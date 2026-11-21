package cn.devcxl.photosync.ptp.protocol

/**
 * The `StorageInfo` dataset (PIMA 15740 section 5.1.2) for one storage id.
 *
 * @property accessCapability bitfield; [READ_ONLY] is the bit that forbids host writes
 */
internal class PtpStorageInfo private constructor(
    val accessCapability: Int,
    val maxCapacityBytes: Long,
    val freeSpaceBytes: Long,
    val description: String?,
    val volumeLabel: String?,
) {
    val isReadOnly: Boolean get() = accessCapability and READ_ONLY != 0

    companion object {
        /** Set when the storage refuses writes from the host, deletes included. */
        const val READ_ONLY = 0x0001

        fun parse(bytes: ByteArray): PtpStorageInfo {
            val reader = PtpByteReader(bytes)
            reader.u16() // storage type: fixed or removable, no consumer
            reader.u16() // filesystem type: no consumer
            val accessCapability = reader.u16()
            val maxCapacityBytes = reader.u64()
            val freeSpaceBytes = reader.u64()
            reader.u32() // free space in images: no consumer
            return PtpStorageInfo(
                accessCapability = accessCapability,
                maxCapacityBytes = maxCapacityBytes,
                freeSpaceBytes = freeSpaceBytes,
                description = reader.string(),
                volumeLabel = reader.string(),
            )
        }
    }
}
