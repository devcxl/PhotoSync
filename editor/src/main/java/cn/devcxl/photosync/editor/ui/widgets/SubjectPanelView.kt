package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Tier-1 panel of `AI -> 主体`: the three background modes plus the preset solid-colour swatches.
 *
 * The view is a pure presentation binder. It never writes the `EditGraph`; [Listener] hands the
 * selected mode/colour to [cn.devcxl.photosync.editor.ui.panels.EditorPanelController], which owns
 * the undo transaction and the asynchronous mask generation.
 *
 * The structure deliberately has room for the Wave 3 additions (background blur and independent
 * foreground/background grading): those controls will be added as sibling rows under the swatches.
 */
internal class SubjectPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Callbacks the panel reports to the controller. */
    interface Listener {
        /** The user selected [mode]; a cut-out mode may need a model download first. */
        fun onModeSelected(mode: SubjectBackgroundMode)

        /** The user picked [argb]; selecting a swatch always switches to the solid mode. */
        fun onColorSelected(argb: Int)
    }

    var listener: Listener? = null

    private val modeChips = linkedMapOf<SubjectBackgroundMode, TextView>()
    private val swatchViews = mutableListOf<View>()
    private var selectedColor: Int = SubjectOps.DEFAULT_BG_COLOR_ARGB
    private var selectedMode: SubjectBackgroundMode = SubjectBackgroundMode.ORIGINAL
    private lateinit var modeRow: LinearLayout
    private lateinit var swatchRow: LinearLayout

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        modeRow = findViewById(R.id.subjectModeRow)
        swatchRow = findViewById(R.id.subjectSwatchRow)
        buildModeChips()
        buildSwatches()
    }

    /** Repaints the panel from [subject]. */
    fun bind(subject: SubjectOps) {
        selectedMode = subject.bgMode
        selectedColor = subject.bgColorArgb
        modeChips.forEach { (mode, chip) ->
            PanelChipFactory.applySelection(context, chip, mode == selectedMode)
            chip.alpha = if (mode == selectedMode) 1.0f else 0.75f
        }
        val colors = swatchColors()
        swatchViews.forEachIndexed { index, view ->
            val color = colors.getOrNull(index) ?: return@forEachIndexed
            view.background = swatchBackground(color, color == selectedColor)
        }
        swatchRow.alpha = if (selectedMode == SubjectBackgroundMode.SOLID) 1.0f else 0.6f
    }

    private fun buildModeChips() {
        val labels = listOf(
            SubjectBackgroundMode.ORIGINAL to R.string.editor_subject_mode_original,
            SubjectBackgroundMode.TRANSPARENT to R.string.editor_subject_mode_transparent,
            SubjectBackgroundMode.SOLID to R.string.editor_subject_mode_solid
        )
        val density = resources.displayMetrics.density
        labels.forEach { (mode, labelRes) ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = labelRes,
                heightPx = (30 * density).toInt(),
                textSizeSp = 12.0f,
                paddingPx = (12 * density).toInt()
            )
            chip.setOnClickListener {
                listener?.onModeSelected(mode)
            }
            modeChips[mode] = chip
            modeRow.addView(chip)
        }
    }

    private fun buildSwatches() {
        val density = resources.displayMetrics.density
        val size = (26 * density).toInt()
        swatchColors().forEach { color ->
            val swatch = View(context, null)
            swatch.contentDescription = context.getString(
                R.string.editor_subject_swatch_description,
                "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0')
            )
            swatch.background = swatchBackground(color, false)
            swatch.setOnClickListener {
                listener?.onColorSelected(color)
            }
            swatchViews.add(swatch)
            swatchRow.addView(
                swatch,
                LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * density).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
        }
    }

    private fun swatchColors(): List<Int> =
        resources.getStringArray(R.array.editor_subject_swatch_colors).map(::parseColor)

    private fun parseColor(raw: String): Int = try {
        raw.toColorInt()
    } catch (error: IllegalArgumentException) {
        SubjectOps.DEFAULT_BG_COLOR_ARGB
    }

    private fun swatchBackground(color: Int, selected: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                ((if (selected) STROKE_SELECTED_DP else STROKE_IDLE_DP) * density).toInt(),
                if (selected) SELECTED_STROKE_COLOR else IDLE_STROKE_COLOR
            )
        }
    }

    private companion object {
        const val STROKE_SELECTED_DP = 3
        const val STROKE_IDLE_DP = 1
        const val SELECTED_STROKE_COLOR = 0xFF2F80ED.toInt()
        const val IDLE_STROKE_COLOR = 0x55FFFFFF
    }
}
