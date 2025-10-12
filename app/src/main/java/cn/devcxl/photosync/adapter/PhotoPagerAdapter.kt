package cn.devcxl.photosync.adapter

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import cn.devcxl.photosync.data.entity.PhotoEntity
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

class PhotoPagerAdapter(
    private val bitmapProvider: (String) -> Bitmap?,
    private val onBindRequest: (String) -> Unit
) : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {

    private val items = mutableListOf<PhotoEntity>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val photoView = PhotoView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // default: zoom enabled
            maximumScale = 5.0f
            minimumScale = 1.0f
        }
        return VH(photoView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = items[position]
        holder.bind(entity, bitmapProvider)
        // 仅对RAW触发后台解码请求；JPEG直接由PhotoView通过URI显示
        val path = entity.path
        val isJpeg = isJpegPath(path)
        if (bitmapProvider(path) == null && !isJpeg) {
            onBindRequest(path)
        }
    }

    fun updateItems(newItems: List<PhotoEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].path == newItems[newItemPosition].path
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = items[oldItemPosition]
                val n = newItems[newItemPosition]
                return o.name == n.name && o.uriString == n.uriString && o.createdAt == n.createdAt
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun getItem(position: Int): PhotoEntity? = items.getOrNull(position)

    fun indexOfPath(path: String): Int = items.indexOfFirst { it.path == path }

    fun notifyPathChanged(path: String) {
        val idx = indexOfPath(path)
        if (idx >= 0) notifyItemChanged(idx)
    }

    class VH(private val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
        fun bind(entity: PhotoEntity, bitmapProvider: (String) -> Bitmap?) {
            val path = entity.path
            val cached = bitmapProvider(path)
            when {
                cached != null -> {
                    photoView.setImageBitmap(cached)
                }
                // 优先使用传入的uriString
                !entity.uriString.isNullOrEmpty() -> {
                    photoView.setImageURI(Uri.parse(entity.uriString))
                }
                // JPEG：直接使用文件URI展示
                isJpegPath(path) -> {
                    val uri = Uri.fromFile(File(path))
                    photoView.setImageURI(uri)
                }
                else -> {
                    photoView.setImageDrawable(null)
                    photoView.scale = 1.0f
                }
            }
        }
    }
}

// 简单判断是否为JPEG后缀（忽略大小写）
private fun isJpegPath(path: String?): Boolean {
    if (path.isNullOrEmpty()) return false
    val lower = path.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
}
