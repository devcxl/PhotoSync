package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ViewBaseLookToggleBinding
import cn.devcxl.photosync.editor.domain.BaseLook

/**
 * Two-state RAW base-look pill rendered as `RAW:默认观感 | 线性中性`.
 *
 * The selected half is highlighted and tapping the other half reports it through
 * [onLookSelected]; the host owns the actual [BaseLook] state so the choice flows through the
 * `EditGraph` undo stack.
 */
class BaseLookToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = ViewBaseLookToggleBinding.inflate(LayoutInflater.from(context), this)

    /** Invoked with the tapped look; never invoked for the already-selected look. */
    var onLookSelected: ((BaseLook) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        binding.baseLookCameraDefault.setOnClickListener {
            onLookSelected?.invoke(BaseLook.CAMERA_DEFAULT)
        }
        binding.baseLookLinearNeutral.setOnClickListener {
            onLookSelected?.invoke(BaseLook.LINEAR_NEUTRAL)
        }
    }

    /** Highlights [look] as the active baseline. */
    fun setLook(look: BaseLook) {
        applySelected(binding.baseLookCameraDefault, look == BaseLook.CAMERA_DEFAULT)
        applySelected(binding.baseLookLinearNeutral, look == BaseLook.LINEAR_NEUTRAL)
    }

    private fun applySelected(view: TextView, selected: Boolean) {
        val colorRes = if (selected) R.color.editor_text_primary else R.color.editor_text_muted
        view.setTextColor(ContextCompat.getColor(context, colorRes))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }
}
