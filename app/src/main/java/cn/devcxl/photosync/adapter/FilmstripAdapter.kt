package cn.devcxl.photosync.adapter

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Determines whether the filmstrip should proactively enqueue thumbnail decoding for an item
 * when no cached bitmap is available yet.
 *
 * RAW files are only decoded on demand when the user visits their page so filmstrip scrolling
 * never spawns background LibRaw bursts.
 */
internal fun shouldRequestFilmstripThumbnail(
    isRaw: Boolean,
    hasEditPreview: Boolean = false
): Boolean = !isRaw || hasEditPreview

/**
 * Horizontal thumbnail filmstrip adapter for quick navigation across synced photos.
 */
class FilmstripAdapter(
    private val thumbnailProvider: (String) -> Bitmap?,
    private val onThumbnailRequest: (String) -> Unit,
    private val onItemClick: (Int) -> Unit,
    private val hasEditPreviewProvider: (Long) -> Boolean = { false }
) : RecyclerView.Adapter<FilmstripAdapter.VH>() {

    companion object {
        private val PAYLOAD_SELECTION = Any()
        private val PAYLOAD_THUMBNAIL = Any()
        private val PAYLOAD_EDITED = Any()
    }

    private val items = mutableListOf<PhotoEntity>()
    private var selectedPosition: Int = RecyclerView.NO_POSITION
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val entity = items[position]
        return if (entity.id != 0L) entity.id else entity.path.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filmstrip_thumb, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = items[position]
        val isSelected = position == selectedPosition
        val isRaw = ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(entity.path))
        val bitmap = thumbnailProvider(entity.path)
        val hasEditPreview = hasEditPreviewProvider(entity.id)
        if (bitmap == null && shouldRequestFilmstripThumbnail(isRaw, hasEditPreview)) {
            onThumbnailRequest(entity.path)
        }
        holder.bind(
            bitmap = bitmap,
            isRaw = isRaw,
            isEdited = hasEditPreview,
            isExported = !entity.uriString.isNullOrBlank(),
            isSelected = isSelected
        ) {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onItemClick(adapterPos)
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val entity = items[position]
        if (payloads.contains(PAYLOAD_THUMBNAIL)) {
            holder.bindBitmap(thumbnailProvider(entity.path))
        }
        if (payloads.contains(PAYLOAD_EDITED)) {
            holder.bindEdited(hasEditPreviewProvider(entity.id))
        }
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(position == selectedPosition)
        }
    }

    /**
     * Replaces the displayed photo list using incremental diffing.
     */
    fun updateItems(newItems: List<PhotoEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
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

    /**
     * Updates the highlighted selection frame without rebinding unrelated items.
     */
    fun updateSelectedPosition(position: Int) {
        if (selectedPosition == position) return
        val previous = selectedPosition
        selectedPosition = position
        if (previous in items.indices) {
            mainHandler.post { notifyItemChanged(previous, PAYLOAD_SELECTION) }
        }
        if (position in items.indices) {
            mainHandler.post { notifyItemChanged(position, PAYLOAD_SELECTION) }
        }
    }

    /**
     * Refreshes the thumbnail image for [path] once it finishes decoding into cache.
     */
    fun notifyPathChanged(path: String) {
        val idx = items.indexOfFirst { it.path == path }
        if (idx >= 0) {
            mainHandler.post {
                notifyItemChanged(idx, PAYLOAD_THUMBNAIL)
                notifyItemChanged(idx, PAYLOAD_EDITED)
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbImage: ImageView = itemView.findViewById(R.id.thumbImage)
        private val thumbRawBadge: TextView = itemView.findViewById(R.id.thumbRawBadge)
        private val thumbEditedBadge: TextView = itemView.findViewById(R.id.thumbEditedBadge)
        private val thumbExportedBadge: TextView = itemView.findViewById(R.id.thumbExportedBadge)
        private val thumbBorderOverlay: View = itemView.findViewById(R.id.thumbBorderOverlay)

        fun bind(
            bitmap: Bitmap?,
            isRaw: Boolean,
            isEdited: Boolean,
            isExported: Boolean,
            isSelected: Boolean,
            onClick: () -> Unit
        ) {
            bindBitmap(bitmap)
            thumbRawBadge.visibility = if (isRaw) View.VISIBLE else View.GONE
            bindEdited(isEdited)
            thumbExportedBadge.visibility = if (isExported) View.VISIBLE else View.GONE
            bindSelection(isSelected)
            itemView.setOnClickListener { onClick() }
        }

        fun bindBitmap(bitmap: Bitmap?) {
            thumbImage.setImageBitmap(bitmap)
        }

        fun bindEdited(isEdited: Boolean) {
            thumbEditedBadge.visibility = if (isEdited) View.VISIBLE else View.GONE
        }

        fun bindSelection(isSelected: Boolean) {
            thumbImage.alpha = if (isSelected) 1.0f else 0.65f
            thumbBorderOverlay.setBackgroundResource(
                if (isSelected) R.drawable.bg_filmstrip_item_active else R.drawable.bg_filmstrip_item_normal
            )
        }
    }
}
