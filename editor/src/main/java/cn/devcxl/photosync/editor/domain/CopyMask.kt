package cn.devcxl.photosync.editor.domain

/**
 * Subset selector for copying [EditGraph] parameters between photos.
 *
 * The defaults copy everything that describes a *look* (`baseLook`, tone, colour, detail, effects and
 * portrait skin) and exclude everything that describes *this* frame's composition: crop, straighten,
 * perspective, rotate/flip, liquify strokes and the face/body MLS warps. Pasting a graded look onto a
 * burst therefore never moves the next frame's crop.
 */
data class CopyMask(
    val includeBaseLook: Boolean = true,
    val includeTone: Boolean = true,
    val includeColor: Boolean = true,
    val includeDetail: Boolean = true,
    val includeEffects: Boolean = true,
    val includePortraitSkin: Boolean = true,
    val includeGeometryCropRotate: Boolean = false,
    val includeLiquify: Boolean = false,
    val includePortraitReshapeBody: Boolean = false
) {

    /**
     * Returns [target] with every axis enabled in this mask replaced by [source]'s.
     *
     * The result is sanitized so a hostile or stale preset row can never push `NaN` or an
     * out-of-range value into the renderer.
     */
    fun apply(source: EditGraph, target: EditGraph): EditGraph {
        val merged = target.copy(
            baseLook = pick(includeBaseLook, source.baseLook, target.baseLook),
            geometry = mergeGeometry(source.geometry, target.geometry),
            tone = pick(includeTone, source.tone, target.tone),
            color = pick(includeColor, source.color, target.color),
            detail = pick(includeDetail, source.detail, target.detail),
            effects = pick(includeEffects, source.effects, target.effects),
            portrait = mergePortrait(source.portrait, target.portrait)
        )
        return EditValidator.sanitize(merged)
    }

    /**
     * Extracts the masked axes of [source] as a standalone look, i.e. `apply(source, EditGraph())`.
     */
    fun extract(source: EditGraph): EditGraph = apply(source, EditGraph())

    private fun mergeGeometry(source: GeometryOps, target: GeometryOps): GeometryOps = when {
        includeGeometryCropRotate && includeLiquify -> source
        includeGeometryCropRotate -> source.copy(liquify = target.liquify)
        includeLiquify -> target.copy(liquify = source.liquify)
        else -> target
    }

    private fun mergePortrait(source: PortraitOps, target: PortraitOps): PortraitOps = target.copy(
        skin = pick(includePortraitSkin, source.skin, target.skin),
        reshape = pick(includePortraitReshapeBody, source.reshape, target.reshape),
        body = pick(includePortraitReshapeBody, source.body, target.body)
    )

    /** Explicit `if` instead of `takeIf` so the generic type stays inferred without boxing. */
    private fun <T> pick(enabled: Boolean, fromSource: T, fromTarget: T): T =
        if (enabled) fromSource else fromTarget

    companion object {

        /** Number of independently selectable axes in the copy checklist. */
        const val AXIS_COUNT = 9

        /**
         * Maps the `AXIS_COUNT` copy-checklist checkboxes onto a mask.
         *
         * Living here keeps the dialog free of index knowledge and makes the mapping unit-testable; a
         * short array degrades to the documented defaults instead of throwing.
         */
        fun fromCheckboxState(checked: BooleanArray): CopyMask = CopyMask(
            includeBaseLook = checked.getOrElse(0) { true },
            includeTone = checked.getOrElse(1) { true },
            includeColor = checked.getOrElse(2) { true },
            includeDetail = checked.getOrElse(3) { true },
            includeEffects = checked.getOrElse(4) { true },
            includePortraitSkin = checked.getOrElse(5) { true },
            includeGeometryCropRotate = checked.getOrElse(6) { false },
            includeLiquify = checked.getOrElse(7) { false },
            includePortraitReshapeBody = checked.getOrElse(8) { false }
        )
    }
}

/**
 * Merges a [Preset]'s parameters into an existing graph.
 *
 * A preset carries the whole appearance — grade, skin, face reshape and body reshape — but never
 * the frame geometry, so applying one leaves this photo's crop, straightening, perspective and
 * liquify work untouched. Clearing to `无预设` is stronger still: it resets the whole graph,
 * including the geometry and the portrait group.
 */
object PresetGraphMerger {

    /**
     * The axes a preset carries: the whole appearance, portrait work included.
     *
     * The default [CopyMask] deliberately excludes `reshape` / `body` because a cross-photo paste
     * must not move the next frame's face; a preset is a look the user saved on purpose, so it owns
     * those axes instead.
     */
    private val PRESET_MASK = CopyMask(includePortraitReshapeBody = true)

    /** Applies [preset] onto [target], preserving only the frame `geometry`. */
    fun applyPreset(preset: EditGraph, target: EditGraph): EditGraph =
        PRESET_MASK.apply(preset, target)

    /** Extracts the axes a preset stores from [graph]: the appearance, never the geometry. */
    fun extractPreset(graph: EditGraph): EditGraph = PRESET_MASK.extract(graph)

    /**
     * Returns [target] with every axis — grade, portrait and frame geometry — back at its neutral
     * default, i.e. the state a freshly imported photo starts in.
     *
     * The liquify grid is referenced from `geometry` but lives outside the graph, so the editor must
     * additionally clear its [LiquifySession] mesh when it applies this reset; the history
     * coordinator records both halves as one undoable step.
     */
    fun resetToOriginal(target: EditGraph): EditGraph = EditValidator.sanitize(
        target.copy(
            baseLook = BaseLook.CAMERA_DEFAULT,
            geometry = GeometryOps(),
            tone = ToneOps(),
            color = ColorOps(),
            detail = DetailOps(),
            effects = EffectsOps(),
            portrait = PortraitOps()
        )
    )

    /**
     * True when every axis a preset carries sits at its neutral default.
     *
     * Frame geometry is deliberately ignored: presets never carry it, so a crop cannot make a
     * preset "active". [resetToOriginal] is broader than this check — clearing to `无预设` resets
     * the whole graph.
     */
    fun hasNeutralPresetAxes(graph: EditGraph): Boolean =
        graph.baseLook == BaseLook.CAMERA_DEFAULT &&
            graph.tone.isIdentity() &&
            graph.color.isIdentity() &&
            graph.detail.isIdentity() &&
            graph.effects.isIdentity() &&
            graph.portrait.isIdentity()

    /**
     * Returns the id of the first entry in [candidates] whose carried axes match [graph], or `null`
     * when [graph] is neutral on those axes or matches none.
     */
    fun matchingPresetId(
        graph: EditGraph,
        candidates: Iterable<Pair<String, String>>
    ): String? {
        if (hasNeutralPresetAxes(graph)) return null
        val targetEncoded = EditGraphJsonCodec.encode(extractPreset(graph))
        for ((id, subsetJson) in candidates) {
            val decoded = EditGraphJsonCodec.decode(subsetJson) ?: continue
            val normalized = EditGraphJsonCodec.encode(extractPreset(decoded))
            if (normalized == targetEncoded) return id
        }
        return null
    }
}
