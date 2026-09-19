package cn.devcxl.photosync.ptp.usbcamera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.widget.ImageView
import cn.devcxl.photosync.ptp.manager.SyncDeviceManager
import cn.devcxl.photosync.ptp.interfaces.FileDownloadedListener
import cn.devcxl.photosync.ptp.interfaces.FileTransferListener
import cn.devcxl.photosync.ptp.params.SyncParams
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random
import timber.log.Timber

/**
 * @author devcxl
 */
open class BaselineInitiator : NameFactory, Runnable {

    companion object {
        const val DEBUG = false
        const val TRACE = false
        const val DEFAULT_TIMEOUT = 1000

        // USB Class-specific control requests; from Annex D.5.2
        private const val CLASS_CANCEL_REQ: Byte = 0x64.toByte()
        private const val CLASS_GET_EVENT_DATA: Byte = 0x65.toByte()
        private const val CLASS_DEVICE_RESET: Byte = 0x66.toByte()
        private const val CLASS_GET_DEVICE_STATUS: Byte = 0x67.toByte()

        @JvmStatic
        fun byteArrayToHex(a: ByteArray): String {
            val sb = StringBuilder(a.size * 2)
            for (b in a)
                sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }

    @JvmField var device: UsbDevice? = null
    protected var intf: UsbInterface? = null
    protected var epIn: UsbEndpoint? = null
    protected var inMaxPS: Int = 0
    protected var epOut: UsbEndpoint? = null
    protected var epEv: UsbEndpoint? = null
    protected var intrMaxPS: Int = 0
    protected lateinit var session: Session
    protected var info: DeviceInfo? = null
    @JvmField var rand: Random = Random()
    @JvmField var mConnection: UsbDeviceConnection? = null

    protected open var OBJECT_ADDED_EVENT_CODE: Int = Event.ObjectAdded

    protected var fileDownloadedListenerList: MutableList<FileDownloadedListener> = mutableListOf()
    protected var fileTransferListenerList: MutableList<FileTransferListener> = mutableListOf()

    @JvmField var autoDownloadFile: Boolean = true

    @Volatile
    @JvmField var autoPollEvent: Boolean = true

    @JvmField var fileDownloadPath: String? = null

    @JvmField var pollingThread: Thread? = null

    @JvmField var syncTriggerMode: Int = SyncParams.SYNC_TRIGGER_MODE_EVENT
    @JvmField var syncMode: Int = SyncParams.SYNC_MODE_SYNC_NEW_ADDED
    @JvmField var syncRecordMode: Int = SyncParams.SYNC_RECORD_MODE_REMEMBER

    @JvmField var getObjectHandleFilterParam: Int = 0

    @Volatile
    @JvmField var pollThreadRunning: Boolean = false

    @JvmField var fileNameRule: Int = SyncParams.FILE_NAME_RULE_HANDLE_ID

    protected constructor() {
    }

    /**
     * Constructs a class driver object, if the device supports
     * operations according to Annex D of the PTP specification.
     *
     * @param dev the first PTP interface will be used
     * @throws IllegalArgumentException if the device has no
     *                                  Digital Still Imaging Class or PTP interfaces
     */
    @Throws(PTPException::class)
    constructor(dev: UsbDevice, connection: UsbDeviceConnection) : this() {
        if (connection == null) {
            throw PTPException("Connection = null")
        }
        this.mConnection = connection
        if (dev == null) {
            throw PTPException("dev = null")
        }
        session = Session()
        session.setFactory(this as NameFactory)
        this.device = dev
        intf = findUsbInterface(dev)

        if (intf == null) {
            throw PTPException("No PTP interfaces associated to the device")
        }

        for (i in 0 until intf!!.endpointCount) {
            val ep = intf!!.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    epOut = ep
                } else {
                    epIn = ep
                }
            }
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                epEv = ep
            }
        }
        endpointSanityCheck()
        inMaxPS = epIn?.maxPacketSize ?: 0
        intrMaxPS = epEv?.maxPacketSize ?: 0

        reset()
        if (getClearStatus() != Response.OK
            && getDeviceStatus(null) != Response.OK) {
            throw PTPException("can't init")
        }

        Timber.d("trying getDeviceInfoUncached")
        info = getDeviceInfoUncached()

        if (info!!.vendorExtensionId != 0) {
            info!!.factory = updateFactory(info!!.vendorExtensionId)
        }
    }

    protected fun findUsbInterface(device: UsbDevice): UsbInterface? {
        val count = device.interfaceCount
        for (i in 0 until count) {
            val intf = device.getInterface(i)
            Timber.d("Interface %d Class %d Prot %d", i, intf.interfaceClass, intf.interfaceProtocol)
            if (intf.interfaceClass == 6
            ) {
                return intf
            }
        }
        return null
    }

    fun getDevice(): UsbDevice? {
        return device
    }

    @Throws(PTPException::class)
    fun getDeviceInfo(): DeviceInfo {
        if (info == null) {
            return getDeviceInfoUncached()
        }
        return info!!
    }

    @Throws(PTPException::class)
    fun reset() {
        if (mConnection == null) throw PTPException("No Connection")

        mConnection!!.controlTransfer(
            (UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS).toInt(),
            CLASS_DEVICE_RESET.toInt(),
            0,
            0,
            ByteArray(0),
            0,
            DEFAULT_TIMEOUT
        )

        session.close()
    }

    @Throws(PTPException::class)
    open fun openSession() {
        var command: Command
        var response: Response

        synchronized(session) {
            command = Command(Command.OpenSession, session,
                session.nextSessionID)
            response = transactUnsync(command, null)
            when (response.getCode()) {
                Response.OK -> {
                    session.open()
                    pollingThread = Thread(this)
                    pollingThread!!.start()
                    return
                }
                Response.SessionAlreadyOpen -> {
                    closeSession()
                    session = Session()
                    command = Command(Command.OpenSession, session,
                        session.nextSessionID)
                    response = transactUnsync(command, null)
                    if (response.getCode() == Response.OK) {
                        session.open()
                        pollingThread = Thread(this)
                        pollingThread!!.start()
                    }
                }
            }
            throw PTPOpenSessionException(response.toString(), response.getCode())
        }
    }

    @Throws(PTPException::class)
    open fun closeSession() {
        var response: Response

        synchronized(session) {
            response = transact0(Command.CloseSession, null)
            when (response.getCode()) {
                Response.SessionNotOpen -> {
                    if (DEBUG) {
                        System.err.println("close unopen session?")
                    }
                }
                Response.OK -> {
                }
                else -> throw PTPException(response.toString())
            }
            session.close()
            return
        }
    }

    @Throws(PTPException::class)
    open fun close() {
        pollThreadRunning = false
        if (pollingThread != null) {
            pollingThread!!.interrupt()
            pollingThread = null
        }

        if (isSessionActive()) {
            try {
                closeSession()
            } catch (ignore: PTPException) {
            } catch (ignore: IllegalArgumentException) {
            }
        }

        try {
            if (mConnection != null && intf != null) mConnection!!.releaseInterface(intf!!)
            if (mConnection != null) mConnection!!.close()
            device = null
            info = null
        } catch (ignore: Exception) {
            throw PTPException("Unable to close the USB device")
        }
    }

    fun showResponseCode(comment: String, code: Int) {
        Timber.d("%s Response: %s,  code: 0x%s", comment, Response._getResponseString(code), Integer.toHexString(code))
    }

    fun isSessionActive(): Boolean {
        synchronized(session) {
            return session.isActive()
        }
    }

    @Throws(PTPException::class)
    protected fun transact0(code: Int, data: Data?): Response {
        synchronized(session) {
            val command = Command(code, session)
            return transactUnsync(command, data)
        }
    }

    @Throws(PTPException::class)
    protected fun transact1(code: Int, data: Data?, p1: Int): Response {
        synchronized(session) {
            val command = Command(code, session, p1)
            return transactUnsync(command, data)
        }
    }

    @Throws(PTPException::class)
    protected fun transact2(code: Int, data: Data?, p1: Int, p2: Int): Response {
        synchronized(session) {
            val command = Command(code, session, p1, p2)
            return transactUnsync(command, data)
        }
    }

    @Throws(PTPException::class)
    protected fun transact3(code: Int, data: Data?, p1: Int, p2: Int, p3: Int): Response {
        synchronized(session) {
            val command = Command(code, session, p1, p2, p3)
            return transactUnsync(command, data)
        }
    }

    @Throws(PTPException::class)
    fun getClearStatus(): Int {
        val buf = Buffer(null, 0)
        var retval = getDeviceStatus(buf)

        if (buf.length != 4) {
            while ((buf.offset + 4) <= buf.length) {
                val ep = buf.nextS32()
                if (epIn!!.address == ep) {
                    if (TRACE) {
                        System.err.println("clearHalt epIn")
                    }
                    clearHalt(epIn!!)
                } else if (epOut!!.address == ep) {
                    if (TRACE) {
                        System.err.println("clearHalt epOut")
                    }
                    clearHalt(epOut!!)
                } else {
                    if (DEBUG || TRACE) {
                        System.err.println("?? halted EP: $ep")
                    }
                }
            }

            var status = Response.Undefined

            for (i in 0 until 10) {
                try {
                    status = getDeviceStatus(null)
                } catch (x: PTPException) {
                    if (DEBUG) {
                        Timber.w(x, "getClearStatus failed")
                    }
                }
                if (status == Response.OK) {
                    break
                }
                if (TRACE) {
                    System.err.println("sleep; status = " + getResponseString(status))
                }

                try {
                    Thread.sleep(1000)
                } catch (x: InterruptedException) {
                }
            }
            if (status != Response.OK) {
                retval = -1
            }
        } else {
            if (TRACE) {
                System.err.println("no endpoints halted")
            }
        }
        return retval
    }

    @Throws(PTPException::class)
    protected fun getDeviceStatus(bufParam: Buffer?): Int {
        if (mConnection == null) throw PTPException("No Connection")

        val data = ByteArray(33)

        mConnection!!.controlTransfer(
            (UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS).toInt(),
            CLASS_GET_DEVICE_STATUS.toInt(),
            0,
            0,
            data,
            data.size,
            DEFAULT_TIMEOUT
        )

        val buf: Buffer
        if (bufParam == null) {
            buf = Buffer(data)
        } else {
            buf = bufParam
            buf.data = data
        }
        buf.offset = 4
        buf.length = buf.getU16(0)
        if (buf.length != buf.data.size) {
        }

        return buf.getU16(2)
    }

    @Throws(PTPException::class)
    protected fun getDeviceInfoUncached(): DeviceInfo {
        val data = DeviceInfo(this)
        var response: Response

        synchronized(session) {
            val command: Command
            command = Command(Command.GetDeviceInfo, session)
            response = transactUnsync(command, data)
        }

        when (response.getCode()) {
            Response.OK -> {
                info = data
                return data
            }
            else -> throw PTPException(response.toString())
        }
    }

    @Throws(PTPException::class)
    fun transactUnsync(command: Command, data: Data?): Response {
        if ("command" != Container.getBlockTypeName(command.blockType)) {
            throw IllegalArgumentException(command.toString())
        }

        val opcode = command.getCode()

        if (session.isActive()) {
            if (Command.OpenSession == opcode) {
                throw IllegalStateException("session already open")
            }
        } else {
            if (Command.GetDeviceInfo != opcode
                && Command.OpenSession != opcode) {
                throw IllegalStateException("no session")
            }
        }

        if (info != null && !info!!.supportsOperation(opcode)) {
            throw UnsupportedOperationException(command.getCodeName(opcode))
        }

        var response: Response
        var abort = true

        if (TRACE) {
            System.err.println(command.toString())
        }
        var lenC = mConnection!!.bulkTransfer(epOut!!, command.data, command.length, DEFAULT_TIMEOUT)
        Timber.d("Command %s bytes sent %d", Command._getOpcodeString(command.getCode()), lenC)

        if ((command.length % epOut!!.maxPacketSize) == 0) {
            lenC = mConnection!!.bulkTransfer(epOut!!, command.data, 0, DEFAULT_TIMEOUT)
        }

        if (data != null) {

            if (!data.isIn()) {

                data.offset = 0
                data.putHeader(data.getLength(), 2,
                    opcode,
                    command.getXID())

                if (TRACE) {
                    System.err.println(data.toString())
                }

                val bytes = data.data
                var len = mConnection!!.bulkTransfer(epOut!!, bytes, bytes.size, DEFAULT_TIMEOUT)
                if (len < 0) {
                    throw PTPException("short: $len")
                }

                if ((data.length % epOut!!.maxPacketSize) == 0) {
                    mConnection!!.bulkTransfer(epOut!!, bytes, 0, DEFAULT_TIMEOUT)
                }

            } else {
                val readBuffer = ByteArray(inMaxPS)
                var readLen = 0
                readLen = mConnection!!.bulkTransfer(epIn!!, readBuffer, inMaxPS,
                    DEFAULT_TIMEOUT)
                if (readLen == 0) {
                    Timber.d("rainx note: 有的时候，端点会返回空包，这个时候需要再次发送请求 ")
                    readLen = mConnection!!.bulkTransfer(epIn!!, readBuffer, inMaxPS, DEFAULT_TIMEOUT)
                }
                data.data = readBuffer
                data.length = readLen
                if ("data" != Container.getBlockTypeName(data.blockType)
                    || data.getCode() != command.getCode()
                    || data.getXID() != command.getXID()) {
                    if (data.getLength() == 0) {
                        readLen = mConnection!!.bulkTransfer(epIn!!, readBuffer, inMaxPS,
                            DEFAULT_TIMEOUT)
                        data.data = readBuffer
                        data.length = readLen

                        Timber.d("read a unkonwn pack , read again:%s", byteArrayToHex(data.data))
                    }
                    throw PTPException("protocol err 1, " + data +
                            "\n data:" + byteArrayToHex(data.data))
                }

                val totalLen = data.getLength()
                if (totalLen > readLen) {
                    val dataStream = ByteArrayOutputStream(
                        totalLen)

                    dataStream.write(readBuffer, 0, readLen)

                    var remaining = totalLen - readLen
                    while (remaining > 0) {
                        val toRead = if (remaining > inMaxPS) inMaxPS else remaining
                        readLen = mConnection!!.bulkTransfer(epIn!!, readBuffer, toRead,
                            DEFAULT_TIMEOUT)
                        dataStream.write(readBuffer, 0, readLen)
                        remaining -= readLen
                    }

                    data.data = dataStream.toByteArray()
                    data.length = data.length
                }
                data.parse()
            }
        }

        val buf = ByteArray(inMaxPS)
        Timber.d("read response")
        var len = mConnection!!.bulkTransfer(epIn!!, buf, inMaxPS, DEFAULT_TIMEOUT)
        Timber.d("received data bytes: $len")

        if (len == 0) {
            len = mConnection!!.bulkTransfer(epIn!!, buf, inMaxPS, DEFAULT_TIMEOUT)
        }

        response = Response(buf, len, this)
        if (TRACE) {
            System.err.println(response.toString())
        }

        abort = false
        return response
    }

    @Throws(PTPException::class)
    protected fun endpointSanityCheck() {
        if (epIn == null) {
            throw PTPException("No input end-point found!")
        }

        if (epOut == null) {
            throw PTPException("No output end-point found!")
        }

        if (epEv == null) {
            throw PTPException("No input interrupt end-point found!")
        }
        if (DEBUG) {
            Timber.d("Get: %d Other: %s", device!!.interfaceCount, device!!.deviceName)
            Timber.d(
                "\nClass: %d,%d,%d\nIendpoints: %d Type %d Dir %d",
                intf!!.interfaceClass, intf!!.interfaceSubclass, intf!!.interfaceProtocol,
                epIn!!.maxPacketSize, epIn!!.type, epIn!!.direction
            )
            Timber.d("\nOendpoints: %d Type %d Dir %d", epOut!!.maxPacketSize, epOut!!.type, epOut!!.direction)
            Timber.d("\nEendpoints: %d Type %d Dir %d", epEv!!.maxPacketSize, epEv!!.type, epEv!!.direction)
        }
    }

    private fun clearHalt(e: UsbEndpoint) {
    }

    fun writeExtraData(command: Command, data: Data, timeout: Int) {
        var lenC = mConnection!!.bulkTransfer(epOut!!, command.data, command.length, timeout)

        if ((command.length % epOut!!.maxPacketSize) == 0) {
            lenC = mConnection!!.bulkTransfer(epOut!!, command.data, 0, timeout)
        }
        val opcode = command.getCode()
        data.offset = 0
        data.putHeader(data.getLength(), 2, opcode, command.getXID())
        val bytes = data.data

        mConnection!!.bulkTransfer(epOut!!, data.data, data.length, timeout)

        if ((data.length % epOut!!.maxPacketSize) == 0) {
            mConnection!!.bulkTransfer(epOut!!, bytes, 0, timeout)
        }
    }

    @Throws(PTPException::class)
    open fun initiateCapture(storageId: Int, formatCode: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun startBulb(): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun stopBulb(): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setShutterSpeed(speed: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setExposure(exposure: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setISO(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setAperture(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setImageQuality(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setShutterSpeed(timeSeconds: Double): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setAperture(apertureValue: Double): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setISO(isoValue: Double): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setExposure(exposureValue: Double): Response? {
        Timber.d("Not overriden!!!")
        return null
    }

    @Throws(PTPException::class)
    open fun setImageQuality(quality: String): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setDevicePropValueEx(x: Int, y: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun MoveFocus(x: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setPictureStyle(x: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setWhiteBalance(x: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setMetering(x: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun setDriveMode(x: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    open fun getPropValue(value: Int): DevicePropDesc? {
        return null
    }

    @Throws(PTPException::class)
    open fun setupLiveview() {
    }

    open fun getLiveView(x: ImageView) {
    }

    fun read(timeout: Int): ByteArray {
        Timber.d("Reading data")
        val data = ByteArray(inMaxPS)

        var retries = 10
        var tmp = -1
        var i = 0
        while (i < retries && isSessionActive() && pollThreadRunning) {
            tmp = mConnection!!.bulkTransfer(epIn!!, data, inMaxPS, timeout)
            if (tmp < 0)
                Timber.e("Reading failed, retry")
            else
                break
            retries--
        }

        return data
    }

    fun write(data: ByteArray, length: Int, timeout: Int) {
        Timber.d("Sending command")
        mConnection!!.bulkTransfer(epOut!!, data, length, timeout)
    }

    open fun setFocusPos(x: Int, y: Int) {
    }

    open fun setZoom(zoomLevel: Int) {
    }

    open fun doAutoFocus() {
    }

    fun readInter(timeout: Int, data: ByteArray): Int {
        Timber.d("Reading interrupt data")

        var retries = 10
        var length = -1
        var i = 0
        while (i < retries) {
            length = mConnection!!.bulkTransfer(epEv!!, data, intrMaxPS, timeout)
            if (length < 0)
                Timber.e("Reading failed, retry")
            else
                break
            retries--
        }

        return length
    }

    @Throws(PTPException::class, IOException::class)
    fun importFile(objectHandle: Int, destPath: String): Boolean {

        val outputFile = File(destPath)

        val parentDir = outputFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            val created = parentDir.mkdirs()
            if (!created) {
                throw PTPException("Failed to create parent directory: " + parentDir.absolutePath)
            }
        }

        val fos: FileOutputStream
        try {
            fos = FileOutputStream(outputFile)
        } catch (e: FileNotFoundException) {
            throw PTPException("can not import file since the destPath is hit FileNotFoundException: " + destPath, e)
        }

        val outputStream = BufferedOutputStream(fos, 64 * 1024)

        synchronized(session) {
            val startDownloadAt = System.currentTimeMillis()
            val command = Command(Command.GetObject, session, objectHandle)
            if (!session.isActive())
                throw IllegalStateException("no session")

            if (info != null && !info!!.supportsOperation(Command.GetObject)) {
                throw UnsupportedOperationException(command.getCodeName(Command.GetObject))
            }

            var response: Response
            var abort = true
            var lenC = mConnection!!.bulkTransfer(epOut!!, command.data, command.length, DEFAULT_TIMEOUT)

            if ((command.length % epOut!!.maxPacketSize) == 0) {
                lenC = mConnection!!.bulkTransfer(epOut!!, command.data, 0, DEFAULT_TIMEOUT)
            }
            val READ_BUF_SIZE = Math.max(64 * 1024, if (inMaxPS > 0) inMaxPS else 64 * 1024)
            val readBuffer = ensureImportBuffer(READ_BUF_SIZE)
            var readLen = 0
            val READ_TIMEOUT_MS = Math.max(DEFAULT_TIMEOUT, 15000)
            readLen = mConnection!!.bulkTransfer(epIn!!, readBuffer, READ_BUF_SIZE, READ_TIMEOUT_MS)

            val data = Data(this)
            data.data = readBuffer
            data.length = readLen

            if (data.blockType == Container.BLOCK_TYPE_RESPONSE) {
                response = Response(data.data, this)
                outputStream.close()
                return response.getCode() == Response.OK
            }

            if ("data" != Container.getBlockTypeName(data.blockType)
                || data.getCode() != command.getCode()
                || data.getXID() != command.getXID()) {
                outputStream.close()
                throw PTPException("protocol err 1, " + data)
            }

            val fullLength = data.getLength()

            if (fullLength < Container.HDR_LEN) {
                Timber.tag("ptp-error").v("fullLength is too short: $fullLength")
                outputStream.close()
                return false
            }

            val length = fullLength - Container.HDR_LEN
            var offset = 0
            val initialDataLength = data.length - Container.HDR_LEN

            var totalWritten: Long = 0
            val PROGRESS_STEP = 256 * 1024
            var nextReportAt: Long = PROGRESS_STEP.toLong()

            if (initialDataLength > 0) {
                outputStream.write(data.data, Container.HDR_LEN, initialDataLength)
                offset += initialDataLength
                totalWritten += initialDataLength.toLong()
                if (totalWritten >= nextReportAt || totalWritten >= length) {
                    for (fileTransferListener in fileTransferListenerList) {
                        fileTransferListener.onFileTranster(this, objectHandle, length, totalWritten.toInt())
                    }
                    while (nextReportAt <= totalWritten) nextReportAt += PROGRESS_STEP.toLong()
                }
            }

            var remaining = fullLength - readLen
            while (remaining > 0) {
                val toRead = Math.min(remaining, READ_BUF_SIZE)
                readLen = mConnection!!.bulkTransfer(epIn!!, readBuffer, toRead, READ_TIMEOUT_MS)
                if (readLen <= 0) {
                    val retry = mConnection!!.bulkTransfer(epIn!!, readBuffer, Math.min(remaining, READ_BUF_SIZE), READ_TIMEOUT_MS)
                    if (retry <= 0) {
                        outputStream.close()
                        throw IOException("bulkTransfer read failed or timed out")
                    }
                    readLen = retry
                }
                if (readLen > READ_BUF_SIZE) {
                    Timber.d("readLen $readLen is bigger than buffer size:$READ_BUF_SIZE")
                    readLen = READ_BUF_SIZE
                }
                outputStream.write(readBuffer, 0, readLen)
                remaining -= readLen
                totalWritten += readLen.toLong()

                if (totalWritten >= nextReportAt || remaining == 0) {
                    for (fileTransferListener in fileTransferListenerList) {
                        fileTransferListener.onFileTranster(this, objectHandle, length, totalWritten.toInt())
                    }
                    while (nextReportAt <= totalWritten) nextReportAt += PROGRESS_STEP.toLong()
                }
            }
            outputStream.flush()
            outputStream.close()
            response = readResponse()
            if (response != null && response.getCode() == Response.OK) {
                val downloadDuring = System.currentTimeMillis() - startDownloadAt
                for (fileDownloadedListener in fileDownloadedListenerList) {
                    fileDownloadedListener.onFileDownloaded(this, objectHandle, outputFile, downloadDuring)
                }
                return true
            }
        }
        return false
    }

    private var importReadBuffer: ByteArray? = null

    private fun ensureImportBuffer(minSize: Int): ByteArray {
        if (importReadBuffer == null || importReadBuffer!!.size < minSize) {
            importReadBuffer = ByteArray(minSize)
        }
        return importReadBuffer!!
    }

    fun readResponse(): Response {
        val buf = ByteArray(inMaxPS)
        var len = mConnection!!.bulkTransfer(epIn!!, buf, inMaxPS, DEFAULT_TIMEOUT)

        if (len == 0) {
            len = mConnection!!.bulkTransfer(epIn!!, buf, inMaxPS, DEFAULT_TIMEOUT)
        }

        val response = Response(buf, len, this)
        if (TRACE) {
            System.err.println(response.toString())
        }

        return response
    }

    fun resetFileDownloadedListener() {
        fileDownloadedListenerList.clear()
    }

    fun resetFileTransferListener() {
        fileTransferListenerList.clear()
    }

    fun setFileDownloadedListener(l: FileDownloadedListener) {
        if (!fileDownloadedListenerList.contains(l)) {
            fileDownloadedListenerList.add(l)
        }
    }

    fun setFileTransferListener(l: FileTransferListener) {
        if (!fileTransferListenerList.contains(l)) {
            fileTransferListenerList.add(l)
        }
    }

    @Throws(PTPException::class)
    fun getStorageIds(): IntArray {
        var response: Response
        val data = Data(this)

        synchronized(session) {
            response = transact0(Command.GetStorageIDs, data)
            when (response.getCode()) {
                Response.OK -> {
                    data.parse()
                    return data.nextS32Array()
                }
                else -> throw PTPException(response.toString())
            }
        }
    }

    @Throws(PTPException::class)
    fun getObjectHandles(storageId: Int, format: Int, objectHandle: Int): IntArray {
        var response: Response
        val data = Data(this)

        synchronized(session) {
            response = transact3(Command.GetObjectHandles, data, storageId, format, objectHandle)
            when (response.getCode()) {
                Response.OK -> {
                    data.parse()
                    return data.nextS32Array()
                }
                else -> throw PTPException(response.toString())
            }
        }
    }

    @Throws(PTPException::class)
    fun getObjectInfo(objectHandle: Int): ObjectInfo {
        var response: Response
        val data = ObjectInfo(objectHandle, this)

        synchronized(session) {
            response = transact1(Command.GetObjectInfo, data, objectHandle)
            when (response.getCode()) {
                Response.OK -> {
                    data.parse()
                    return data
                }
                else -> throw PTPException(response.toString())
            }
        }
    }

    @Throws(PTPException::class)
    fun getStorageInfo(storageId: Int): StorageInfo {
        var response: Response
        val data = StorageInfo(this)

        synchronized(session) {
            response = transact1(Command.GetStorageInfo, data, storageId)
            when (response.getCode()) {
                Response.OK -> {
                    data.parse()
                    return data
                }
                else -> throw PTPException(response.toString())
            }
        }
    }

    override fun run() {

        if (syncTriggerMode == SyncParams.SYNC_TRIGGER_MODE_EVENT) {
            runEventPoll()
        } else if (syncTriggerMode == SyncParams.SYNC_TRIGGER_MODE_POLL_LIST) {
            try {
                runPollListPoll()
            } catch (e: PTPException) {
                Timber.w(e, "run failed")
            }
        }
    }

    protected open fun runEventPoll() {
        Timber.tag("PTP_EVENT").v("开始event轮询")
        var loopTimes: Long = 0
        pollEventSetUp()
        if (epEv != null) {
            val buffer = ByteArray(intrMaxPS)
            var length: Int
            while (isSessionActive()) {
                loopTimes++

                val singal = waitVendorSpecifiedFileReadySignal() as? ObjectInfo
                if (singal != null) {

                    val outputFile = getAvailableOutputFile(File(fileDownloadPath!!), getSafeFileName(singal.filename))
                    val outputFilePath = outputFile.path
                    try {
                        importFile(singal.handle, outputFilePath)
                    } catch (e: PTPException) {
                        Timber.e(e, "Failed to import file for handle %d to %s", singal.handle, outputFilePath)
                    } catch (e: IOException) {
                        Timber.e(e, "I/O error while importing file for handle %d to %s", singal.handle, outputFilePath)
                    }
                }
            }
        }
        Timber.tag("PTP_EVENT").v("结束轮询")
    }

    protected open fun waitVendorSpecifiedFileReadySignal(): Any? {
        return null
    }

    protected open fun getObjectAddedEventCode(): Int {
        return OBJECT_ADDED_EVENT_CODE
    }

    @Throws(PTPException::class)
    protected open fun runPollListPoll() {
        pollThreadRunning = true
        val PTP_POLL_LIST = "PTP_POLL_LIST"
        Timber.tag(PTP_POLL_LIST).v("开始event轮询")
        var loopTimes: Long = 0
        var syncDeviceManager: SyncDeviceManager

        pollListSetUp()
        var oldObjectHandles: MutableList<Int>

        var sids: IntArray
        sids = getStorageIds()
        sids = filterValidStorageIds(sids)
        if (sids.isEmpty()) {
            Timber.w("未发现可用存储卡槽，停止轮询")
            pollListTearDown()
            return
        }
        pollListAfterGetStorages(sids)
        if (syncRecordMode == SyncParams.SYNC_RECORD_MODE_REMEMBER) {
            syncDeviceManager = SyncDeviceManager(device!!)
            val syncDevice = syncDeviceManager.updateDeviceInfo()!!
            if (syncDevice.syncAt != null) {
                oldObjectHandles = syncDeviceManager.getIdList().toMutableList()
            } else {
                if (syncMode == SyncParams.SYNC_MODE_SYNC_ALL) {
                    oldObjectHandles = mutableListOf()
                } else {
                    oldObjectHandles = getObjectHandlesByStorageIds(sids).toMutableList()
                }
                if (oldObjectHandles != null) {
                    syncDeviceManager.updateIdList(oldObjectHandles)
                } else {
                    Timber.d("init oldObjectHandles is null")
                }
            }
        } else if (syncRecordMode == SyncParams.SYNC_RECORD_MODE_FORGET) {
            if (syncMode == SyncParams.SYNC_MODE_SYNC_ALL) {
                oldObjectHandles = mutableListOf()
            } else {
                oldObjectHandles = getObjectHandlesByStorageIds(sids).toMutableList()
            }
        } else {
            oldObjectHandles = mutableListOf()
        }

        Timber.tag(PTP_POLL_LIST).v("初始objectHandle列表: $oldObjectHandles")
        while (pollThreadRunning) {
            if (!isSessionActive() || !autoPollEvent || mConnection == null) {
                try {
                    Thread.sleep(DEFAULT_TIMEOUT.toLong())
                } catch (e: InterruptedException) {
                    Timber.w(e, "poll loop interrupted, stopping")
                    return
                }
                continue
            } else {
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    Timber.w(e, "poll loop interrupted, stopping")
                    return
                }

                val newObjectHandles = getObjectHandlesByStorageIds(sids)
                val newAdded = getAllNewAddedObjectHandles(oldObjectHandles, newObjectHandles)
                Timber.tag(PTP_POLL_LIST).v("New Added objectHandle : $newAdded")
                val newAddedDownloaded = mutableListOf<Int>()
                if (newAdded.size > 0) {
                    var downloadInterrupted = false
                    for (h in newAdded) {
                        if (processFileAddEvent(h, null)) {
                            newAddedDownloaded.add(h)
                        } else {
                            downloadInterrupted = true
                            break
                        }
                    }

                    if (!downloadInterrupted) {
                        oldObjectHandles = ArrayList(newObjectHandles)
                    } else {
                        oldObjectHandles.addAll(newAddedDownloaded)
                    }

                    if (syncRecordMode == SyncParams.SYNC_RECORD_MODE_REMEMBER) {
                        syncDeviceManager = SyncDeviceManager(device!!)
                        syncDeviceManager.updateIdList(oldObjectHandles)
                    }
                }
            }
        }

        pollListTearDown()
        Timber.tag(PTP_POLL_LIST).v("结束轮询")
    }

    private fun filterValidStorageIds(sids: IntArray): IntArray {
        if (sids.isEmpty()) {
            return IntArray(0)
        }
        val validSids = mutableListOf<Int>()
        for (sid in sids) {
            try {
                val info = getStorageInfo(sid)
                if (info != null) {
                    validSids.add(sid)
                }
            } catch (e: PTPException) {
                Timber.w(e, "存储卡槽不可用，跳过 sid=%s", sid)
            }
        }
        val result = IntArray(validSids.size)
        for (i in validSids.indices) {
            result[i] = validSids[i]
        }
        return result
    }

    private fun getAllNewAddedObjectHandles(oldHandles: List<Int>, newHandles: List<Int>): List<Int> {
        val newAdded = mutableListOf<Int>()
        for (newHandle in newHandles) {
            if (!oldHandles.contains(newHandle)) {
                newAdded.add(newHandle)
            }
        }
        return newAdded
    }

    @Throws(PTPException::class)
    private fun getObjectHandlesByStorageIds(sids: IntArray): List<Int> {
        val objectHandles: MutableList<Int>
        objectHandles = mutableListOf()
        for (sid in sids) {
            try {
                val oneStorageObjectHandles = getObjectHandles(sid, getObjectHandleFilterParam, 0)
                for (h in oneStorageObjectHandles) {
                    objectHandles.add(h)
                }
            } catch (e: PTPException) {
                Timber.w(e, "读取存储卡槽失败，跳过 sid=%s", sid)
            }
        }
        return objectHandles
    }

    protected open fun pollListSetUp() {
    }

    protected open fun pollListTearDown() {
    }

    protected open fun pollListAfterGetStorages(ids: IntArray) {
    }

    protected open fun pollEventSetUp() {
    }

    protected open fun processFileAddEvent(fileHandle: Int, event: Any?): Boolean {
        Timber.v("start processFileAddEvent : handle -> $fileHandle")
        if (autoDownloadFile && fileDownloadPath != null) {
            try {
                var downloadFileName: String

                var objectInfo: ObjectInfo? = null

                if (event is ObjectInfo) {
                    objectInfo = event
                } else {
                    try {
                        objectInfo = getObjectInfo(fileHandle)
                    } catch (ex: Exception) {
                        Timber.w(ex, "Failed to resolve ObjectInfo for handle %s", fileHandle)
                        return false
                    }
                }

                if (objectInfo != null && objectInfo.associationType == 1) {
                    return true
                }

                if (objectInfo != null && objectInfo.filename != null && objectInfo.filename!!.trim().isNotEmpty()) {
                    downloadFileName = getSafeFileName(objectInfo.filename)
                } else {
                    downloadFileName = getRandomFileName()
                }

                val downloadDir = File(fileDownloadPath!!)
                if (!downloadDir.exists()) {
                    val created = downloadDir.mkdirs()
                    if (!created) {
                        Timber.e("Failed to create download directory: $fileDownloadPath")
                        return false
                    }
                }

                val outputFile = getAvailableOutputFile(downloadDir, downloadFileName)
                val outputFilePath = outputFile.path
                val ok = importFile(fileHandle, outputFilePath)
                if (!ok) {
                    Timber.w("importFile returned false for handle $fileHandle, path: $outputFilePath")
                }
                return ok
            } catch (e: PTPException) {
                Timber.e(e, "PTPException in processFileAddEvent for handle %s", fileHandle)
                return false
            } catch (e: IOException) {
                Timber.e(e, "IOException in processFileAddEvent for handle %s", fileHandle)
                return false
            } catch (e: Exception) {
                Timber.e(e, "Exception in processFileAddEvent for handle %s", fileHandle)
                return false
            }
        }
        return false
    }

    private fun getRandomFileName(): String {
        var downloadFileName: String
        val randId = rand.nextInt()
        val randIdLong: Long = randId.toLong() and -0x100000000L.inv()
        downloadFileName = "tmp_" + randIdLong + ".jpg"
        return downloadFileName
    }

    private fun getAvailableOutputFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) {
            return candidate
        }

        var baseName = fileName
        var extension = ""
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex)
            extension = fileName.substring(dotIndex)
        }

        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, baseName + "_" + suffix + extension)
            suffix++
        }
        return candidate
    }

    private fun getSafeFileName(name: String?): String {
        if (name == null) return getRandomFileName()
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) getRandomFileName() else trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    fun setFileDownloadPath(fileDownloadPath: String?) {
        this.fileDownloadPath = fileDownloadPath
    }

    fun setSyncTriggerMode(syncTriggerMode: Int) {
        this.syncTriggerMode = syncTriggerMode
    }
}
