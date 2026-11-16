package cn.devcxl.photosync.ptp.usbcamera

import cn.devcxl.photosync.ptp.params.SyncParams

/**
 * Consecutive event-channel failures after which a session stops using it.
 *
 * Three failures is well above the single USB hiccup a busy camera produces, and small
 * enough that a channel that is consistently broken falls back instead of retrying for
 * the rest of the session.
 */
private const val EVENT_FAILURE_DEGRADE_THRESHOLD = 3

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

/**
 * Whether a session should give up the vendor event channel and poll the object list.
 *
 * The signal is repeated failure, never silence: an idle camera sends no events, and
 * vendor channels legitimately drop some, so "nothing for a while" would degrade sessions
 * that are working. A single failed poll is only counted and retried.
 *
 * @param consecutiveFailures How many polls of the event channel failed in a row; a poll
 *   that returns, even with no events, resets the count.
 * @return true once the failures reach [EVENT_FAILURE_DEGRADE_THRESHOLD].
 */
internal fun shouldDegradeToPolling(consecutiveFailures: Int): Boolean =
    consecutiveFailures >= EVENT_FAILURE_DEGRADE_THRESHOLD

/**
 * Why a session is considering a full object-list pass.
 *
 * The event channel only reports objects added while the session is open, so the app has
 * to read the object list back at the moments where its picture of the camera can be
 * wrong. These are exactly the moments the spec names; nothing else asks for a pass.
 */
internal enum class ReconcileCause {
    /** A session just opened and recorded its handle baseline. */
    SESSION_START,

    /** The camera said it may have been unable to report events (`UnreportedStatus`). */
    UNREPORTED_EVENTS,

    /** The camera was unplugged and plugged back in; it opens a fresh session. */
    RECONNECT,

    /** A fixed interval elapsed while the session stayed open. */
    PERIODIC_TICK,
}

/**
 * Whether a session should run one full object-list pass for [cause].
 *
 * Only a periodic tick is refused. The other three are moments where the app can be
 * missing photos, and each costs one enumeration.
 *
 * A timer is refused because the pass shares the one USB session the event loop polls
 * every 200 ms with: a fixed cadence of its own would interrupt event delivery for a
 * benefit the spec does not need. "Photos did not arrive" is observable by the user, and
 * reconnecting is the recovery the spec accepts, so there is no periodic fallback to add.
 *
 * A reconnect opens a new session and therefore enters through
 * [ReconcileCause.SESSION_START]; the cause is listed separately so the rule states every
 * input the spec names.
 *
 * @param cause what made the session consider a pass.
 * @return true for a session start, an unreported-events signal and a reconnect; false
 *   for a periodic tick.
 */
internal fun shouldReconcile(cause: ReconcileCause): Boolean = when (cause) {
    ReconcileCause.SESSION_START,
    ReconcileCause.UNREPORTED_EVENTS,
    ReconcileCause.RECONNECT,
    -> true

    ReconcileCause.PERIODIC_TICK -> false
}

/** Fast poll used while the camera is reporting activity. */
internal const val EVENT_POLL_FAST_MS = 200L

/** Slowest idle poll; polling faster than this forever is what drains a session. */
internal const val EVENT_POLL_IDLE_MS = 3000L

/**
 * How long to wait before the next event poll.
 *
 * A quiet camera is polled at a growing interval, so an idle session stops hammering the
 * USB link. Any event snaps the interval back to the fast one: a burst of activity — and a
 * shutter press always produces one — is followed at full speed, while a camera left on the
 * table costs one transaction every few seconds instead of five a second.
 *
 * @param currentIntervalMs The interval used for the poll that just finished.
 * @param eventsLastPoll How many events that poll returned.
 */
internal fun nextEventPollIntervalMs(currentIntervalMs: Long, eventsLastPoll: Int): Long {
    if (eventsLastPoll > 0) {
        return EVENT_POLL_FAST_MS
    }
    return (currentIntervalMs * 2).coerceAtMost(EVENT_POLL_IDLE_MS)
}
