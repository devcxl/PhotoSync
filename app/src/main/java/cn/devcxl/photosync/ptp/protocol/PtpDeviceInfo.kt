package cn.devcxl.photosync.ptp.protocol

/**
 * The `DeviceInfo` dataset a camera returns for `GetDeviceInfo` (PIMA 15740 section 5.1.1).
 *
 * The operation tables are the camera's own declaration of what it implements; the sync
 * path decides from them rather than from the vendor id, because the same model can ship
 * firmware with a different feature set.
 *
 * Tables and strings with no consumer are parsed past rather than stored. Adding one back
 * is a field, not a redesign.
 */
internal class PtpDeviceInfo private constructor(
    val vendorExtensionId: Int,
    val operationsSupported: Set<Int>,
    val eventsSupported: Set<Int>,
    val manufacturer: String?,
    val model: String?,
    val deviceVersion: String?,
) {
    fun supportsOperation(opcode: Int): Boolean = opcode in operationsSupported

    override fun toString(): String =
        "DeviceInfo(manufacturer=$manufacturer, model=$model, version=$deviceVersion)"

    companion object {
        fun parse(bytes: ByteArray): PtpDeviceInfo {
            val reader = PtpByteReader(bytes)
            reader.u16()                       // standard version
            val vendorExtensionId = reader.u32()
            reader.u16()                       // vendor extension version
            reader.string()                    // vendor extension description
            reader.u16()                       // functional mode
            val operations = reader.u16Array()
            val events = reader.u16Array()
            reader.u16Array()                  // properties supported: no consumer
            reader.u16Array()                  // capture formats: no consumer
            reader.u16Array()                  // image formats: no consumer
            return PtpDeviceInfo(
                vendorExtensionId = vendorExtensionId,
                operationsSupported = operations.toSet(),
                eventsSupported = events.toSet(),
                manufacturer = reader.string(),
                model = reader.string(),
                deviceVersion = reader.string(),
            )
        }
    }
}
