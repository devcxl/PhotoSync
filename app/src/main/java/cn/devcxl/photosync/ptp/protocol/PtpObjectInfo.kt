package cn.devcxl.photosync.ptp.protocol

/**
 * The `ObjectInfo` dataset (PIMA 15740 section 5.1.3) describing one object on the camera.
 *
 * Only the fields the sync and delete paths read are kept. Thumbnail geometry, image
 * dimensions and the date strings are parsed past rather than stored: nothing reads them,
 * and a field that is kept but never used is only another place to be wrong.
 *
 * @property handle the object handle this dataset describes
 * @property storageId storage the object lives in
 * @property formatCode object format code; [PtpFormat.ASSOCIATION] for folders
 * @property protectionStatus non-zero when the camera refuses to delete the object
 * @property sizeBytes uncompressed size in bytes, widened because it is unsigned
 * @property associationType kind of object a folder holds; 1 means "folder"
 * @property filename name on the camera; may be absent on vendor-defined objects
 *
 * Thumbnail geometry, capture and modification dates are skipped: nothing in the sync or
 * delete path reads them, and a dataset field that is parsed but never used is just another
 * place to be wrong.
 */
internal class PtpObjectInfo private constructor(
    val handle: Int,
    val storageId: Int,
    val formatCode: Int,
    val protectionStatus: Int,
    val sizeBytes: Long,
    val associationType: Int,
    val filename: String?,
) {
    val isFolder: Boolean
        get() = formatCode == PtpFormat.ASSOCIATION || associationType == ASSOCIATION_TYPE_FOLDER

    val isProtected: Boolean get() = protectionStatus != 0

    override fun toString(): String =
        "ObjectInfo(handle=$handle, name=$filename, size=$sizeBytes, folder=$isFolder)"

    companion object {
        /** `AssociationType` value for a folder. */
        const val ASSOCIATION_TYPE_FOLDER = 1

        fun parse(handle: Int, bytes: ByteArray): PtpObjectInfo {
            val reader = PtpByteReader(bytes)
            val storageId = reader.u32()
            val formatCode = reader.u16()
            val protectionStatus = reader.u16()
            val sizeBytes = reader.u32AsLong()
            reader.u16() // thumbnail format
            reader.u32() // thumbnail size
            reader.u32() // thumbnail width
            reader.u32() // thumbnail height
            reader.u32() // image width
            reader.u32() // image height
            reader.u32() // image bit depth
            reader.u32() // parent object
            val associationType = reader.u16()
            reader.u32() // association description
            reader.u32() // sequence number
            return PtpObjectInfo(
                handle = handle,
                storageId = storageId,
                formatCode = formatCode,
                protectionStatus = protectionStatus,
                sizeBytes = sizeBytes,
                associationType = associationType,
                filename = reader.string(),
            )
        }
    }
}
