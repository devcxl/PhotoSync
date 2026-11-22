package cn.devcxl.photosync.editor.vision

import android.graphics.Bitmap

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.BodyWarpSolver
import cn.devcxl.photosync.editor.domain.FaceMlsSolver
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.SkinMaskMath

/**
 * Turns one MediaPipe pass into the concrete artefacts the editor consumes: the dual-mode
 * `skinMask`, the person mask and the resolved body guides.
 *
 * The analysis always works on a `256 x 256` proxy because the segmentation models output that
 * resolution; mapping it back to the frame is a plain normalized-coordinate transform, which the
 * CPU solvers and the `GL_R8` mask texture both understand.
 */
class PortraitAnalyzer(private val engine: MediaPipePortraitEngine) {

    /**
     * Analyses [bitmap], which the caller should hand over at a modest proxy size (a 512 px long
     * edge is plenty for a 256 px segmentation pass).
     *
     * @param body Persisted `portrait.body` values, so the returned guides already honour the
     *   user's dragged waist / hip lines.
     */
    fun analyze(bitmap: Bitmap, body: BodyOps = BodyOps()): PortraitAnalysis? {
        val result = engine.analyze(bitmap)
        if (result == null) {
            Timber.w("Portrait analysis produced no result")
            return null
        }
        val faces = FaceMlsSolver.selectFaces(
            result.faces.sortedByDescending { it.boundingBoxArea() }
        )
        val colours = readProxyColours(bitmap) ?: return null
        val holeMask = SkinMaskMath.holeMask(faces)
        val masks = result.confidenceMasks
        val personMask = if (masks != null) personFromClasses(masks) else result.personMask
        val skinMask = when {
            masks != null -> SkinMaskMath.skinMaskFromClasses(
                bodySkin = masks[PortraitBundledModels.BODY_SKIN_CLASS],
                faceSkin = masks[PortraitBundledModels.FACE_SKIN_CLASS],
                hair = masks[PortraitBundledModels.HAIR_CLASS],
                holeMask = holeMask,
                srgb = colours
            )
            personMask != null -> SkinMaskMath.skinMaskFromPerson(personMask, holeMask, colours)
            else -> null
        }
        if (skinMask == null) {
            Timber.w("No segmentation mask was produced; the Portrait tab stays in reshape-only mode")
            return null
        }
        val mainFace = faces.firstOrNull()
        return PortraitAnalysis(
            faces = faces,
            skinMask = skinMask,
            personMask = personMask,
            guides = BodyWarpSolver.resolveGuides(mainFace, personMask, body),
            usesMulticlass = masks != null
        )
    }

    /**
     * Person probability from the six-class masks: everything except the background class. Skin,
     * hair and clothes all count, which is exactly the body mask Body Reshaping needs.
     */
    private fun personFromClasses(masks: List<FloatArray>): FloatArray {
        val background = masks[0]
        return FloatArray(background.size) { index ->
            (1.0f - background[index].coerceIn(0.0f, 1.0f)).coerceIn(0.0f, 1.0f)
        }
    }

    private fun readProxyColours(bitmap: Bitmap): FloatArray? {
        val scaled = if (bitmap.width == MASK_SIZE && bitmap.height == MASK_SIZE && !bitmap.isRecycled) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, MASK_SIZE, MASK_SIZE, true)
        }
        if (scaled === bitmap) return pixelColours(bitmap)
        return try {
            pixelColours(scaled)
        } finally {
            scaled.recycle()
        }
    }

    private fun pixelColours(bitmap: Bitmap): FloatArray? {
        if (bitmap.isRecycled) return null
        val pixels = IntArray(MASK_SIZE * MASK_SIZE)
        bitmap.getPixels(pixels, 0, MASK_SIZE, 0, 0, MASK_SIZE, MASK_SIZE)
        val colours = FloatArray(pixels.size * CHANNELS)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val offset = index * CHANNELS
            colours[offset] = ((pixel ushr 16) and 0xFF) / EIGHT_BIT_MAX
            colours[offset + 1] = ((pixel ushr 8) and 0xFF) / EIGHT_BIT_MAX
            colours[offset + 2] = (pixel and 0xFF) / EIGHT_BIT_MAX
        }
        return colours
    }

    private companion object {
        private const val MASK_SIZE = SkinMaskMath.MASK_SIZE
        private const val CHANNELS = 3
        private const val EIGHT_BIT_MAX = 255.0f
    }
}
