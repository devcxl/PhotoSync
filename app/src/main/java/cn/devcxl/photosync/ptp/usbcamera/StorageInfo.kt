package cn.devcxl.photosync.ptp.usbcamera

import android.widget.TextView
import java.io.PrintStream

/**
 * @author devcxl
 */
open class StorageInfo(f: NameFactory) : Data(true, ByteArray(0), 0, f) {

    var storageType: Int = 0
    var filesystemType: Int = 0
    var accessCapability: Int = 0
    var maxCapacity: Long = 0

    var freeSpaceInBytes: Long = 0
    var freeSpaceInImages: Int = 0
    var storageDescription: String? = null
    var volumeLabel: String? = null

    override fun parse() {
        super.parse()
        storageType = nextU16()
        filesystemType = nextU16()
        accessCapability = nextU16()
        maxCapacity = nextS64()
        freeSpaceInBytes = nextS64()
        freeSpaceInImages = nextS32()
        storageDescription = nextString()
        volumeLabel = nextString()
    }

    internal fun line(out: PrintStream) {
        val temp: String = when (storageType) {
            0 -> "undefined"
            1 -> "Fixed ROM"
            2 -> "Removable ROM"
            3 -> "Fixed RAM"
            4 -> "Removable RAM"
            else -> "Reserved-0x" + Integer.toHexString(storageType)
        }
        out.println("Storage Type: $temp")
    }

    internal fun line(tv: TextView) {
        val temp: String = when (storageType) {
            0 -> "undefined"
            1 -> "Fixed ROM"
            2 -> "Removable ROM"
            3 -> "Fixed RAM"
            4 -> "Removable RAM"
            else -> "Reserved-0x" + Integer.toHexString(storageType)
        }
        tv.append("Storage Type: $temp \n")
    }

    override fun dump(out: PrintStream) {
        var temp: String
        super.dump(out)
        out.println("StorageInfo:")
        line(out)

        temp = when (filesystemType) {
            0 -> "undefined"
            1 -> "flat"
            2 -> "hierarchical"
            3 -> "dcf"
            else -> {
                val prefix = if ((filesystemType and 0x8000) != 0) "Reserved-0x" else "Vendor-0x"
                prefix + Integer.toHexString(filesystemType)
            }
        }
        out.println("Filesystem Type: $temp")

        if (maxCapacity != -1L)
            out.println("Capacity: $maxCapacity bytes (" + ((maxCapacity + 500000) / 1000000) + " MB)")

        if (freeSpaceInBytes != -1L)
            out.println("Free space: $freeSpaceInBytes bytes (" + ((freeSpaceInBytes + 500000) / 1000000) + " MB)")

        if (freeSpaceInImages != -1)
            out.println("Free space in Images: $freeSpaceInImages")

        if (storageDescription != null)
            out.println("Description: $storageDescription")
        if (volumeLabel != null)
            out.println("Volume Label: $volumeLabel")
    }

    internal fun showInTextView(tv: TextView) {
        tv.text = "StorageInfo:"
        tv.append("\n")
        line(tv)

        var temp: String = when (filesystemType) {
            0 -> "undefined"
            1 -> "flat"
            2 -> "hierarchical"
            3 -> "dcf"
            else -> {
                val prefix = if ((filesystemType and 0x8000) != 0) "Reserved-0x" else "Vendor-0x"
                prefix + Integer.toHexString(filesystemType)
            }
        }
        tv.append("\n")
        tv.append("Filesystem Type: $temp")

        if (maxCapacity != -1L) {
            tv.append("\n")
            tv.append("Capacity: $maxCapacity bytes (" + ((maxCapacity + 500000) / 1000000) + " MB)")
        }
        if (freeSpaceInBytes != -1L) {
            tv.append("\n")
            tv.append("Free space: $freeSpaceInBytes bytes (" + ((freeSpaceInBytes + 500000) / 1000000) + " MB)")
        }
        if (freeSpaceInImages != -1) {
            tv.append("\n")
            tv.append("Free space in Images: $freeSpaceInImages")
        }
        if (storageDescription != null) {
            tv.append("\n")
            tv.append("Description: $storageDescription")
        }
        if (volumeLabel != null) {
            tv.append("\n")
            tv.append("Volume Label: $volumeLabel")
        }
    }
}
