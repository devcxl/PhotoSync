package cn.devcxl.photosync.editor.domain

/**
 * Typed parameter keys for [EditorUndoController] coalescing windows.
 */
enum class EditParameterKey {
    TONE_EXPOSURE
}

/**
 * Manages immutable [EditGraph] undo/redo snapshots with a rolling coalescing window so
 * continuous slider dragging within [coalesceWindowMs] produces a single undo step.
 */
class EditorUndoController(
    initialGraph: EditGraph = EditGraph(),
    private val maxDepth: Int = DEFAULT_MAX_UNDO_DEPTH,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS
) {
    private val undoStack = ArrayDeque<EditGraph>()
    private val redoStack = ArrayDeque<EditGraph>()

    var currentGraph: EditGraph = EditValidator.sanitize(initialGraph)
        private set

    private var activeTransactionKey: EditParameterKey? = null
    private var lastDragTimestampMs: Long = 0L

    val undoDepth: Int
        get() = undoStack.size

    val redoDepth: Int
        get() = redoStack.size

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    /**
     * Resets the controller to [graph] and clears both undo and redo history.
     */
    fun resetTo(graph: EditGraph) {
        undoStack.clear()
        redoStack.clear()
        activeTransactionKey = null
        lastDragTimestampMs = 0L
        currentGraph = EditValidator.sanitize(graph)
    }

    /**
     * Records a parameter change during slider dragging or discrete clicks.
     *
     * If [parameterKey] matches the active transaction and arrives within [coalesceWindowMs]
     * of the previous tick, the existing top undo snapshot is kept and only [currentGraph]
     * advances. Otherwise a new undo snapshot is pushed.
     */
    fun onParameterDrag(
        newGraph: EditGraph,
        parameterKey: EditParameterKey,
        timestampMs: Long = System.currentTimeMillis()
    ): EditGraph {
        val sanitized = EditValidator.sanitize(newGraph)
        if (sanitized == currentGraph) {
            return currentGraph
        }
        val withinWindow = activeTransactionKey == parameterKey &&
            (timestampMs - lastDragTimestampMs) in 0..coalesceWindowMs
        if (!withinWindow) {
            pushUndoSnapshot(currentGraph)
            activeTransactionKey = parameterKey
        }
        lastDragTimestampMs = timestampMs
        redoStack.clear()
        currentGraph = sanitized
        return currentGraph
    }

    /**
     * Seals the current coalescing transaction when the user lifts their finger from a slider.
     */
    fun onParameterRelease() {
        activeTransactionKey = null
        lastDragTimestampMs = 0L
    }

    /**
     * Reverts to the previous snapshot on the undo stack, if any.
     */
    fun undo(): EditGraph {
        onParameterRelease()
        val previous = undoStack.removeLastOrNull() ?: return currentGraph
        redoStack.addLast(currentGraph)
        currentGraph = previous
        return currentGraph
    }

    /**
     * Re-applies the most recently undone snapshot, if any.
     */
    fun redo(): EditGraph {
        onParameterRelease()
        val next = redoStack.removeLastOrNull() ?: return currentGraph
        pushUndoSnapshot(currentGraph)
        currentGraph = next
        return currentGraph
    }

    private fun pushUndoSnapshot(snapshot: EditGraph) {
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
    }

    companion object {
        const val DEFAULT_MAX_UNDO_DEPTH = 50
        const val DEFAULT_COALESCE_WINDOW_MS = 300L
    }
}
