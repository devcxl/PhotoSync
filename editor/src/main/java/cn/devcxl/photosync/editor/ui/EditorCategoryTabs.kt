package cn.devcxl.photosync.editor.ui

import android.content.Context
import android.view.View
import android.widget.Toast

import cn.devcxl.photosync.editor.R

/**
 * Placeholder wiring for the category tabs whose tool panels land in later tickets.
 */
internal object EditorCategoryTabs {

    fun bindComingSoon(context: Context, tabs: List<View>) {
        tabs.forEach { tab ->
            tab.setOnClickListener {
                Toast.makeText(context, R.string.editor_tool_coming_soon, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
