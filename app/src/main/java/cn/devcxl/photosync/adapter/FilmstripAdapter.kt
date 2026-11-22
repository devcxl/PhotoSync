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
 *
 * Two interaction modes share the same rows: plain paging (tap scrolls the pager) and multi-select
 * (long-press enters it, tap toggles a photo). Selection is expressed as a set of photo ids so the
 * caller — not the adapter — owns the batch it will export.
 */
class FilmstripAdapter(
    private val thumbnailProvider: (String) -> Bitmap?,
    private val onThumbnailRequest: (String) -> Unit,
    private val onItemClick: (Int) -> Unit,
    private val hasEditPreviewProvider: (Long) -> Boolean = { false },
    private val onSelectionChanged: (Set<Long>) -> Unit = {}
) : RecyclerView.Adapter<FilmstripAdapter.VH>() {

    companion object {
        private val PAYLOAD_SELECTION = Any()
        private val PAYLOAD_THUMBNAIL = Any()
        private val PAYLOAD_EDITED = Any()
    }

    private val items = mutableListOf<PhotoEntity>()
    private var selectedPosition: Int = RecyclerView.NO_POSITION
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the user is picking photos for a batch action. */
    var selectionMode: Boolean = false
        private set

    private val checkedPhotoIds = linkedSetOf<Long>()

    /** Selected photo ids; returns an immutable snapshot so mutations never race callers. */
    val selectedPhotoIds: Set<Long>
        get() = checkedPhotoIds.toSet()

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
            isSelected = !selectionMode && position == selectedPosition,
            isChecked = selectionMode && entity.id in checkedPhotoIds
        )
        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) onRowTapped(adapterPos)
        }
        holder.itemView.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) enterSelectionMode(adapterPos)
            true
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
            holder.bindSelection(!selectionMode && position == selectedPosition)
            holder.bindChecked(selectionMode && entity.id in checkedPhotoIds)
        }
    }

    /**
     * Replaces the displayed photo list using incremental diffing.
     *
     * Selected ids that no longer exist are dropped so a deletion during selection cannot leave a
     * phantom id in the batch.
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
        pruneSelection()
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
     * Leaves multi-select, drops the batch and notifies the host.
     */
    fun exitSelectionMode() {
        if (!selectionMode && selectedPhotoIds.isEmpty()) return
        selectionMode = false
        checkedPhotoIds.clear()
        notifySelectionChanged()
        rebindAllSelection()
    }

    /**
     * Enters multi-select with [photoId] already checked.
     *
     * Used when the quick strip's `多选` button starts the batch, so the first tap always yields a
     * non-empty selection instead of forcing the user to also tap the photo.
     */
    fun enterSelectionModeFor(photoId: Long) {
        selectionMode = true
        checkedPhotoIds.add(photoId)
        notifySelectionChanged()
        rebindAllSelection()
    }

    /** Drops the batch but stays in multi-select; used after a batch export was queued. */
    fun clearSelection() {
        if (selectedPhotoIds.isEmpty()) return
        checkedPhotoIds.clear()
        notifySelectionChanged()
        rebindAllSelection()
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

    private fun onRowTapped(position: Int) {
        if (selectionMode) {
            toggleSelection(items.getOrNull(position))
        } else if (position != RecyclerView.NO_POSITION) {
            onItemClick(position)
        }
    }

    private fun enterSelectionMode(position: Int) {
        val entity = items.getOrNull(position) ?: return
        selectionMode = true
        checkedPhotoIds.add(entity.id)
        notifySelectionChanged()
        rebindAllSelection()
    }

    private fun toggleSelection(entity: PhotoEntity?) {
        if (entity == null) return
        if (!checkedPhotoIds.add(entity.id)) {
            checkedPhotoIds.remove(entity.id)
        }
        notifySelectionChanged()
        val idx = items.indexOf(entity)
        if (idx >= 0) mainHandler.post { notifyItemChanged(idx, PAYLOAD_SELECTION) }
    }

    private fun notifySelectionChanged() {
        onSelectionChanged(selectedPhotoIds.toSet())
    }

    /**
     * Rebinds every row for its selection chrome.
     *
     * Entering or leaving multi-select changes how *all* rows look (paging highlight versus check
     * badge), so this is the one place a full invalidation is honest: a per-item diff would rebind
     * the very same range anyway.
     */
    private fun rebindAllSelection() {
        if (items.isEmpty()) return
        mainHandler.post { notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION) }
    }

    private fun pruneSelection() {
        val liveIds = items.mapTo(mutableSetOf()) { it.id }
        if (checkedPhotoIds.retainAll(liveIds)) {
            notifySelectionChanged()
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbImage: ImageView = itemView.findViewById(R.id.thumbImage)
        private val thumbRawBadge: TextView = itemView.findViewById(R.id.thumbRawBadge)
        private val thumbEditedBadge: TextView = itemView.findViewById(R.id.thumbEditedBadge)
        private val thumbExportedBadge: TextView = itemView.findViewById(R.id.thumbExportedBadge)
        private val thumbSelectionBadge: TextView = itemView.findViewById(R.id.thumbSelectionBadge)
        private val thumbBorderOverlay: View = itemView.findViewById(R.id.thumbBorderOverlay)

        fun bind(
            bitmap: Bitmap?,
            isRaw: Boolean,
            isEdited: Boolean,
            isExported: Boolean,
            isSelected: Boolean,
            isChecked: Boolean
        ) {
            bindBitmap(bitmap)
            thumbRawBadge.visibility = if (isRaw) View.VISIBLE else View.GONE
            bindEdited(isEdited)
            thumbExportedBadge.visibility = if (isExported) View.VISIBLE else View.GONE
            bindSelection(isSelected)
            bindChecked(isChecked)
        }

        fun bindBitmap(bitmap: Bitmap?) {
            thumbImage.setImageBitmap(bitmap)
        }

        fun bindEdited(isEdited: Boolean) {
            thumbEditedBadge.visibility = if (isEdited) View.VISIBLE else View.GONE
        }

        /** Paging-mode highlight: the photo the pager is showing. */
        fun bindSelection(isSelected: Boolean) {
            thumbImage.alpha = if (isSelected) 1.0f else 0.65f
            thumbBorderOverlay.setBackgroundResource(
                if (isSelected) R.drawable.bg_filmstrip_item_active else R.drawable.bg_filmstrip_item_normal
            )
        }

        /** Multi-select highlight: this photo is in the batch. */
        fun bindChecked(isChecked: Boolean) {
            thumbSelectionBadge.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                thumbImage.alpha = 1.0f
                thumbBorderOverlay.setBackgroundResource(R.drawable.bg_filmstrip_item_active)
            }
        }
    }
}
