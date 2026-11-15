package cn.devcxl.photosync.ptp.usbcamera

/**
 * Object handles that appeared on the camera since the last reconciliation.
 *
 * @param handled Handles the app already imported.
 * @param onCamera Handles the camera reports right now.
 */
internal fun newObjectHandles(handled: List<Int>, onCamera: List<Int>): List<Int> {
    val known = handled.toHashSet()
    return onCamera.filter { it !in known }
}

/**
 * The handled set for the next tick, in camera order.
 *
 * Starts from what was already handled plus what this tick imported, then keeps only the
 * handles the camera still reports. Dropping vanished handles matters twice: a deleted
 * photo must not stay "imported", and handles are reused — if the deleted handle stayed in
 * the set, the next photo that lands on it would be treated as already imported and would
 * never sync.
 *
 * A handle whose download failed is not in [imported], so it stays out of the result and
 * is retried on the next tick.
 *
 * @param handled Handles imported before this tick.
 * @param onCamera Handles the camera reports right now.
 * @param imported Handles successfully imported during this tick.
 */
internal fun nextHandledHandles(
    handled: List<Int>,
    onCamera: List<Int>,
    imported: List<Int>
): List<Int> {
    val known = handled.toHashSet()
    known.addAll(imported)
    return onCamera.filter { it in known }
}
