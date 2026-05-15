package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class Data : Container {
    internal var `in`: Boolean = true

    constructor(f: NameFactory) : super(ByteArray(0), 0, f) {
        `in` = true
    }

    constructor(isIn: Boolean, buf: ByteArray, f: NameFactory) : super(buf, buf.size, f) {
        `in` = isIn
    }

    constructor(isIn: Boolean, buf: ByteArray, len: Int, f: NameFactory) : super(buf, len, f) {
        `in` = isIn
    }

    fun isIn(): Boolean = `in`

    override fun getCodeName(code: Int): String = factory.getOpcodeString(code)

    override fun toString(): String {
        val temp = StringBuilder()
        val code = getCode()
        temp.append("{ ")
        temp.append(Container.getBlockTypeName(blockType))
        if (`in`)
            temp.append(" IN")
        else
            temp.append(" OUT")
        temp.append("; len ")
        temp.append(getLength().toString())
        temp.append("; ")
        temp.append(factory.getOpcodeString(code))
        temp.append("; xid ")
        temp.append(getXID().toString())
        temp.append("}")
        return temp.toString()
    }
}
