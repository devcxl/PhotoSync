package cn.devcxl.photosync.editor.ui

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.BodyGuideOverlayView

/**
 * Configures the GL preview surface and wires the crop and liquify overlay gestures.
 *
 * Keeping this out of [EditorActivity] leaves the activity to own only the edit session, the drawer
 * and the render/history flow.
 */
internal class EditorCanvasBinder(
    private val binding: ActivityEditorBinding,
    private val renderer: EditorGlRenderer,
    private val undoController: EditorUndoController,
    private val liquifySession: LiquifySession,
    private val host: Host
) {

    /** Callbacks the activity implements for gesture and viewport events. */
    interface Host {
        /** A preview gesture started dragging. */
        fun onPreviewDragStarted()

        /** A preview gesture released and the full-quality frame should render. */
        fun onPreviewDragFinished()

        /** The canvas pan/zoom transform changed. */
        fun onCanvasTransformChanged(transform: CanvasTransform)

        /** Press-and-hold compare started or ended. */
        fun onCompareChanged(comparing: Boolean)

        /** The liquify displacement grid changed mid-stroke. */
        fun onLiquifyMeshChanged()

        /** A liquify stroke was committed to the undo timeline. */
        fun onLiquifyStrokeFinished()

        /** The user asked to clear every liquify displacement. */
        fun onLiquifyResetRequested()

        /** A Body Reshaping guide line moved. */
        fun onBodyGuidesChanged(waistY: Float, hipY: Float)

        /** A Body Reshaping guide drag ended and its undo step must be sealed. */
        fun onBodyGuidesCommitted()
    }

    /**
     * Configures the GL surface view.
     *
     * @return True when the device exposes an OpenGL ES 3.0 context.
     */
    fun configureSurfaceView(): Boolean {
        val activityManager =
            binding.root.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val preferEs3 =
            (activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= MIN_GLES_VERSION_ES3
        renderer.preferEs3 = preferEs3
        binding.glSurfaceView.setEGLContextClientVersion(if (preferEs3) 3 else 2)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        return preferEs3
    }

    /** Installs both overlay listeners; the liquify overlay starts hidden. */
    fun attachOverlays() {
        binding.cropOverlayView.listener = EditorGestureFactory.cropOverlay(
            undoController = undoController,
            onDragged = host::onPreviewDragStarted,
            onFinished = host::onPreviewDragFinished,
            onCanvasTransformChanged = host::onCanvasTransformChanged,
            onCompareHold = host::onCompareChanged
        )
        binding.liquifyBrushOverlayView.listener = EditorGestureFactory.liquifyOverlay(
            session = liquifySession,
            undoController = undoController,
            renderer = renderer,
            onMeshChanged = host::onLiquifyMeshChanged,
            onStrokeFinished = host::onLiquifyStrokeFinished,
            onCanvasTransformChanged = host::onCanvasTransformChanged,
            onResetRequested = host::onLiquifyResetRequested
        )
        binding.bodyGuideOverlayView.listener = object : BodyGuideOverlayView.Listener {
            override fun onGuidesChanged(waistY: Float, hipY: Float) =
                host.onBodyGuidesChanged(waistY, hipY)

            override fun onGuidesChangeFinished() = host.onBodyGuidesCommitted()
        }
        binding.liquifyBrushOverlayView.visibility = View.GONE
        binding.bodyGuideOverlayView.visibility = View.GONE
    }

    /** Drops the overlay listeners so a leaked view cannot call back after destruction. */
    fun detachOverlays() {
        binding.cropOverlayView.listener = null
        binding.liquifyBrushOverlayView.listener = null
        binding.bodyGuideOverlayView.listener = null
    }

    private companion object {
        private const val MIN_GLES_VERSION_ES3 = 0x30000
    }
}
