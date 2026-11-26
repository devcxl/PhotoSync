package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.cloud.CloudEditMode
import cn.devcxl.photosync.editor.ui.CloudPanelPort
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Everything the cloud panel needs to render one frame of controller state.
 *
 * @property mode Selected request shape.
 * @property prompt Current prompt text, restored when the panel is rebound.
 * @property hasLayer True when the graph already contains a `CloudEditLayer`.
 * @property opacity Current layer opacity in `0..100`.
 * @property softMaskWarning True when the selected provider only supports advisory masks.
 */
internal data class CloudPanelState(
    val mode: CloudEditMode,
    val prompt: String,
    val hasLayer: Boolean,
    val opacity: Int,
    val softMaskWarning: Boolean
)

/** User intents raised by [CloudPanelView]. */
internal interface CloudPanelListener {

    /** The user switched the request shape. */
    fun onCloudModeSelected(mode: CloudEditMode)

    /** The prompt text changed. */
    fun onCloudPromptChanged(text: String)

    /** The user asked to send the edit. */
    fun onCloudGenerateClicked()

    /** The opacity slider moved; this is a live preview update, not an undo step. */
    fun onCloudOpacityChanged(opacity: Int)

    /** The user released the opacity slider; this is the undoable commit. */
    fun onCloudOpacityCommitted(opacity: Int)

    /** The user asked to remove the current cloud layer. */
    fun onCloudRemoveLayerClicked()
}

/**
 * Tier-1 panel of `AI -> 云端`.
 *
 * A pure presentation binder: it never writes the `EditGraph` or talks to the network. Mode chips,
 * prompt edits, generation, the opacity slider and layer removal are forwarded through
 * [CloudPanelListener], while [renderCloudPanel] repaints from controller state. The Gemini
 * advisory sits next to the generate button so a local repaint never hides the loose-boundary
 * warning.
 */
internal class CloudPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), CloudPanelPort {

    /** Intent callbacks; assigned by the controller during integration. */
    var listener: CloudPanelListener? = null

    private val modeChips = linkedMapOf<CloudEditMode, TextView>()
    private val promptInput: EditText
    private val modeRow: LinearLayout
    private val softMaskWarning: TextView
    private val providerHint: TextView
    private val generateButton: TextView
    private val layerControls: LinearLayout
    private val opacitySeek: SeekBar
    private val opacityValue: TextView
    private val removeLayerButton: TextView
    private var binding = false

    init {
        orientation = VERTICAL
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.editor_cloud_panel, this, true)
        promptInput = findViewById(R.id.cloudPromptInput)
        modeRow = findViewById(R.id.cloudModeRow)
        softMaskWarning = findViewById(R.id.cloudSoftMaskWarning)
        providerHint = findViewById(R.id.cloudProviderHint)
        generateButton = findViewById(R.id.cloudGenerateButton)
        layerControls = findViewById(R.id.cloudLayerControls)
        opacitySeek = findViewById(R.id.cloudOpacitySeek)
        opacityValue = findViewById(R.id.cloudOpacityValue)
        removeLayerButton = findViewById(R.id.cloudRemoveLayerButton)
        buildModeChips()
        bindActions()
    }

    override fun setCloudPanelVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun renderCloudPanel(state: CloudPanelState) {
        binding = true
        modeChips.forEach { (mode, chip) ->
            PanelChipFactory.applySelection(context, chip, mode == state.mode)
            chip.alpha = if (mode == state.mode) 1.0f else 0.75f
        }
        if (promptInput.text.toString() != state.prompt) promptInput.setText(state.prompt)
        softMaskWarning.visibility = if (state.softMaskWarning) View.VISIBLE else View.GONE
        providerHint.visibility = if (state.hasLayer) View.GONE else View.VISIBLE
        layerControls.visibility = if (state.hasLayer) View.VISIBLE else View.GONE
        opacitySeek.progress = state.opacity.coerceIn(0, 100)
        opacityValue.text = context.getString(R.string.editor_cloud_opacity_value, state.opacity)
        binding = false
    }

    private fun buildModeChips() {
        val labels = listOf(
            CloudEditMode.WHOLE_IMAGE to R.string.editor_cloud_mode_whole,
            CloudEditMode.PAINTED_MASK to R.string.editor_cloud_mode_painted,
            CloudEditMode.SUBJECT_BACKGROUND to R.string.editor_cloud_mode_background
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
            chip.setOnClickListener { listener?.onCloudModeSelected(mode) }
            modeChips[mode] = chip
            modeRow.addView(chip)
        }
    }

    private fun bindActions() {
        promptInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                if (!binding) listener?.onCloudPromptChanged(text?.toString().orEmpty())
            }
        })
        generateButton.setOnClickListener { listener?.onCloudGenerateClicked() }
        removeLayerButton.setOnClickListener { listener?.onCloudRemoveLayerClicked() }
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || binding) return
                opacityValue.text = context.getString(R.string.editor_cloud_opacity_value, progress)
                listener?.onCloudOpacityChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                listener?.onCloudOpacityCommitted(seekBar.progress)
            }
        })
    }
}
