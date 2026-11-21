package cn.devcxl.photosync.ptp.camera.vendor

import cn.devcxl.photosync.ptp.camera.PtpSession
import cn.devcxl.photosync.ptp.protocol.PtpEvent
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException

/**
 * One thing a camera told the sync loop about, reduced to what the loop acts on.
 *
 * @property code the event code the camera sent
 * @property newObjectHandle the object to import, or null when the event is not an
 *   "object added" announcement
 * @property description log line describing the event; built eagerly so a malformed event
 *   cannot throw later, on a different thread than the one that received it
 * @property reconcileRequested true when the camera warns it may have dropped events
 *   ([cn.devcxl.photosync.ptp.protocol.PtpEvent.UNREPORTED_STATUS])
 */
internal data class CameraEvent(
    val code: Int,
    val newObjectHandle: Int?,
    val description: String,
    val reconcileRequested: Boolean = false,
)

/**
 * A camera's vendor event channel: a vendor operation the host polls to learn about new
 * objects without enumerating the whole card.
 *
 * Implementations are called with the session locked and may throw
 * [cn.devcxl.photosync.ptp.protocol.PtpException]; the sync loop counts consecutive
 * failures and falls back to object-list polling, so a channel must report failure rather
 * than pretend the camera was quiet.
 */
internal interface CameraEventChannel {
    /** Human-readable channel name, for the loop's begin and end log lines. */
    val description: String

    /** Runs once per session, before the first [poll]. */
    fun prepare() = Unit

    /** Reads one batch of events. Returns an empty list when nothing happened. */
    fun poll(): List<CameraEvent>
}

/**
 * Reads the length-prefixed event records Canon EOS returns for `EosGetEvent`.
 *
 * Unlike the standard event container, this payload is a sequence of records, each
 * prefixed with its own length and code. Reading each record's payload as a slice first is
 * what keeps a variable-length or unknown record from desynchronizing the records after it.
 */
internal object CanonEosEventReader {

    private const val RECORD_HEADER_BYTES = 8
    private const val NOTHING_HAPPENED = 0x0000
    private const val OBJECT_ADDED_EX = 0xc181
    private const val OBJECT_ADDED_EX64 = 0xc1a7
    private const val MAX_RECORD_BYTES = 1024 * 1024

    fun read(payload: ByteArray): List<CameraEvent> {
        val events = mutableListOf<CameraEvent>()
        var offset = 0
        while (offset + RECORD_HEADER_BYTES <= payload.size) {
            val length = readU32(payload, offset)
            val code = readU32(payload, offset + 4)
            if (length < RECORD_HEADER_BYTES || length > MAX_RECORD_BYTES) {
                throw PtpProtocolException("EOS event record declares $length bytes")
            }
            if (offset + length > payload.size) {
                // A truncated trailing record is the camera's problem; the batch ends here.
                break
            }
            offset += RECORD_HEADER_BYTES
            if (code != NOTHING_HAPPENED) {
                events += decode(code, payload, offset, length - RECORD_HEADER_BYTES)
            }
            offset += length - RECORD_HEADER_BYTES
        }
        return events
    }

    private fun decode(code: Int, payload: ByteArray, offset: Int, length: Int): CameraEvent {
        if (code == PtpEvent.UNREPORTED_STATUS) {
            return CameraEvent(
                code = code,
                newObjectHandle = null,
                description = "UnreportedStatus",
                reconcileRequested = true,
            )
        }
        val handle = if (length >= 4) readU32(payload, offset) else null
        return when (code) {
            OBJECT_ADDED_EX, OBJECT_ADDED_EX64 -> CameraEvent(
                code = code,
                newObjectHandle = handle,
                description = "ObjectAddedEx(handle=0x%08x)".format(handle),
            )
            else -> CameraEvent(
                code = code,
                newObjectHandle = null,
                description = "EOS event 0x%04x (%d bytes)".format(code, length),
            )
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}

/**
 * Reads the Nikon `CheckEvent` payload: a 16-bit event count followed by fixed six-byte
 * records of `{uint16 code, uint32 parameter}`.
 *
 * A record the payload cuts short is ignored rather than treated as an error: the rest of
 * the batch is still meaningful, and the next poll starts from a fresh payload.
 */
internal object NikonEventReader {

    private const val COUNT_BYTES = 2
    private const val RECORD_BYTES = 6
    private const val OBJECT_ADDED_IN_SDRAM = 0xc101

    /** Handle Nikon uses for an in-camera capture whose real handle is not allocated yet. */
    const val SDRAM_PLACEHOLDER_HANDLE: Int = 0xffff0001.toInt()

    fun read(payload: ByteArray): List<CameraEvent> {
        if (payload.size < COUNT_BYTES) {
            return emptyList()
        }
        val declared = ((payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8))
        val available = (payload.size - COUNT_BYTES) / RECORD_BYTES
        val count = minOf(declared, available)
        val events = ArrayList<CameraEvent>(count)
        var offset = COUNT_BYTES
        repeat(count) {
            val code = (payload[offset].toInt() and 0xff) or
                ((payload[offset + 1].toInt() and 0xff) shl 8)
            val parameter = (payload[offset + 2].toInt() and 0xff) or
                ((payload[offset + 3].toInt() and 0xff) shl 8) or
                ((payload[offset + 4].toInt() and 0xff) shl 16) or
                ((payload[offset + 5].toInt() and 0xff) shl 24)
            offset += RECORD_BYTES
            events += when (code) {
                NikonProfile.OBJECT_ADDED_STANDARD -> CameraEvent(
                    code = code,
                    newObjectHandle = parameter,
                    description = "ObjectAdded(handle=0x%08x)".format(parameter),
                )
                OBJECT_ADDED_IN_SDRAM -> CameraEvent(
                    code = code,
                    newObjectHandle = if (parameter == 0) SDRAM_PLACEHOLDER_HANDLE else parameter,
                    description = "ObjectAddedInSDRAM(handle=0x%08x)".format(parameter),
                )
                PtpEvent.UNREPORTED_STATUS -> CameraEvent(
                    code = code,
                    newObjectHandle = null,
                    description = "UnreportedStatus",
                    reconcileRequested = true,
                )
                else -> CameraEvent(
                    code = code,
                    newObjectHandle = null,
                    description = "Nikon event 0x%04x (param 0x%08x)".format(code, parameter),
                )
            }
        }
        return events
    }
}
