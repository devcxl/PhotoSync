package cn.devcxl.photosync.activity

import android.view.LayoutInflater

import androidx.core.view.isVisible

import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.ActivityCloudSettingsBinding
import cn.devcxl.photosync.databinding.ItemCloudProfileBinding
import cn.devcxl.photosync.editor.cloud.CloudProviderKind
import cn.devcxl.photosync.editor.cloud.CloudProviderProfile

/**
 * Renders the selectable provider-profile list.
 *
 * The active profile carries a badge; tapping any row selects it, loads it into the form and makes
 * it the profile the editor will use.
 */
internal class CloudProfileListRenderer(
    private val binding: ActivityCloudSettingsBinding
) {

    /** Rebuilds the list; [kindLabel] localizes the provider family. */
    fun render(
        profiles: List<CloudProviderProfile>,
        activeId: String?,
        kindLabel: (CloudProviderKind) -> String,
        onSelect: (CloudProviderProfile) -> Unit
    ) {
        val context = binding.root.context
        val inflater = LayoutInflater.from(context)
        binding.cloudProfileList.removeAllViews()
        binding.cloudProfilesEmptyText.isVisible = profiles.isEmpty()
        for (profile in profiles) {
            val row = ItemCloudProfileBinding.inflate(inflater, binding.cloudProfileList, true)
            row.cloudProfileName.text = profile.displayName
            row.cloudProfileSummary.text = context.getString(
                R.string.cloud_profile_summary,
                kindLabel(profile.kind),
                profile.baseUrl
            )
            row.cloudProfileActiveBadge.isVisible = profile.profileId == activeId
            row.root.setOnClickListener { onSelect(profile) }
        }
    }
}
