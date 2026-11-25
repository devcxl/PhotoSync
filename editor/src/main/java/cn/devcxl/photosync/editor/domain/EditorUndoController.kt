package cn.devcxl.photosync.editor.domain

/**
 * Typed parameter keys for [EditorUndoController] coalescing windows.
 *
 * Keys are grouped by the tool that produced the change so a continuous drag on one slider
 * collapses into a single undo step while switching tools always starts a new one.
 */
enum class EditParameterKey {
    TONE_EXPOSURE,
    TONE_BRIGHTNESS,
    TONE_CONTRAST,
    TONE_GLOW,
    TONE_HIGHLIGHTS,
    TONE_SHADOWS,
    TONE_WHITES,
    TONE_BLACKS,
    COLOR_TEMP,
    COLOR_TINT,
    COLOR_SATURATION,
    COLOR_VIBRANCE,
    COLOR_HSL,
    COLOR_CURVE,
    COLOR_SPLIT_TONING,
    COLOR_FADE,
    COLOR_LUT,
    BASE_LOOK,
    GEOMETRY_CROP,
    GEOMETRY_STRAIGHTEN,
    GEOMETRY_PERSPECTIVE,
    GEOMETRY_ROTATE_FLIP,
    GEOMETRY_LIQUIFY,
    DETAIL_CLARITY,
    DETAIL_TEXTURE,
    DETAIL_DEHAZE,
    DETAIL_SHARPEN,
    DETAIL_NOISE_REDUCTION,
    DETAIL_CA_RED_CYAN,
    DETAIL_CA_BLUE_YELLOW,
    DETAIL_CA_DEFRINGE,
    DETAIL_GRAIN,
    DETAIL_VIGNETTE,
    PORTRAIT_SKIN,
    PORTRAIT_RESHAPE,
    PORTRAIT_BODY,
    AI_SUBJECT,
    AI_INPAINT,
    PRESET
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

    var onStepPushed: (() -> Unit)? = null

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
            onStepPushed?.invoke()
        }
        lastDragTimestampMs = timestampMs
        redoStack.clear()
        currentGraph = sanitized
        return currentGraph
    }

    /**
     * Updates `geometry.liquify` on the current snapshot without pushing a duplicate parameter
     * undo step, because stroke undo/redo is owned by [LiquifyHistory].
     */
    fun replaceLiquifyRefInPlace(liquify: LiquifyRef?) {
        currentGraph = currentGraph.copy(
            geometry = currentGraph.geometry.copy(liquify = liquify)
        )
    }

    /**
     * Seals the current coalescing transaction when the user lifts their finger from a slider.
     */
    fun onParameterRelease() {
        activeTransactionKey = null
        lastDragTimestampMs = 0L
    }

    /**
     * Reverts to the previous snapshot on the undo stack, if any, preserving the active
     * liquify mesh reference which is managed independently by [LiquifyHistory].
     */
    fun undo(): EditGraph {
        onParameterRelease()
        val previous = undoStack.removeLastOrNull() ?: return currentGraph
        val liveLiquify = currentGraph.geometry.liquify
        redoStack.addLast(currentGraph)
        currentGraph = previous.copy(geometry = previous.geometry.copy(liquify = liveLiquify))
        return currentGraph
    }

    /**
     * Re-applies the most recently undone snapshot, if any.
     */
    fun redo(): EditGraph {
        onParameterRelease()
        val next = redoStack.removeLastOrNull() ?: return currentGraph
        val liveLiquify = currentGraph.geometry.liquify
        pushUndoSnapshot(currentGraph)
        currentGraph = next.copy(geometry = next.geometry.copy(liquify = liveLiquify))
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
