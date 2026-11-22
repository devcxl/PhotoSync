package cn.devcxl.photosync.editor.domain

/**
 * Bounded undo/redo history of liquify displacement grids.
 *
 * Snapshots are stored as half-float grids (`64 KiB` each) rather than float copies, so the full
 * 50-step ring costs exactly `3.125 MiB` regardless of how many strokes were drawn. A stroke only
 * pushes one snapshot — the mesh state before the stroke started — which is why a long brush session
 * never grows the history faster than one step per stroke.
 */
internal class LiquifyHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val undoStack = ArrayDeque<ShortArray>()
    private val redoStack = ArrayDeque<ShortArray>()

    /** Number of undoable steps currently held. */
    val undoDepth: Int
        get() = undoStack.size

    /** Number of redoable steps currently held. */
    val redoDepth: Int
        get() = redoStack.size

    /** True when [undo] can restore a previous grid. */
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    /** True when [redo] can re-apply an undone grid. */
    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    /**
     * Pushes [mesh] as the state to return to, dropping the oldest snapshot once the ring is full
     * and discarding any redo branch.
     */
    fun record(mesh: LiquifyMesh) {
        undoStack.addLast(mesh.toHalfFloatBits())
        while (undoStack.size > capacity) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    /**
     * Reverts one step.
     *
     * @param current The live mesh, snapshotted onto the redo stack.
     * @return The restored mesh, or `null` when there is nothing to undo.
     */
    fun undo(current: LiquifyMesh): LiquifyMesh? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current.toHalfFloatBits())
        return LiquifyMesh.fromHalfFloatBits(previous)
    }

    /**
     * Re-applies one undone step.
     *
     * @param current The live mesh, snapshotted back onto the undo stack.
     * @return The re-applied mesh, or `null` when there is nothing to redo.
     */
    fun redo(current: LiquifyMesh): LiquifyMesh? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current.toHalfFloatBits())
        while (undoStack.size > capacity) {
            undoStack.removeFirst()
        }
        return LiquifyMesh.fromHalfFloatBits(next)
    }

    /** Drops every snapshot, for example after loading a persisted mesh. */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Bytes currently held by all snapshots. */
    fun memoryFootprintBytes(): Int =
        (undoStack.size + redoStack.size) * LiquifyMeshCodec.HALF_FLOAT_BYTE_COUNT

    companion object {
        /** Maximum number of undo steps the session keeps. */
        const val DEFAULT_CAPACITY = 50
    }
}
