package cn.devcxl.photosync.ptp.camera

import cn.devcxl.photosync.ptp.camera.vendor.CameraEvent
import cn.devcxl.photosync.ptp.camera.vendor.CameraEventChannel
import cn.devcxl.photosync.ptp.camera.vendor.NikonEventReader
import cn.devcxl.photosync.ptp.interfaces.FileDownloadedListener
import cn.devcxl.photosync.ptp.interfaces.FileTransferListener
import cn.devcxl.photosync.ptp.protocol.PtpDeviceException
import cn.devcxl.photosync.ptp.protocol.PtpException
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.File
import java.util.Random

/**
 * Runs one camera's sync loop: notice a new object, download it, remember it.
 *
 * Two trigger channels share the same import and bookkeeping code. The event channel is
 * preferred because the camera reports a photo the moment it is shot; the object-list poll
 * is the fallback that always works, and is also where an event channel that keeps failing
 * lands mid-session.
 *
 * A session only ever imports what appears after it opened: the photos already on the card
 * are not this app's job, and re-importing a full card on every new install would be a
 * surprise. The one exception is a device with no sync record yet, where the card is read
 * once to learn what "already there" means.
 *
 * @param downloadDirectory resolved on every import, so a path chosen after the session
 *   opened takes effect without reconnecting; null means "do not download yet"
 * @param transferListener progress of the object being written, or null
 * @param downloadedListener completion of each object, or null
 */
