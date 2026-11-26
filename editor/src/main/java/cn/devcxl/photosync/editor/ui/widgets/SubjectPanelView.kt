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
 * Tier-1 panel of `AI -> 主体`: the three background modes, the preset solid-colour swatches and the
 * seven subject effect dials (background blur plus independent foreground/background exposure,
 * temperature and saturation).
 *
 * The view is a pure presentation binder. It never writes the `EditGraph`; [Listener] hands the
 * mode/colour to [cn.devcxl.photosync.editor.ui.panels.EditorPanelController] and [GradingListener]
 * forwards the dial values to the subject controller, which owns the undo transaction and the
 * asynchronous mask generation triggered by the first adjustment.
 */
internal class SubjectPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Callbacks the panel reports for the exclusive background choices. */
    interface Listener {
        /** The user selected [mode]; a cut-out mode may need a model download first. */
        fun onModeSelected(mode: SubjectBackgroundMode)

        /** The user picked [argb]; selecting a swatch always switches to the solid mode. */
        fun onColorSelected(argb: Int)
    }

    /**
     * Callbacks of the seven effect dials.
     *
     * Deliberately separate from [Listener]: grading is a continuous parameter write that may need a
     * mask inference first, while mode/colour go through the cut-out controller.
     */
    interface GradingListener {
        /** The user moved a dial; [subject] carries the new value and any earlier ones. */
        fun onSubjectAdjusted(subject: SubjectOps)

        /** The user lifted the finger; the undo transaction can be sealed. */
        fun onSubjectAdjustmentStopped()
    }

    var listener: Listener? = null
    var gradingListener: GradingListener? = null

    private val modeChips = linkedMapOf<SubjectBackgroundMode, TextView>()
    private val swatchViews = mutableListOf<View>()
    private val dials = mutableListOf<DialBinding>()
    private var selectedColor: Int = SubjectOps.DEFAULT_BG_COLOR_ARGB
    private var selectedMode: SubjectBackgroundMode = SubjectBackgroundMode.ORIGINAL
    private var selectedSubject: SubjectOps = SubjectOps()
    private lateinit var modeRow: LinearLayout
    private lateinit var swatchRow: LinearLayout
    private lateinit var dialContainer: LinearLayout

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        modeRow = findViewById(R.id.subjectModeRow)
        swatchRow = findViewById(R.id.subjectSwatchRow)
        dialContainer = findViewById(R.id.subjectDialContainer)
        buildModeChips()
        buildSwatches()
        buildDials()
    }

    /** Repaints the panel from [subject]. */
    fun bind(subject: SubjectOps) {
        selectedSubject = subject
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
        dials.forEach { it.row.setValue(it.read(selectedSubject)) }
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

    private fun buildDials() {
        addDial(
            labelRes = R.string.editor_subject_blur,
            min = 0.0f,
            max = 100.0f,
            signed = false,
            read = { it.blur },
            write = { subject, value -> subject.copy(blur = value) }
        )
        addDial(
            labelRes = R.string.editor_subject_fg_exposure,
            min = -100.0f,
            max = 100.0f,
            signed = true,
            read = { it.fgExposure },
            write = { subject, value -> subject.copy(fgExposure = value) }
        )
        addDial(
            labelRes = R.string.editor_subject_fg_temp,
            min = -100.0f,
            max = 100.0f,
            signed = true,
            read = { it.fgTemp },
            write = { subject, value -> subject.copy(fgTemp = value) }
        )
        addDial(
            labelRes = R.string.editor_subject_fg_sat,
            min = -100.0f,
            max = 100.0f,
            signed = true,
            read = { it.fgSat },
            write = { subject, value -> subject.copy(fgSat = value) }
        )
        addDial(
            labelRes = R.string.editor_subject_bg_exposure,
            min = -100.0f,
            max = 100.0f,
            signed = true,
            read = { it.bgExposure },
            write = { subject, value -> subject.copy(bgExposure = value) }
        )
        addDial(
            labelRes = R.string.editor_subject_bg_temp,
            min = -100.0f,
            max = 100.0f,
            signed = true,
            read = { it.bgTemp },
            write = { subject, value -> subject.copy(bgTemp = value) }
        )
        addDial(
            labelRes = R.string.editor_subject_bg_sat,
            min = -100.0f,
            max = 100.0f,
            signed = true,
            read = { it.bgSat },
            write = { subject, value -> subject.copy(bgSat = value) }
        )
    }

    private fun addDial(
        labelRes: Int,
        min: Float,
        max: Float,
        signed: Boolean,
        read: (SubjectOps) -> Int,
        write: (SubjectOps, Int) -> SubjectOps
    ) {
        val row = LabeledDialRow.create(
            context = context,
            labelRes = labelRes,
            min = min,
            max = max,
            zeroSnap = if (signed) ZERO_SNAP else 0.0f,
            formatRes = if (signed) {
                R.string.editor_value_signed_fmt
            } else {
                R.string.editor_value_unsigned_fmt
            },
            onChanged = { value ->
                selectedSubject = write(selectedSubject, value)
                gradingListener?.onSubjectAdjusted(selectedSubject)
            },
            onStopTracking = { gradingListener?.onSubjectAdjustmentStopped() }
        )
        dials += DialBinding(row, read)
        dialContainer.addView(row.rowView)
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

    private data class DialBinding(
        val row: LabeledDialRow,
        val read: (SubjectOps) -> Int
    )

    private companion object {
        const val STROKE_SELECTED_DP = 3
        const val STROKE_IDLE_DP = 1
        const val ZERO_SNAP = 2.0f
        const val SELECTED_STROKE_COLOR = 0xFF2F80ED.toInt()
        const val IDLE_STROKE_COLOR = 0x55FFFFFF
    }
}
