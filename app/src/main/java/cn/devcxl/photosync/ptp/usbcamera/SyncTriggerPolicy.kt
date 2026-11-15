package cn.devcxl.photosync.ptp.usbcamera

import cn.devcxl.photosync.ptp.params.SyncParams

/**
 * How a session notices objects the camera just added.
 */
internal enum class SyncTriggerDecision {
    /** Poll the vendor event channel; the camera reports a photo as soon as it is shot. */
    EVENT,

    /** Poll the object list on a fixed interval. */
    POLL,
}

/**
 * Picks the trigger channel from the operations the camera advertises.
 *
 * The camera's own operation table is the only input on purpose: it is already parsed when
 * the session opens, so the decision costs no transaction and cannot fail. It is also why
 * there is no brand check here — a camera that advertises an event-channel operation gets
 * the event loop whatever its vendor, and a new model of a known brand that does not (a
 * Nikon 1 whose `0x90C7` is disabled, say) gets the poll loop without touching this code.
 *
 * @param vendorEventOpcodes Operations this camera family reads its event channel with;
 *   empty for a camera without an event channel.
 * @param operationsSupported Operations the connected camera reports in its device info.
 * @return [SyncTriggerDecision.EVENT] when one of [vendorEventOpcodes] is advertised,
 *   [SyncTriggerDecision.POLL] otherwise.
 */
internal fun syncTriggerDecision(
    vendorEventOpcodes: Set<Int>,
    operationsSupported: Set<Int>,
): SyncTriggerDecision {
    return if (vendorEventOpcodes.any { it in operationsSupported }) {
        SyncTriggerDecision.EVENT
    } else {
        SyncTriggerDecision.POLL
    }
}

/**
 * The legacy trigger-mode constant a decision maps to.
 *
 * [BaselineInitiator.syncTriggerMode] predates the decision and is still what the loops
 * read, so the decision has to be expressed in its terms once, here.
 */
internal fun SyncTriggerDecision.toSyncTriggerMode(): Int = when (this) {
    SyncTriggerDecision.EVENT -> SyncParams.SYNC_TRIGGER_MODE_EVENT
    SyncTriggerDecision.POLL -> SyncParams.SYNC_TRIGGER_MODE_POLL_LIST
}
