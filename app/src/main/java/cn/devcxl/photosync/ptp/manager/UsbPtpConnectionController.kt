package cn.devcxl.photosync.ptp.manager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.App
import cn.devcxl.photosync.ptp.detect.isSupportedCamera
import cn.devcxl.photosync.ptp.detect.toUsbDeviceTraits
import cn.devcxl.photosync.ptp.interfaces.FileDownloadedListener
import cn.devcxl.photosync.ptp.interfaces.FileTransferListener
import cn.devcxl.photosync.ptp.params.SyncParams
import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.InitiatorFactory
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.sony.SonyInitiator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Manages the lifecycle of a USB PTP session: detecting attached cameras, connecting,
 * disconnecting, and routing file-transfer events. Exposes a [StateFlow] of
 * [UsbPtpConnectionState] and [availableCameras].
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

    private var currentInitiator: BaselineInitiator? = null
    private var currentDevice: UsbDevice? = null
    private var currentDeviceId: UsbPtpDeviceId? = null
    private var pendingPermissionDeviceId: UsbPtpDeviceId? = null
    private var transferListener: FileTransferListener? = null
    private var downloadedListener: FileDownloadedListener? = null
    private var fileDownloadPath: String? = null

    val connectionState: StateFlow<UsbPtpConnectionState> = _connectionState.asStateFlow()

    /**
     * Cameras currently attached, as last seen by [refreshAvailableCameras].
     *
     * Presence only: an empty list does not mean disconnected, and a non-empty one does
     * not mean connected.
     */
    val availableCameras: StateFlow<List<UsbPtpDeviceId>> = _availableCameras.asStateFlow()

    /**
     * Re-reads the attached cameras. Never connects.
     *
     * Plugging the cable is not the same intent as opening a session: a session locks the
     * camera out of shooting until it is closed, so only the user starts one.
     */
    fun refreshAvailableCameras() {
        scope.launch {
            _availableCameras.value = attachedCameras().map { it.toUsbPtpDeviceId() }
        }
    }

    /**
     * Connects the first attached camera, asking for USB permission when it is missing.
     */
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
     * immutable PendingIntent drops. Reading them reported a granted permission as
     * denied.
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

    fun setFileDownloadPath(path: String?) {
        fileDownloadPath = path
        scope.launch {
            stateMutex.withLock {
                currentInitiator?.setFileDownloadPath(path)
            }
        }
    }

    fun setFileTransferListener(listener: FileTransferListener?) {
        transferListener = listener
        scope.launch {
            stateMutex.withLock {
                currentInitiator?.resetFileTransferListener()
                if (listener != null) {
                    currentInitiator?.setFileTransferListener(listener)
                }
            }
        }
    }

    fun setFileDownloadedListener(listener: FileDownloadedListener?) {
        downloadedListener = listener
        scope.launch {
            stateMutex.withLock {
                currentInitiator?.resetFileDownloadedListener()
                if (listener != null) {
                    currentInitiator?.setFileDownloadedListener(listener)
                }
            }
        }
    }

    private fun attachedCameras(): List<UsbDevice> {
        return usbManager.deviceList.values
            .filter { isSupportedCamera(it.toUsbDeviceTraits()) }
    }

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
        Timber.d("请求设备权限 %s", device.deviceName)
    }

    private suspend fun connectInternal(device: UsbDevice): Boolean {
        val deviceId = device.toUsbPtpDeviceId()
        disconnectInternal("reconnect_before_connect", deviceId, resetStateAfterDisconnect = false)
        stateMutex.withLock {
            currentDeviceId = deviceId
            pendingPermissionDeviceId = null
        }
        _connectionState.value = UsbPtpConnectionState.Connecting(deviceId)

        val initiator = try {
            val produced = InitiatorFactory.produceInitiator(device, usbManager)
            produced.getClearStatus()
            produced.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_POLL_LIST)
            if (produced is SonyInitiator) {
                produced.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_EVENT)
            }
            fileDownloadPath?.let(produced::setFileDownloadPath)
            transferListener?.let(produced::setFileTransferListener)
            downloadedListener?.let(produced::setFileDownloadedListener)
            produced.openSession()
            produced
        } catch (e: Exception) {
            stateMutex.withLock {
                currentDeviceId = null
            }
            _connectionState.value = UsbPtpConnectionState.Error(deviceId, "connect_failed", e.message)
            Timber.e(e, "connectInternal failed")
            return false
        }

        stateMutex.withLock {
            currentInitiator = initiator
            currentDevice = device
        }
        _connectionState.value = UsbPtpConnectionState.Connected(deviceId)
        return true
    }

    private suspend fun disconnectInternal(
        reason: String,
        targetDeviceId: UsbPtpDeviceId?,
        resetStateAfterDisconnect: Boolean = true
    ) {
        stateMutex.withLock {
            if (currentInitiator == null && currentDeviceId == null && pendingPermissionDeviceId == null) {
                if (resetStateAfterDisconnect && _connectionState.value !is UsbPtpConnectionState.Idle) {
                    _connectionState.value = UsbPtpConnectionState.Disconnected(targetDeviceId, reason)
                }
                return
            }
        }
        val initiatorToClose: BaselineInitiator?
        val deviceToDisconnect: UsbPtpDeviceId?
        stateMutex.withLock {
            deviceToDisconnect = targetDeviceId ?: currentDeviceId ?: pendingPermissionDeviceId
            initiatorToClose = currentInitiator
            if (deviceToDisconnect != null) {
                _connectionState.value = UsbPtpConnectionState.Disconnecting(deviceToDisconnect, reason)
            }
            currentInitiator = null
            currentDevice = null
            currentDeviceId = null
            pendingPermissionDeviceId = null
        }

        try {
            initiatorToClose?.close()
        } catch (e: PTPException) {
            Timber.w(e, "disconnectInternal close failed")
        } catch (e: Exception) {
            Timber.w(e, "disconnectInternal unexpected close failure")
        }

        if (resetStateAfterDisconnect) {
            _connectionState.value = UsbPtpConnectionState.Disconnected(deviceToDisconnect, reason)
        }
    }
}
