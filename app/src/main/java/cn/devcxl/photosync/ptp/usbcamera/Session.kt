package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class Session {
    private var sessionId: Int = 0
    private var xid: Int = 0
    private var active: Boolean = false
    internal var factory: NameFactory? = null

    fun setFactory(f: NameFactory) {
        factory = f
    }

    val nextXID: Int get() = if (active) xid++ else 0

    val nextSessionID: Int
        get() {
            if (!active)
                return ++sessionId
            throw IllegalStateException("already active")
        }

    fun isActive(): Boolean = active

    fun open() {
        xid = 1
        active = true
    }

    fun close() {
        active = false
    }

    fun getSessionId(): Int = sessionId
}
