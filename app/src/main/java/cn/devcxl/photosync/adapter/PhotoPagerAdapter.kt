package cn.devcxl.photosync.adapter

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import cn.devcxl.photosync.activity.PhotoItem
import com.github.chrisbanes.photoview.PhotoView

class PhotoPagerAdapter(
    private val items: List<PhotoItem>,
    private val onBindRequest: (PhotoItem) -> Unit
) : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {

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
        val item = items[position]
        holder.bind(item)
        if (item.bitmap == null) {
            onBindRequest(item)
        }
    }

    class VH(private val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
        fun bind(item: PhotoItem) {
            if (item.bitmap != null) {
                photoView.setImageBitmap(item.bitmap)
            } else {
                photoView.setImageDrawable(null)
                photoView.scale = 1.0f
            }
        }
    }
}