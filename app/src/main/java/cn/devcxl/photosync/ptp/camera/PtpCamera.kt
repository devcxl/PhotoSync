package cn.devcxl.photosync.ptp.camera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.ptp.camera.vendor.CameraEventChannel
import cn.devcxl.photosync.ptp.camera.vendor.CanonEosProfile
import cn.devcxl.photosync.ptp.camera.vendor.GenericProfile
import cn.devcxl.photosync.ptp.camera.vendor.NikonProfile
import cn.devcxl.photosync.ptp.camera.vendor.SonyProfile
import cn.devcxl.photosync.ptp.camera.vendor.VendorProfile
import cn.devcxl.photosync.ptp.detect.CameraDetector
import cn.devcxl.photosync.ptp.protocol.PtpByteReader
import cn.devcxl.photosync.ptp.protocol.PtpDeviceException
import cn.devcxl.photosync.ptp.protocol.PtpDeviceInfo
import cn.devcxl.photosync.ptp.protocol.PtpException
import cn.devcxl.photosync.ptp.protocol.PtpObjectInfo
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException
import cn.devcxl.photosync.ptp.protocol.PtpResponsePacket
import cn.devcxl.photosync.ptp.protocol.PtpSessionException
import cn.devcxl.photosync.ptp.protocol.PtpStorageInfo
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import cn.devcxl.photosync.ptp.transport.PtpTransport
import cn.devcxl.photosync.ptp.transport.UsbPtpTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A connected camera: one USB link, one PTP session, and the operations the sync loop uses.
 *
 * Every operation is serialized through one mutex because the protocol has a single
 * transaction id and the USB endpoints carry one transfer at a time. Public methods are
 * suspending and run on [ioDispatcher]; callers never touch the transport themselves.
 */
