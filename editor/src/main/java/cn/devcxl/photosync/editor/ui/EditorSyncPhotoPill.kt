package cn.devcxl.photosync.editor.ui

import android.view.View

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding

/**
 * Counts photos the camera synced while the editor was in front.
 *
 * Only `RESUMED` collects: an editor in the background must not accumulate a pill the user never
 * saw, and the ingest itself never touches this activity's window, so an active slider drag or a
 * liquify stroke keeps its focus.
 */
internal class EditorSyncPhotoPill(
    private val binding: ActivityEditorBinding,
    private val scope: LifecycleCoroutineScope,
    private val lifecycle: Lifecycle,
    private val events: Flow<Long>?
) {

    private var count: Int = 0

    /** Starts observing the host's ingest events and wires the pill's reset tap. */
    fun observe() {
        val source = events ?: emptyFlow()
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                source.collect { increment() }
            }
        }
        binding.newSyncedPhotoPill.setOnClickListener { reset() }
    }

    private fun increment() {
        count++
        binding.newSyncedPhotoPill.text = binding.root.context.getString(
            R.string.editor_new_photos_pill,
            count
        )
        binding.newSyncedPhotoPill.visibility = View.VISIBLE
    }

    private fun reset() {
        count = 0
        binding.newSyncedPhotoPill.visibility = View.GONE
    }
}
