package cn.devcxl.photosync.editor.domain

/**
 * Session state of the liquify tool: the live displacement mesh, its brush settings and its
 * 50-step undo history.
 *
 * The session deliberately keeps the mesh outside [EditGraph]: the graph only ever stores a
 * [LiquifyRef] to the compressed payload, so a hundred brush strokes cannot grow the database row.
 * A stroke that the two-finger preemption rule cancels is rolled back from the pre-stroke snapshot
 * without touching the history, which is what makes "put a second finger down to zoom" safe.
 *
 * Writing the payload is not part of the session: `EditorSaveCoordinator` copies the grid on the
 * main thread and persists that copy on a worker, so an undo or a fresh stroke cannot change what a
 * running save is writing.
 */
internal class LiquifySession(
    private val store: LiquifyMeshStore,
    private val history: LiquifyHistory = LiquifyHistory()
) {

    /** The live displacement grid; never `null`, possibly all zeros. */
    var mesh: LiquifyMesh = LiquifyMesh.empty()
        private set

    /**
     * Monotonic change counter of [mesh].
     *
     * The mesh is mutated in place by a push/restore stamp, so its identity is not a valid cache
     * key. Coordinate projections use this revision to rebuild their composed warp only when the
     * grid actually changed.
     */
    var revision: Int = 0
        private set

    /** Brush mode, size and strength. */
    var settings: LiquifyBrushSettings = LiquifyBrushSettings()
        private set

    private var strokeStart: LiquifyMesh? = null

    /** True when the mesh carries a visible displacement. */
    val hasMesh: Boolean
        get() = !mesh.isZero()

    /** True when a brush stroke can be undone. */
    val canUndo: Boolean
        get() = history.canUndo

    /** True when a stroke can be re-applied. */
    val canRedo: Boolean
        get() = history.canRedo

    /** Replaces the brush settings and returns the applied values. */
    fun updateSettings(
        transform: (LiquifyBrushSettings) -> LiquifyBrushSettings
    ): LiquifyBrushSettings {
        settings = transform(settings)
        return settings
    }

    /** Snapshots the mesh so a preempted stroke can be rolled back byte for byte. */
    fun beginStroke() {
        if (strokeStart == null) strokeStart = mesh.copy()
    }

    /**
     * Applies one push-stamp of the active stroke.
     *
     * @param prevUv Previous touch position in pre-crop normalized coordinates.
     * @param nextUv Current touch position in pre-crop normalized coordinates.
     * @param radiusNorm Brush radius in pre-crop normalized units; the caller scales the on-screen
     *   size by the geometry projection so the ring and the grid agree.
     * @param frameAspect Width/height ratio of the pre-crop frame, keeping the brush circular.
     */
    fun pushStroke(prevUv: Uv, nextUv: Uv, radiusNorm: Float, frameAspect: Float) {
        mesh.applyPushStroke(
            prevUv = prevUv,
            nextUv = nextUv,
            radiusNorm = radiusNorm,
            strength = settings.strength,
            frameAspect = frameAspect
        )
        revision++
    }

    /**
     * Applies one restore-stamp of the active stroke.
     *
     * @param uv Touch position in pre-crop normalized coordinates.
     * @param radiusNorm Brush radius in pre-crop normalized units.
     * @param frameAspect Width/height ratio of the pre-crop frame, keeping the brush circular.
     */
    fun restoreStroke(uv: Uv, radiusNorm: Float, frameAspect: Float) {
        mesh.applyRestoreStroke(
            uv = uv,
            radiusNorm = radiusNorm,
            strength = settings.strength,
            frameAspect = frameAspect
        )
        revision++
    }

    /** Rolls the mesh back to the pre-stroke snapshot without creating an undo step. */
    fun cancelStroke() {
        val start = strokeStart ?: return
        strokeStart = null
        mesh = start
        revision++
    }

    /**
     * Seals the active stroke, pushing its pre-stroke state onto the history.
     *
     * @return True when the mesh changed and an undo step was recorded.
     */
    fun commitStroke(): Boolean {
        val start = strokeStart ?: return false
        strokeStart = null
        if (mesh.contentEquals(start)) return false
        history.record(start)
        return true
    }

    /** Restores the previous mesh. */
    fun undo(): Boolean {
        val restored = history.undo(mesh) ?: return false
        mesh = restored
        revision++
        return true
    }

    /** Re-applies the most recently undone mesh. */
    fun redo(): Boolean {
        val replayed = history.redo(mesh) ?: return false
        mesh = replayed
        revision++
        return true
    }

    /**
     * Clears every displacement as one undoable step.
     *
     * @return True when there was something to clear.
     */
    fun reset(): Boolean {
        if (mesh.isZero()) return false
        history.record(mesh)
        mesh = LiquifyMesh.empty()
        revision++
        return true
    }

    /**
     * Loads the mesh referenced by [ref] and clears the history.
     *
     * @return True when a mesh was restored; `false` when [ref] is absent or failed verification,
     *   in which case the session stays empty and the caller must drop the stale reference.
     */
    fun load(ref: LiquifyRef?): Boolean {
        history.reset()
        mesh = LiquifyMesh.empty()
        revision++
        if (ref == null) return false
        val loaded = store.read(ref) ?: return false
        mesh = loaded
        revision++
        return true
    }
}
