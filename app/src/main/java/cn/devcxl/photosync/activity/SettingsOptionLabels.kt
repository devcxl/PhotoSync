package cn.devcxl.photosync.activity

import android.content.Context

import cn.devcxl.photosync.R
import cn.devcxl.photosync.editor.settings.AiIdleRelease
import cn.devcxl.photosync.editor.settings.AiTierOverride
import cn.devcxl.photosync.editor.settings.InpaintQualityMode

/** Localized one-line labels of the settings option enums, shared by values and pickers. */
internal object SettingsOptionLabels {

    /** Label of a device tier override. */
    fun tierOverride(context: Context, value: AiTierOverride): String = context.getString(
        when (value) {
            AiTierOverride.AUTO -> R.string.settings_value_auto
            AiTierOverride.LOW -> R.string.settings_value_low
            AiTierOverride.STANDARD -> R.string.settings_value_standard
        }
    )

    /** Label of an idle release policy; a null timeout means immediate release. */
    fun idleRelease(context: Context, value: AiIdleRelease): String {
        val timeoutMs = value.timeoutMs ?: return context.getString(R.string.settings_value_immediate)
        return context.getString(R.string.settings_value_seconds, (timeoutMs / 1_000L).toInt())
    }

    /** Label of an inpaint quality mode. */
    fun inpaintMode(context: Context, value: InpaintQualityMode): String = context.getString(
        when (value) {
            InpaintQualityMode.FINE -> R.string.settings_value_fine
            InpaintQualityMode.FAST -> R.string.settings_value_fast
        }
    )

    /** On/off label of a boolean switch value. */
    fun onOff(context: Context, value: Boolean): String =
        context.getString(if (value) R.string.settings_value_on else R.string.settings_value_off)
}
