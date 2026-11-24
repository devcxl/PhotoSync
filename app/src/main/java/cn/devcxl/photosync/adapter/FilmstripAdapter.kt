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
        private const val PAYLOAD_THUMB = "payload_thumb"
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val PAYLOAD_EXPORTED = "payload_exported"
    }

    private val items = mutableListOf<PhotoEntity>()
    private val handler = Handler(Looper.getMainLooper())
    private var selectedPosition: Int = RecyclerView.NO_POSITION

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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = items[position]
        val isRaw = ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(entity.path))
        val hasEdit = hasEditPreviewProvider(entity.id)
        val thumb = thumbnailProvider(entity.path)
        if (thumb == null && shouldRequestFilmstripThumbnail(isRaw, hasEdit)) {
            onThumbnailRequest(entity.path)
        }
        holder.bind(
            bitmap = thumb,
            isRaw = isRaw,
            isEdited = hasEdit,
            isExported = !entity.uriString.isNullOrBlank(),
            isSelected = position == selectedPosition
        )
        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) onItemClick(adapterPos)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val entity = items[position]
        if (payloads.contains(PAYLOAD_THUMB)) {
            holder.bindThumb(thumbnailProvider(entity.path))
            holder.bindEdited(hasEditPreviewProvider(entity.id))
        }
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(position == selectedPosition)
        }
        if (payloads.contains(PAYLOAD_EXPORTED)) {
            holder.bindExported(!entity.uriString.isNullOrBlank())
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<PhotoEntity>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos].path == newItems[newPos].path

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos] == newItems[newPos]

            override fun getChangePayload(oldPos: Int, newPos: Int): Any? =
                if (oldItems[oldPos].uriString != newItems[newPos].uriString) {
                    PAYLOAD_EXPORTED
                } else {
                    null
                }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun updateSelectedPosition(position: Int) {
        if (selectedPosition == position) return
        val previous = selectedPosition
        selectedPosition = position
        if (previous in items.indices) {
            handler.post { notifyItemChanged(previous, PAYLOAD_SELECTION) }
        }
        if (position in items.indices) {
            handler.post { notifyItemChanged(position, PAYLOAD_SELECTION) }
        }
    }

    fun notifyPathChanged(path: String) {
        val idx = items.indexOfFirst { it.path == path }
        if (idx >= 0) {
            handler.post { notifyItemChanged(idx, PAYLOAD_THUMB) }
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
            isSelected: Boolean
        ) {
            bindThumb(bitmap)
            thumbRawBadge.visibility = if (isRaw) View.VISIBLE else View.GONE
            bindEdited(isEdited)
            bindExported(isExported)
            bindSelection(isSelected)
        }

        fun bindThumb(bitmap: Bitmap?) {
            if (bitmap != null) {
                thumbImage.setImageBitmap(bitmap)
                thumbImage.alpha = 1f
            } else {
                thumbImage.setImageDrawable(null)
                thumbImage.alpha = 0.4f
            }
        }

        fun bindEdited(isEdited: Boolean) {
            thumbEditedBadge.visibility = if (isEdited) View.VISIBLE else View.GONE
        }

        fun bindExported(isExported: Boolean) {
            thumbExportedBadge.visibility = if (isExported) View.VISIBLE else View.GONE
        }

        fun bindSelection(isSelected: Boolean) {
            thumbBorderOverlay.setBackgroundResource(
                if (isSelected) R.drawable.bg_filmstrip_item_active else R.drawable.bg_filmstrip_item_normal
            )
            itemView.alpha = if (isSelected) 1f else 0.72f
        }
    }
}
