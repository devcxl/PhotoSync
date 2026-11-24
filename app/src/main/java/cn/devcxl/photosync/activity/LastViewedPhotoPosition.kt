package cn.devcxl.photosync.activity

import android.content.SharedPreferences

/**
 * Remembers which photo the gallery was showing so the next cold start reopens on it.
 *
 * The photo **id** is stored instead of the pager index: a swipe only changes the index, but imports
 * and deletions renumber the list between runs, and a stale index would then point at a different
 * photo.
 */
internal class LastViewedPhotoStore(private val preferences: SharedPreferences) {

    /** Records [photoId] as the photo the user is looking at. */
    fun save(photoId: Long) {
        if (photoId <= 0L) return
        preferences.edit().putLong(KEY_PHOTO_ID, photoId).apply()
    }

    /** The last recorded photo id, or `null` when nothing was recorded yet. */
    fun load(): Long? = preferences.getLong(KEY_PHOTO_ID, NO_PHOTO).takeIf { it != NO_PHOTO }

    /** Drops the record, e.g. once the remembered photo is gone. */
    fun clear() {
        preferences.edit().remove(KEY_PHOTO_ID).apply()
    }

    companion object {
        /** `SharedPreferences` key of the last viewed photo id. */
        const val KEY_PHOTO_ID = "last_viewed_photo_id"

        private const val NO_PHOTO = -1L
    }
}

/**
 * Resolves the pager position of [savedPhotoId] inside [photoIds].
 *
 * @return The index of the saved photo, or `null` when nothing was saved, the photo was deleted, or
 *   [savedPhotoId] is not part of the list; the caller then keeps the pager's default position.
 */
internal fun restoreIndexForPhoto(savedPhotoId: Long?, photoIds: List<Long>): Int? {
    if (savedPhotoId == null) return null
    val index = photoIds.indexOf(savedPhotoId)
    return index.takeIf { it >= 0 }
}
