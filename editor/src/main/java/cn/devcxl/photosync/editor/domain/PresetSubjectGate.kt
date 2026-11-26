package cn.devcxl.photosync.editor.domain

/**
 * Decides whether applying a preset on this photo still needs its own `SubjectMask`.
 *
 * A preset stores `SubjectOps` parameters (blur, foreground/background grade, background mode) but
 * never the source photo's mask file: the mask describes *where* the subject is in one specific
 * frame. When such a preset lands on a photo without a mask, the editor must run the on-device
 * segmentation once before the effect can render.
 */
object PresetSubjectGate {

    /**
     * True when [graph] carries a non-neutral subject group without a bound mask reference.
     *
     * A neutral group never needs a mask even when the reference is blank, and a group with a
     * reference is already rendered, so this is exactly the "apply preset → compute mask" trigger.
     */
    fun needsMask(graph: EditGraph): Boolean =
        !graph.subject.isIdentity() && graph.subject.maskRef.isBlank()
}