internal class CameraSyncEngine(
    private val camera: PtpCamera,
    private val downloadDirectory: () -> File?,
    private val transferListener: FileTransferListener?,
    private val downloadedListener: FileDownloadedListener?,
    private val random: Random = Random(),
) {
    private val retries = EventRetryQueue()

    suspend fun run() {
        val storages = camera.usableStorageIds()
        if (storages.isEmpty()) {
            Timber.w("No usable storage on %s, sync loop not started", camera.modelName)
            return
        }

        camera.handledObjects.replace(establishBaseline(storages))

        val channel = eventChannelOrNull()
        if (channel == null) {
            Timber.i("Sync trigger: object-list poll (%s)", camera.profile.name)
            runPollLoop(storages)
        } else {
            Timber.i("Sync trigger: event channel %s", channel.description)
            runEventLoop(channel, storages)
        }
    }

    /**
     * The handled set a session starts from.
     *
     * The stored record wins when it exists: it is the only source that knows about photos
     * imported over the event channel in an earlier session. Without one, the card itself is
     * the baseline unless the caller asked to import everything.
     */
    private suspend fun establishBaseline(storages: IntArray): List<Int> {
        camera.loadPersistedHandles()?.let { stored ->
            Timber.i("Restored %d imported handles for %s", stored.size, camera.modelName)
            return stored
        }
        // No record: this is the first connect, so what is on the card now becomes
        // "already imported" rather than a queue to download.
        val baseline = camera.objectHandles(storages)
        if (!baseline.complete) {
            // Storing a baseline that is missing a storage would make this session's
            // partial view permanent: every photo on the unread storage would look new on
            // each later connect. Keep it in memory for this session only and let the next
            // connect build a complete one.
            Timber.w(
                "First-session baseline is partial (%d objects); not storing it",
                baseline.handles.size,
            )
            return baseline.handles
        }
        camera.handledObjects.replace(baseline.handles)
        camera.persistHandledObjects("session baseline")
        Timber.i("First session for %s, baseline holds %d objects", camera.modelName, baseline.handles.size)
        return baseline.handles
    }

    private fun eventChannelOrNull(): CameraEventChannel? {
        val operation = camera.profile.eventChannelOperation
        val decision = chooseTriggerChannel(operation, camera.info?.operationsSupported.orEmpty())
        if (decision != TriggerChannel.EVENT) {
            return null
        }
        return camera.createEventChannel()
    }

    // ------------------------------------------------------------------ event channel

    private suspend fun runEventLoop(channel: CameraEventChannel, storages: IntArray) {
        channel.prepare()
        // The channel only reports objects added while the session is open, so the first
        // pass finds what was shot while the camera was unplugged. Reconnecting opens a new
        // session and gets the same pass; no timer ever runs one.
        try {
            reconcile(storages)
        } catch (e: PtpTransportException) {
            throw e
        } catch (e: PtpException) {
            // A body that is still committing its last capture answers the enumeration with
            // a busy code; the events that follow will still arrive.
            Timber.w(e, "First reconcile failed, continuing on the event channel")
        }

        var pollDelayMs = EVENT_POLL_FAST_MS
        var consecutiveFailures = 0
        var eventsSeen = 0
        var objectsImported = 0
        try {
            while (currentCoroutineContext().isActive) {
                objectsImported += drainRetries()
                val events = try {
                    camera.pollEvents(channel).also { consecutiveFailures = 0 }
                } catch (e: PtpDeviceException) {
                    if (e.code == PtpStatus.OPERATION_NOT_SUPPORTED) {
                        Timber.w("Event channel is not supported after all, switching to object poll")
                        runPollLoop(storages)
                        return
                    }
                    consecutiveFailures++
                    Timber.w(e, "Event poll failed (%d in a row)", consecutiveFailures)
                    if (shouldFallBackToPolling(consecutiveFailures)) {
                        Timber.w("Event channel keeps failing, switching to object poll")
                        runPollLoop(storages)
                        return
                    }
                    emptyList()
                } catch (e: PtpTransportException) {
                    throw e
                } catch (e: PtpException) {
                    consecutiveFailures++
                    Timber.w(e, "Event poll failed (%d in a row)", consecutiveFailures)
                    if (shouldFallBackToPolling(consecutiveFailures)) {
                        Timber.w("Event channel keeps failing, switching to object poll")
                        runPollLoop(storages)
                        return
                    }
                    emptyList()
                }

                eventsSeen += events.size
                objectsImported += dispatch(events, storages)
                pollDelayMs = nextEventPollDelay(
                    pollDelayMs,
                    maxOf(events.size, if (retries.hasPending()) 1 else 0),
                )
                // Held handles are pending work, but nothing can be written yet: poll at the
                // idle rate instead of every 200 ms waiting for a directory to appear.
                delay(if (downloadDirectory() == null) EVENT_POLL_IDLE_MS else pollDelayMs)
            }
        } finally {
            Timber.i(
                "Event channel stopped: %d events, %d objects imported (%s)",
                eventsSeen,
                objectsImported,
                channel.description,
            )
        }
    }

    /**
     * Acts on one poll's events, in the order the camera reported them.
     *
     * A failed import never takes the channel down: the handle is queued for a retry, which
     * is what covers a camera that announces a file before it is readable.
     */
    private suspend fun dispatch(events: List<CameraEvent>, storages: IntArray): Int {
        var reconcileRequested = false
        var imported = 0
        for (event in events) {
            Timber.v("%s", event.description)
            if (event.reconcileRequested) {
                reconcileRequested = true
            }
            val handle = event.newObjectHandle ?: continue
            if (handle in camera.handledObjects.snapshot()) {
                retries.markSucceeded(handle)
                continue
            }
            when (importSafely(handle)) {
                ImportOutcome.IMPORTED -> {
                    rememberImported(handle, "event import 0x%08x".format(handle))
                    retries.markSucceeded(handle)
                    imported++
                }
                ImportOutcome.NOT_READY -> {
                    Timber.d("Object 0x%08x not readable yet, queued for retry", handle)
                    retries.enqueue(handle)
                }
                ImportOutcome.NO_DESTINATION -> {
                    // The event that announced this object will not repeat, so the handle
                    // has to be kept. Queuing does not charge an attempt, which leaves the
                    // budget for actual transfer failures.
                    Timber.d("No download directory configured, object 0x%08x queued", handle)
                    retries.enqueue(handle)
                }
            }
        }
        if (reconcileRequested) {
            Timber.i("Camera reported it may have dropped events, reconciling once")
            try {
                reconcile(storages)
            } catch (e: PtpTransportException) {
                throw e
            } catch (e: PtpException) {
                // The events that arrived were still imported; a bad enumeration must not
                // end a session that is otherwise working.
                Timber.w(e, "Reconcile after UnreportedStatus failed")
            }
        }
        return imported
    }

    private suspend fun drainRetries(): Int {
        var imported = 0
        for (handle in retries.pending()) {
            if (handle in camera.handledObjects.snapshot()) {
                retries.markSucceeded(handle)
                continue
            }
            when (importSafely(handle)) {
                ImportOutcome.IMPORTED -> {
                    rememberImported(handle, "retried event import 0x%08x".format(handle))
                    retries.markSucceeded(handle)
                    imported++
                }
                ImportOutcome.NOT_READY -> retries.markFailed(handle)
                ImportOutcome.NO_DESTINATION -> retries.enqueue(handle)
            }
        }
        return imported
    }

    // ------------------------------------------------------------------ object poll

    private suspend fun runPollLoop(storages: IntArray) {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            if (downloadDirectory() == null) {
                // Nothing can be written yet, so there is nothing to reconcile for.
                continue
            }
            try {
                reconcile(storages)
                consecutiveFailures = 0
            } catch (e: PtpTransportException) {
                throw e
            } catch (e: PtpException) {
                // A camera that hiccups right after a delete must not end the session: the
                // next tick is the retry.
                consecutiveFailures++
                Timber.w(e, "Object poll failed (%d in a row)", consecutiveFailures)
            }
        }
    }

    /**
     * Imports what appeared since the last pass, then brings the handled set back in line
     * with what the camera still reports.
     */
    private suspend fun reconcile(storages: IntArray) {
        val enumeration = camera.objectHandles(storages)
        val handled = camera.handledObjects.snapshot()
        val added = newHandles(handled, enumeration.handles)
        Timber.v("New object handles: %s", added)

        val imported = mutableListOf<Int>()
        for (handle in added) {
            when (importSafely(handle)) {
                // Leave the rest for the next pass instead of giving up on all of them.
                ImportOutcome.NOT_READY -> break
                ImportOutcome.NO_DESTINATION -> return
                ImportOutcome.IMPORTED -> imported += handle
            }
        }

        val currentHandled = camera.handledObjects.snapshot().toHashSet()
        val deletedMidPass = handled.filterTo(HashSet()) { it !in currentHandled }
        val next = reconcileHandledSet(handled, enumeration.handles, imported, enumeration.complete)
            .filter { it !in deletedMidPass }
        if (next.toSet() != currentHandled) {
            camera.handledObjects.replace(next)
            camera.persistHandledObjects(
                if (enumeration.complete) "object list pass" else "partial object list pass"
            )
        }
    }

    // ------------------------------------------------------------------ import

    private suspend fun importSafely(handle: Int): ImportOutcome = try {
        importObject(handle)
    } catch (e: PtpTransportException) {
        throw e
    } catch (e: PtpException) {
        Timber.w(e, "Importing object 0x%08x failed", handle)
        ImportOutcome.NOT_READY
    } catch (e: Exception) {
        Timber.e(e, "Importing object 0x%08x failed unexpectedly", handle)
        ImportOutcome.NOT_READY
    }

    /**
     * Downloads one object into the configured directory.
     *
     * @return [ImportOutcome.IMPORTED] when the object is on disk, or when it is a folder
     *   nothing needs downloading from
     */
    private suspend fun importObject(handle: Int): ImportOutcome {
        val directory = downloadDirectory() ?: return ImportOutcome.NO_DESTINATION
        val info = camera.objectInfo(handle) ?: return ImportOutcome.NOT_READY
        if (info.isFolder) {
            return ImportOutcome.IMPORTED
        }
        if (!directory.exists() && !directory.mkdirs()) {
            Timber.e("Unable to create download directory %s", directory.absolutePath)
            return ImportOutcome.NOT_READY
        }
        val name = DownloadNaming.sanitize(info.filename) ?: DownloadNaming.generatedName(random)
        val target = DownloadNaming.uniqueFile(directory, name)
        val startedAt = System.currentTimeMillis()
        val completed = camera.download(handle, target) { transferred, total ->
            transferListener?.onFileTransfer(handle, total, transferred)
        }
        if (!completed) {
            Timber.w("Camera refused object 0x%08x", handle)
            return ImportOutcome.NOT_READY
        }
        downloadedListener?.onFileDownloaded(handle, target, System.currentTimeMillis() - startedAt)
        return ImportOutcome.IMPORTED
    }

    private suspend fun rememberImported(handle: Int, reason: String) {
        // Nikon's SDRAM placeholder is a reusable constant rather than a unique card handle;
        // recording it would cause every subsequent SDRAM capture to be skipped.
        if (handle == NikonEventReader.SDRAM_PLACEHOLDER_HANDLE) {
            return
        }
        if (camera.handledObjects.add(handle)) {
            camera.persistHandledObjects(reason)
        }
    }
}

/** What one import attempt achieved. */
internal enum class ImportOutcome {
    /** The object is on disk, or is a folder there is nothing to download from. */
    IMPORTED,

    /** The camera could not serve it yet; worth another attempt on a later pass. */
    NOT_READY,

    /** No download directory is configured, so no attempt was made at all. */
    NO_DESTINATION,
}