internal class PtpCamera private constructor(
    /** The USB device this camera is reached through, or null when built from a fake link. */
    val usbDevice: UsbDevice?,
    private val transport: PtpTransport,
    internal val profile: VendorProfile,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val persistMutex = Mutex()
    private val session = PtpSession(transport)

    /** Objects this session has already imported; shared with the delete path. */
    internal val handledObjects = HandledObjects()

    /** Persists [handledObjects] across sessions; set by the owner before syncing starts. */
    var importedHandleStore: ImportedHandleStore? = null

    private var deviceInfo: PtpDeviceInfo? = null

    /** Set when a transfer failure left the USB pipe in an unknown state. */
    @Volatile
    private var linkBroken = false

    /** What the camera says it implements; available once [connect] returns. */
    internal val info: PtpDeviceInfo?
        get() = deviceInfo

    internal val modelName: String
        get() = listOfNotNull(deviceInfo?.manufacturer, deviceInfo?.model)
            .joinToString(" ")
            .ifBlank { usbDevice?.deviceName ?: "PTP camera" }

    internal fun supportsOperation(opcode: Int): Boolean = deviceInfo?.supportsOperation(opcode) == true

    internal val supportsObjectDeletion: Boolean
        get() = supportsOperation(PtpOp.DELETE_OBJECT)

    /**
     * Opens a PTP session, including the recovery for a camera that still holds one.
     *
     * A camera whose previous host process was killed may refuse the session takeover even after
     * the connect handshake; resetting the pipe once and retrying turns that dead-session refusal
     * into a normal connect instead of a failure the user can only clear by replugging.
     *
     * @throws cn.devcxl.photosync.ptp.protocol.PtpException when the camera refuses
     */
    internal suspend fun openSession() = locked {
        val supportsCloseSession = deviceInfo?.supportsOperation(PtpOp.CLOSE_SESSION) ?: true
        try {
            session.open(supportsCloseSession = supportsCloseSession)
        } catch (e: PtpSessionException) {
            Timber.w(e, "OpenSession rejected; resetting the device and retrying once")
            session.abandon()
            transport.cancelAndResetDevice(null)
            session.open(supportsCloseSession = supportsCloseSession)
        }
        profile.onSessionStart(session)
        Timber.i("PTP session opened on %s (%s)", modelName, profile.name)
    }

    /**
     * Closes the session and releases the USB device. Safe to call twice.
     *
     * Runs the release as a non-cancellable block: a disconnect cancels the coroutine that
     * is waiting here, and abandoning the teardown half-way would leave the USB interface
     * claimed and the device invisible until the next replug.
     */
    internal suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            withContext(ioDispatcher) {
                if (linkBroken) {
                    Timber.i("Skipping CloseSession on broken USB link; cancelling and resetting device")
                    runCatching { transport.cancelAndResetDevice(session.inFlightTransactionId) }
                        .onFailure { Timber.w(it, "cancelAndResetDevice failed during broken-link teardown") }
                } else {
                    try {
                        session.closeSession()
                    } catch (e: PtpException) {
                        Timber.w(e, "CloseSession failed during teardown; cancelling and resetting device")
                        runCatching { transport.cancelAndResetDevice(session.inFlightTransactionId) }
                    }
                }
                session.abandon()
                transport.close()
            }
        }
    }

    /**
     * Reads and validates the storage ids.
     *
     * A storage the camera lists but cannot describe is dropped: every later operation on
     * it would fail, and the sync loop should keep running on the storages that work.
     */
    internal suspend fun usableStorageIds(): IntArray = locked {
        val declared = readStorageIds()
        val usable = declared.filter { storageId ->
            try {
                val info = readStorageInfo(storageId)
                Timber.i(
                    "Storage 0x%08x '%s': %d of %d bytes free, access 0x%04x",
                    storageId,
                    info.volumeLabel ?: info.description ?: "?",
                    info.freeSpaceBytes,
                    info.maxCapacityBytes,
                    info.accessCapability,
                )
                true
            } catch (e: PtpTransportException) {
                // A dead USB link is not a missing card slot; the caller has to see it.
                throw e
            } catch (e: PtpException) {
                Timber.w(e, "Storage %d is not usable, skipping", storageId)
                false
            }
        }
        val ids = usable.toIntArray()
        if (ids.isNotEmpty()) {
            profile.afterStorageEnumeration(session, ids)
        }
        ids
    }

    /**
     * Every object handle the given storages currently report.
     *
     * [complete] is false when a storage could not be enumerated. Callers that reconcile
     * their "already imported" bookkeeping against this list must not prune on a partial
     * result: the missing handles did not leave the camera, they were simply not read, and
     * dropping them would make the whole card look new on the next pass.
     */
    internal suspend fun objectHandles(storageIds: IntArray): ObjectHandles = locked {
        val handles = mutableListOf<Int>()
        var complete = true
        for (storageId in storageIds) {
            try {
                handles += readObjectHandles(storageId).toList()
            } catch (e: PtpTransportException) {
                // Reporting an empty card here would look like every photo vanished, and
                // the next pass would download all of them again.
                throw e
            } catch (e: PtpException) {
                Timber.w(e, "Object enumeration failed for storage %d", storageId)
                complete = false
            }
        }
        ObjectHandles(handles, complete)
    }

    /**
     * Reads one object's metadata.
     *
     * @return the metadata, or null when the camera rejects the handle
     */
    internal suspend fun objectInfo(handle: Int): PtpObjectInfo? = locked {
        val exchange = session.transactForData(PtpOp.GET_OBJECT_INFO, handle)
        if (!exchange.response.isOk) {
            Timber.d("GetObjectInfo(0x%08x) answered %s", handle, exchange.response.code)
            return@locked null
        }
        val data = exchange.data ?: throw PtpProtocolException("GetObjectInfo returned no dataset")
        PtpObjectInfo.parse(handle, data)
    }

    /** The access capability of one storage, used before a delete. */
    internal suspend fun storageAcceptsWrites(storageId: Int): Boolean = locked {
        !readStorageInfo(storageId).isReadOnly
    }

    /**
     * Downloads one object into [target].
     *
     * The file is written while the camera streams, so a 40 MiB RAW never has to fit in
     * memory. A failed transfer deletes the partial file: a truncated photo on disk would
     * later look imported and never be re-fetched.
     *
     * @return true when the camera confirmed the whole object
     * @throws cn.devcxl.photosync.ptp.protocol.PtpException when the transfer failed
     */
    internal suspend fun download(
        handle: Int,
        target: File,
        onProgress: (transferred: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean = locked {
        when (val start = session.beginData(PtpOp.GET_OBJECT, handle)) {
            is PtpDataStart.Rejected -> {
                Timber.w("GetObject(0x%08x) rejected with %s", handle, start.response.code)
                false
            }

            is PtpDataStart.Streaming -> {
                val total = start.declaredLength
                if (total <= 0) {
                    Timber.w("GetObject(0x%08x) announced %d bytes", handle, total)
                    try {
                        if (total == 0) {
                            session.finishData()
                        } else {
                            linkBroken = true
                        }
                    } catch (e: Exception) {
                        linkBroken = true
                        throw e
                    }
                    return@locked false
                }
                try {
                    writeStream(start.body, target, total, onProgress)
                    val response = session.finishData()
                    if (!response.isOk) {
                        target.delete()
                        Timber.w("GetObject(0x%08x) finished with %s", handle, response.code)
                        false
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    target.delete()
                    // Once the data phase started, the rest of it is still on the wire
                    // whatever failed — a USB error, or a disk that filled up before one
                    // byte was read. Issuing another command now would read those bytes as
                    // its response, so the link is only usable after a reconnect.
                    linkBroken = true
                    Timber.w(e, "Object transfer aborted mid-container, link marked unusable")
                    throw e
                }
            }
        }
    }

    /**
     * Deletes one object from the camera.
     *
     * @return the camera's response code; [cn.devcxl.photosync.ptp.protocol.PtpStatus.OK]
     *   when the object is gone
     */
    internal suspend fun deleteObject(handle: Int): Int = locked {
        val response = session.transact(PtpOp.DELETE_OBJECT, handle, 0)
        if (response.isOk) {
            handledObjects.remove(handle)
            persistHandledObjects("delete 0x%08x".format(handle))
        }
        response.code
    }

    /** Builds this camera's vendor event channel, or null when it has none. */
    internal fun createEventChannel(): CameraEventChannel? = profile.createEventChannel(session)

    /** Polls the vendor event channel without releasing the session lock. */
    internal suspend fun pollEvents(channel: CameraEventChannel): List<cn.devcxl.photosync.ptp.camera.vendor.CameraEvent> =
        locked { channel.poll() }

    /**
     * Stores the current handle list through [importedHandleStore].
     *
     * A lost write only costs de-duplication in a later session, so it is logged and never
     * allowed to fail an import that already succeeded.
     */
    internal suspend fun persistHandledObjects(reason: String) {
        val store = importedHandleStore ?: return
        persistMutex.withLock {
            val snapshot = handledObjects.snapshot()
            withContext(ioDispatcher) {
                try {
                    store.save(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Saving imported handles failed (%s)", reason)
                }
            }
        }
    }

    /** Loads the persisted handle list, or null when this device has no record yet. */
    internal suspend fun loadPersistedHandles(): List<Int>? {
        val store = importedHandleStore ?: return null
        return withContext(ioDispatcher) {
            try {
                store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Loading imported handles failed")
                null
            }
        }
    }

    private fun writeStream(
        body: cn.devcxl.photosync.ptp.transport.PtpBody,
        target: File,
        total: Int,
        onProgress: (Int, Int) -> Unit,
    ) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create ${parent.absolutePath}")
            }
        }
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var transferred = 0
        var nextReport = PROGRESS_STEP_BYTES
        BufferedOutputStream(FileOutputStream(target), WRITE_BUFFER_BYTES).use { out ->
            while (body.remaining > 0) {
                val read = body.read(buffer, 0, minOf(buffer.size, body.remaining))
                if (read <= 0) {
                    throw PtpTransportException("Object stream ended with ${body.remaining} bytes pending")
                }
                out.write(buffer, 0, read)
                transferred += read
                if (transferred >= nextReport || body.remaining == 0) {
                    onProgress(transferred, total)
                    while (nextReport <= transferred) {
                        nextReport += PROGRESS_STEP_BYTES
                    }
                }
            }
        }
    }

    private fun readStorageIds(): IntArray {
        val exchange = session.transactForData(PtpOp.GET_STORAGE_IDS)
        requireOk(exchange.response, "GetStorageIDs")
        return PtpByteReader(requireData(exchange, "GetStorageIDs")).u32Array()
    }

    private fun readStorageInfo(storageId: Int): PtpStorageInfo {
        val exchange = session.transactForData(PtpOp.GET_STORAGE_INFO, storageId)
        requireOk(exchange.response, "GetStorageInfo($storageId)")
        return PtpStorageInfo.parse(requireData(exchange, "GetStorageInfo"))
    }

    private fun readObjectHandles(storageId: Int): IntArray {
        val exchange = session.transactForData(PtpOp.GET_OBJECT_HANDLES, storageId, 0, 0)
        requireOk(exchange.response, "GetObjectHandles($storageId)")
        return PtpByteReader(requireData(exchange, "GetObjectHandles")).u32Array()
    }

    private fun readDeviceInfo(): PtpDeviceInfo {
        val exchange = session.transactForData(PtpOp.GET_DEVICE_INFO)
        requireOk(exchange.response, "GetDeviceInfo")
        return PtpDeviceInfo.parse(requireData(exchange, "GetDeviceInfo"))
    }

    private fun requireData(exchange: PtpDataExchange, operation: String): ByteArray =
        exchange.data ?: throw PtpProtocolException("$operation returned no dataset")

    private fun requireOk(response: PtpResponsePacket, operation: String) {
        if (!response.isOk) {
            throw PtpDeviceException(response.code, "$operation failed")
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        if (linkBroken) {
            throw PtpTransportException("The USB link is unusable after a failed transfer; reconnect the camera")
        }
        withContext(ioDispatcher) {
            try {
                block()
            } catch (e: PtpTransportException) {
                linkBroken = true
                throw e
            } catch (e: PtpProtocolException) {
                linkBroken = true
                throw e
            }
        }
    }

    companion object {
        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val WRITE_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024

        /**
         * Opens the USB device, resets its PTP state, and reads the device info.
         *
         * The device info is read before any session exists, which PIMA 15740 explicitly
         * allows; the sync path needs it to pick the trigger channel.
         */
        internal suspend fun connect(
            usbManager: UsbManager,
            device: UsbDevice,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PtpCamera = withContext(ioDispatcher) {
            val transport = UsbPtpTransport.open(usbManager, device)
            connectWithTransport(
                usbDevice = device,
                transport = transport,
                profile = profileFor(device.vendorId),
                ioDispatcher = ioDispatcher,
            )
        }

        internal suspend fun connectWithTransport(
            usbDevice: UsbDevice?,
            transport: PtpTransport,
            profile: VendorProfile = GenericProfile,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            settleDelaysMs: LongArray = PtpCameraInitializer.DEFAULT_SETTLE_DELAYS_MS,
        ): PtpCamera = withContext(ioDispatcher) {
            val camera = PtpCamera(
                usbDevice = usbDevice,
                transport = transport,
                profile = profile,
                ioDispatcher = ioDispatcher,
            )
            try {
                camera.initialise(settleDelaysMs)
            } catch (e: Exception) {
                transport.close()
                throw e
            }
            camera
        }

        internal fun profileFor(vendorId: Int): VendorProfile = when (vendorId) {
            CameraDetector.VENDOR_ID_CANON -> CanonEosProfile()
            CameraDetector.VENDOR_ID_NIKON -> NikonProfile()
            CameraDetector.VENDOR_ID_SONY -> SonyProfile()
            else -> GenericProfile
        }
    }

    /**
     * Runs the connect handshake; [PtpCameraInitializer] owns the reset/retry policy.
     * The device info it reads is published back into [info].
     */
    private suspend fun initialise(settleDelaysMs: LongArray) {
        PtpCameraInitializer(
            transport = transport,
            session = session,
            readDeviceInfo = ::readDeviceInfo,
            onDeviceInfo = { deviceInfo = it },
        ).initialise(settleDelaysMs)
    }
}
