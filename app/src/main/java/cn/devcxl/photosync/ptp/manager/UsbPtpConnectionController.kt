package cn.devcxl.photosync.ptp.manager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.App
import cn.devcxl.photosync.ptp.camera.CameraSyncEngine
import cn.devcxl.photosync.ptp.camera.ImportedHandleStore
import cn.devcxl.photosync.ptp.camera.PtpCamera
import cn.devcxl.photosync.ptp.detect.isSupportedCamera
import cn.devcxl.photosync.ptp.detect.toUsbDeviceTraits
import cn.devcxl.photosync.ptp.interfaces.FileDownloadedListener
import cn.devcxl.photosync.ptp.interfaces.FileTransferListener
import cn.devcxl.photosync.ptp.protocol.PtpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Owns the USB PTP session: detects attached cameras, connects, disconnects, and hands the
 * delete and transfer entry points to the UI.
 *
 * The connection state is a [StateFlow] so the activity can render it without polling. A
 * session is only opened on explicit request: while it is open the camera is locked out of
 * shooting, so plugging the cable alone must not start one.
 */
class UsbPtpConnectionController(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val _connectionState = MutableStateFlow<UsbPtpConnectionState>(UsbPtpConnectionState.Idle)
    private val _availableCameras = MutableStateFlow<List<UsbPtpDeviceId>>(emptyList())

    private var currentCamera: PtpCamera? = null
    private var currentDevice: UsbDevice? = null
    private var currentDeviceId: UsbPtpDeviceId? = null
    private var pendingPermissionDeviceId: UsbPtpDeviceId? = null
    private var syncJob: Job? = null

    @Volatile
    private var transferListener: FileTransferListener? = null

    @Volatile
    private var downloadedListener: FileDownloadedListener? = null

    @Volatile
    private var fileDownloadPath: String? = null

    /**
     * Whether the connected camera advertises `DeleteObject`.
     *
     * Cached at connect time so the UI can ask without a round trip.
     */
    @Volatile
    private var connectedCameraSupportsDeletion = false

    val connectionState: StateFlow<UsbPtpConnectionState> = _connectionState.asStateFlow()

    /**
     * Cameras currently attached, as last seen by [refreshAvailableCameras].
     *
     * Presence only: an empty list does not mean disconnected, and a non-empty one does
     * not mean connected.
     */
    val availableCameras: StateFlow<List<UsbPtpDeviceId>> = _availableCameras.asStateFlow()

    /** Forwards progress to whichever listener is set now, not the one set at connect. */
    private val transferProxy = FileTransferListener { handle, total, transferred ->
        transferListener?.onFileTransfer(handle, total, transferred)
    }

    private val downloadedProxy = FileDownloadedListener { handle, file, duration ->
        downloadedListener?.onFileDownloaded(handle, file, duration)
    }

    /**
     * Re-reads the attached cameras. Never connects.
     */
    fun refreshAvailableCameras() {
        scope.launch {
            _availableCameras.value = attachedCameras().map { it.toUsbPtpDeviceId() }
        }
    }

    /** Connects the first attached camera, asking for USB permission when it is missing. */
    fun connectToAvailableCamera() {
        scope.launch {
            val device = attachedCameras().firstOrNull()
            if (device == null) {
                _connectionState.value = UsbPtpConnectionState.Disconnected(null, "no_device")
                return@launch
            }
            tryStartConnection(device)
        }
    }

    fun onUsbDetached(device: UsbDevice?) {
        scope.launch {
            val detachedId = device?.toUsbPtpDeviceId()
            val shouldDisconnect = stateMutex.withLock {
                if (detachedId == null) {
                    currentDeviceId ?: pendingPermissionDeviceId
                } else {
                    resolveDetachedDisconnectTarget(currentDeviceId, pendingPermissionDeviceId, detachedId)
                }
            }
            if (shouldDisconnect != null) {
                disconnectInternal("usb_detached", shouldDisconnect)
            }
            _availableCameras.value = attachedCameras().map { it.toUsbPtpDeviceId() }
        }
    }

    /**
     * Handles the USB permission result.
     *
     * The granted flag is deliberately *not* read from the broadcast. The permission
     * [PendingIntent] is created with [PendingIntent.FLAG_IMMUTABLE] (a mutable one with
     * an implicit intent throws on target SDK 34+), while the system injects
     * `EXTRA_DEVICE` and `EXTRA_PERMISSION_GRANTED` at send time — exactly the extras an
     * immutable PendingIntent drops. Reading them reported a granted permission as denied.
     *
     * Instead the device tracked when the permission was requested is re-resolved, and
     * [UsbManager.hasPermission] decides. That is also immune to a forged broadcast.
     */
    fun onUsbPermissionResult() {
        scope.launch {
            val pendingDeviceId = stateMutex.withLock { pendingPermissionDeviceId }
            if (pendingDeviceId == null) {
                Timber.w("USB permission result without a pending request, ignoring")
                return@launch
            }
            val device = usbManager.deviceList.values
                .firstOrNull { it.toUsbPtpDeviceId() == pendingDeviceId }
            if (device == null) {
                stateMutex.withLock { pendingPermissionDeviceId = null }
                _connectionState.value =
                    UsbPtpConnectionState.Error(pendingDeviceId, "permission_missing_device")
                return@launch
            }
            if (!usbManager.hasPermission(device)) {
                stateMutex.withLock { pendingPermissionDeviceId = null }
                _connectionState.value = UsbPtpConnectionState.Error(pendingDeviceId, "permission_denied")
                return@launch
            }
            connectInternal(device)
        }
    }

    fun disconnect(reason: String = "manual_disconnect") {
        scope.launch {
            disconnectInternal(reason, null)
        }
    }

    /** Whether the connected camera can delete objects at all. */
    fun canDeleteOnCamera(): Boolean = connectedCameraSupportsDeletion

    /**
     * Deletes one photo from the camera's storage.
     *
     * Runs the checks the standard ties to a successful delete before sending anything, so
     * the caller gets a specific reason instead of one generic failure.
     *
     * @param objectHandle The PTP handle recorded when the photo was imported.
     */
    suspend fun deleteOnCamera(objectHandle: Int): CameraDeleteResult = withContext(ioDispatcher) {
        val camera = stateMutex.withLock { currentCamera }
            ?: return@withContext CameraDeleteResult.NotConnected
        if (!camera.supportsObjectDeletion) {
            return@withContext CameraDeleteResult.NotSupported
        }
        try {
            val objectInfo = camera.objectInfo(objectHandle)
                ?: return@withContext CameraDeleteResult.NotFound
            if (objectInfo.isFolder) {
                // Would take every photo in the folder with it; the app never imports one.
                return@withContext CameraDeleteResult.Failed("association_object")
            }
            if (objectInfo.isProtected) {
                return@withContext CameraDeleteResult.Protected
            }
            if (!camera.storageAcceptsWrites(objectInfo.storageId)) {
                return@withContext CameraDeleteResult.ReadOnly
            }
            cameraDeleteResultOf(camera.deleteObject(objectHandle))
        } catch (e: PtpException) {
            Timber.w(e, "deleteOnCamera failed for handle %s", objectHandle)
            CameraDeleteResult.Failed(e.message)
        } catch (e: Exception) {
            Timber.e(e, "deleteOnCamera unexpected failure for handle %s", objectHandle)
            CameraDeleteResult.Failed(e.message)
        }
    }

    fun setFileDownloadPath(path: String?) {
        fileDownloadPath = path
    }

    fun setFileTransferListener(listener: FileTransferListener?) {
        transferListener = listener
    }

    fun setFileDownloadedListener(listener: FileDownloadedListener?) {
        downloadedListener = listener
    }

    private fun attachedCameras(): List<UsbDevice> =
        usbManager.deviceList.values.filter { isSupportedCamera(it.toUsbDeviceTraits()) }

    private suspend fun tryStartConnection(device: UsbDevice): Boolean {
        val candidateId = device.toUsbPtpDeviceId()
        val shouldSkip = stateMutex.withLock {
            val trackedDevice = currentDeviceId ?: pendingPermissionDeviceId
            shouldSkipConnect(_connectionState.value, trackedDevice, candidateId)
        }
        if (shouldSkip) {
            Timber.i("USB device already managed, skipping reconnect: %s", candidateId.deviceName)
            return true
        }
        if (!usbManager.hasPermission(device)) {
            requestPermission(device, candidateId)
            return false
        }
        return connectInternal(device)
    }

    private suspend fun requestPermission(device: UsbDevice, deviceId: UsbPtpDeviceId) {
        stateMutex.withLock {
            pendingPermissionDeviceId = deviceId
            _connectionState.value = UsbPtpConnectionState.PermissionRequested(deviceId)
        }
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            device.deviceId,
            Intent(App.ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
        Timber.d("请求相机 USB 授权: %s", device.deviceName)
    }

    private suspend fun connectInternal(device: UsbDevice): Boolean {
        val deviceId = device.toUsbPtpDeviceId()
        disconnectInternal("reconnect_before_connect", deviceId, resetStateAfterDisconnect = false)
        stateMutex.withLock {
            currentDeviceId = deviceId
            pendingPermissionDeviceId = null
        }
        _connectionState.value = UsbPtpConnectionState.Connecting(deviceId)

        var produced: PtpCamera? = null
        val camera = try {
            val created = PtpCamera.connect(usbManager, device, ioDispatcher)
            produced = created
            created.importedHandleStore = SyncDeviceHandleStore(device)
            created.openSession()
            created
        } catch (e: Exception) {
            runCatching { produced?.close() }
                .onFailure { closeErr -> Timber.w(closeErr, "Failed to close the camera after connect failure") }
            stateMutex.withLock { currentDeviceId = null }
            _connectionState.value = UsbPtpConnectionState.Error(deviceId, "connect_failed", e.message)
            Timber.e(e, "connectInternal failed")
            return false
        }

        stateMutex.withLock {
            currentCamera = camera
            currentDevice = device
            connectedCameraSupportsDeletion = camera.supportsObjectDeletion
        }
        // Publish the state before the loop starts, so a listener installed on Connected
        // cannot miss the first import.
        _connectionState.value = UsbPtpConnectionState.Connected(deviceId)
        startSync(camera)
        return true
    }

    /**
     * Starts the import loop for [camera].
     *
     * The engine owns the trigger decision; the controller only supplies the environment it
     * runs in. Launching it after the state is published means a listener set from the UI
     * right after `Connected` already sees the first import.
     */
    private fun startSync(camera: PtpCamera) {
        val engine = CameraSyncEngine(
            camera = camera,
            downloadDirectory = { fileDownloadPath?.let(::File) },
            transferListener = transferProxy,
            downloadedListener = downloadedProxy,
        )
        syncJob = scope.launch {
            // The camera may have been disconnected between publishing Connected and this
            // job starting; running the loop then would fight the teardown.
            if (stateMutex.withLock { currentCamera } !== camera) {
                Timber.d("Sync loop skipped, camera was released before it started")
                return@launch
            }
            var failure: Exception? = null
            try {
                engine.run()
            } catch (e: CancellationException) {
                Timber.d("Sync loop cancelled")
                throw e
            } catch (e: Exception) {
                failure = e
                Timber.e(e, "Sync loop stopped on an unexpected failure")
            }
            // The session is over either way: a loop that died leaves a camera that looks
            // connected but will never import anything, which is worse than saying so.
            failure?.let { releaseAfterSyncFailure(camera) }
        }
    }

    /**
     * Tears the session down after [camera]'s sync loop failed.
     *
     * Runs from the loop's own coroutine, so it must not cancel the job it is running in:
     * the cancellation would abort the USB teardown itself.
     */
    private suspend fun releaseAfterSyncFailure(camera: PtpCamera) {
        disconnectInternal(
            reason = "sync_failed",
            targetDeviceId = camera.usbDevice?.toUsbPtpDeviceId(),
            cancelSync = false,
        )
    }

    private suspend fun disconnectInternal(
        reason: String,
        targetDeviceId: UsbPtpDeviceId?,
        resetStateAfterDisconnect: Boolean = true,
        cancelSync: Boolean = true,
    ) {
        stateMutex.withLock {
            if (currentCamera == null && currentDeviceId == null && pendingPermissionDeviceId == null) {
                if (resetStateAfterDisconnect && _connectionState.value !is UsbPtpConnectionState.Idle) {
                    _connectionState.value = UsbPtpConnectionState.Disconnected(targetDeviceId, reason)
                }
                return
            }
        }
        val cameraToClose: PtpCamera?
        val jobToCancel: Job?
        val deviceToDisconnect: UsbPtpDeviceId?
        stateMutex.withLock {
            deviceToDisconnect = targetDeviceId ?: currentDeviceId ?: pendingPermissionDeviceId
            cameraToClose = currentCamera
            jobToCancel = syncJob
            if (deviceToDisconnect != null) {
                _connectionState.value = UsbPtpConnectionState.Disconnecting(deviceToDisconnect, reason)
            }
            currentCamera = null
            currentDevice = null
            currentDeviceId = null
            pendingPermissionDeviceId = null
            syncJob = null
            connectedCameraSupportsDeletion = false
        }

        if (cancelSync) {
            jobToCancel?.cancel()
        }
        try {
            cameraToClose?.close()
        } catch (e: PtpException) {
            Timber.w(e, "disconnectInternal close failed")
        } catch (e: Exception) {
            Timber.w(e, "disconnectInternal unexpected close failure")
        }

        if (resetStateAfterDisconnect) {
            _connectionState.value = UsbPtpConnectionState.Disconnected(deviceToDisconnect, reason)
        }
    }

    /**
     * Persists imported handles in the per-device sync record.
     *
     * A separate type from the PTP layer so the protocol code never depends on Room.
     */
    private class SyncDeviceHandleStore(private val device: UsbDevice) : ImportedHandleStore {
        override suspend fun load(): List<Int>? = withContext(Dispatchers.IO) {
            val manager = SyncDeviceManager(device)
            val record = manager.getSyncDevice(device)
            if (record?.syncAt == null) null else manager.getIdList(record)
        }

        override suspend fun save(handles: List<Int>) = withContext(Dispatchers.IO) {
            val manager = SyncDeviceManager(device)
            manager.updateDeviceInfo()
            manager.updateIdList(handles)
        }
    }
}
