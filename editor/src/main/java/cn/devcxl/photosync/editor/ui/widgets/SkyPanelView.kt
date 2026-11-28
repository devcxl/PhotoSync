package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyCustomAsset
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Tier-1 panel of `AI -> 天空`: the original sky, the four zero-byte procedural presets, the user's
 * imported skies, the SAF import entry and the four P3 sliders.
 *
 * The view is a pure presentation binder: it never writes the `EditGraph`. [Listener] hands a chosen
 * asset or adjusted [SkyOps] to the controller, which owns the mask computation, the asset store and
 * the single `AI_SKY` undo transaction.
 */
internal class SkyPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Callbacks the panel reports to the sky controller. */
    interface Listener {
        /** The user chose a replacement (or the original sky). */
        fun onSkySelected(sky: SkyOps)

        /** The user moved a slider; [sky] carries every earlier value too. */
        fun onSkyAdjusted(sky: SkyOps)

        /** The user lifted the finger; the undo transaction can be sealed. */
        fun onSkyAdjustmentStopped()

        /** The user asked to import a sky photograph through SAF. */
        fun onImportRequested()

        /** The user long-pressed [sha256]; the controller owns the confirmation. */
        fun onCustomDeleteRequested(sha256: String)
    }

    var listener: Listener? = null

    private val dials = mutableListOf<DialBinding>()
    private var selectedSky: SkyOps = SkyOps()
    private var chipSignature: String? = null
    private lateinit var chipRow: LinearLayout
    private lateinit var dialContainer: LinearLayout
    private lateinit var noSkyHint: TextView

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        chipRow = findViewById(R.id.skyChipRow)
        dialContainer = findViewById(R.id.skyDialContainer)
        noSkyHint = findViewById(R.id.skyNoSkyHint)
        buildDials()
    }

    /**
     * Repaints the panel from the current sky group and the imported sky library.
     *
     * @param hasSky False shows the gentle `< 3 %` capsule; the panel stays usable so the user can
     *   still explore presets on a frame the classic segmenter could not read.
     */
    fun bind(sky: SkyOps, customAssets: List<SkyCustomAsset>, hasSky: Boolean) {
        selectedSky = sky
        val signature = chipSignatureOf(sky, customAssets)
        if (signature != chipSignature) {
            chipSignature = signature
            rebuildChips(sky, customAssets)
        }
        dials.forEach { dial -> dial.refresh(sky) }
        noSkyHint.visibility = if (hasSky) View.GONE else View.VISIBLE
    }

    private fun chipSignatureOf(sky: SkyOps, customAssets: List<SkyCustomAsset>): String =
        "${sky.assetType.wireName}|${sky.assetIdOrRelPath}|${sky.assetSha256}|" +
            customAssets.joinToString(",") { it.sha256 }

    private fun rebuildChips(sky: SkyOps, customAssets: List<SkyCustomAsset>) {
        chipRow.removeAllViews()
        addChip(
            label = context.getString(R.string.editor_sky_original),
            selected = sky.assetType == SkyAssetType.NONE
        ) {
            listener?.onSkySelected(SkyOps())
        }
        proceduralChips.forEach { (preset, labelRes) ->
            addChip(
                label = context.getString(labelRes),
                selected = sky.assetType == SkyAssetType.PROCEDURAL &&
                    ProceduralSkyPreset.fromWireId(sky.assetIdOrRelPath) == preset
            ) {
                listener?.onSkySelected(proceduralOps(preset))
            }
        }
        customAssets.forEachIndexed { index, asset ->
            val label = context.getString(R.string.editor_sky_custom_name, index + 1)
            val chip = addChip(
                label = label,
                selected = sky.assetType == SkyAssetType.CUSTOM &&
                    sky.assetSha256.equals(asset.sha256, ignoreCase = true)
            ) {
                listener?.onSkySelected(customOps(asset))
            }
            chip.contentDescription = context.getString(
                R.string.editor_sky_custom_description,
                index + 1
            )
            chip.setOnLongClickListener {
                listener?.onCustomDeleteRequested(asset.sha256)
                true
            }
        }
        addChip(label = context.getString(R.string.editor_sky_import), selected = false) {
            listener?.onImportRequested()
        }
    }

    private fun addChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        val chip = PanelChipFactory.createWithText(
            context = context,
            label = label,
            heightPx = (30 * density).toInt(),
            textSizeSp = 12.0f,
            paddingPx = (12 * density).toInt()
        )
        PanelChipFactory.applySelection(context, chip, selected)
        chip.alpha = if (selected) 1.0f else 0.75f
        chip.setOnClickListener { onClick() }
        chipRow.addView(chip)
        return chip
    }

    private fun proceduralOps(preset: ProceduralSkyPreset): SkyOps = selectedSky.copy(
        assetType = SkyAssetType.PROCEDURAL,
        assetIdOrRelPath = preset.wireId,
        assetSha256 = ""
    )

    private fun customOps(asset: SkyCustomAsset): SkyOps = selectedSky.copy(
        assetType = SkyAssetType.CUSTOM,
        assetIdOrRelPath = asset.relativePath,
        assetSha256 = asset.sha256
    )

    private fun buildDials() {
        addDial(
            labelRes = R.string.editor_sky_relight,
            min = 0.0f,
            max = 100.0f,
            step = 1.0f,
            zeroSnap = 0.0f,
            formatRes = R.string.editor_value_unsigned_fmt,
            read = { it.relight.toFloat() },
            write = { sky, value -> sky.copy(relight = value.toInt().coerceIn(0, 100)) }
        )
        addDial(
            labelRes = R.string.editor_sky_horizon_offset,
            min = -0.3f,
            max = 0.3f,
            step = 0.01f,
            zeroSnap = 0.01f,
            formatRes = R.string.editor_value_signed_decimal_fmt,
            read = { it.horizonOffset },
            write = { sky, value -> sky.copy(horizonOffset = value) }
        )
        addDial(
            labelRes = R.string.editor_sky_horizon_feather,
            min = 0.0f,
            max = 100.0f,
            step = 1.0f,
            zeroSnap = 0.0f,
            formatRes = R.string.editor_value_unsigned_fmt,
            read = { it.horizonFeather.toFloat() },
            write = { sky, value -> sky.copy(horizonFeather = value.toInt().coerceIn(0, 100)) }
        )
        addDial(
            labelRes = R.string.editor_sky_edge_shift,
            min = -2.0f,
            max = 2.0f,
            step = 1.0f,
            zeroSnap = 0.5f,
            formatRes = R.string.editor_value_signed_fmt,
            read = { it.edgeShift.toFloat() },
            write = { sky, value -> sky.copy(edgeShift = value.toInt().coerceIn(-2, 2)) }
        )
    }

    private fun addDial(
        labelRes: Int,
        min: Float,
        max: Float,
        step: Float,
        zeroSnap: Float,
        formatRes: Int,
        read: (SkyOps) -> Float,
        write: (SkyOps, Float) -> SkyOps
    ) {
        val row = LabeledDialRow.createDecimal(
            context = context,
            labelRes = labelRes,
            min = min,
            max = max,
            step = step,
            zeroSnap = zeroSnap,
            formatRes = formatRes,
            onChanged = { value ->
                selectedSky = write(selectedSky, value)
                listener?.onSkyAdjusted(selectedSky)
            },
            onStopTracking = { listener?.onSkyAdjustmentStopped() }
        )
        dials += DialBinding(row, read)
        dialContainer.addView(row.rowView)
    }

    private val proceduralChips: List<Pair<ProceduralSkyPreset, Int>> = listOf(
        ProceduralSkyPreset.CLEAR_BLUE to R.string.editor_sky_clear_blue,
        ProceduralSkyPreset.SUNSET_GLOW to R.string.editor_sky_sunset_glow,
        ProceduralSkyPreset.BLUE_HOUR to R.string.editor_sky_blue_hour,
        ProceduralSkyPreset.WARM_DUSK to R.string.editor_sky_warm_dusk
    )

    private data class DialBinding(
        val row: LabeledDialRow,
        val read: (SkyOps) -> Float
    ) {
        fun refresh(sky: SkyOps) = row.setFloatValue(read(sky))
    }
}
