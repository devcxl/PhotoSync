package cn.devcxl.photosync.ptp.usbcamera

import android.widget.TextView
import java.io.PrintStream
import java.net.URLConnection

/**
 * @author devcxl
 */
open class ObjectInfo @JvmOverloads constructor(
    h: Int, f: NameFactory
) : Data(true, ByteArray(0), 0, f) {

    var storageId: Int = 0
    var objectFormatCode: Int = 0
    var protectionStatus: Int = 0
    var objectCompressedSize: Int = 0

    var thumbFormat: Int = 0
    var thumbCompressedSize: Int = 0
    var thumbPixWidth: Int = 0
    var thumbPixHeight: Int = 0

    var imagePixWidth: Int = 0
    var imagePixHeight: Int = 0
    var imageBitDepth: Int = 0
    var parentObject: Int = 0

    @JvmField var associationType: Int = 0
    var associationDesc: Int = 0
    var sequenceNumber: Int = 0
    @JvmField var filename: String? = null

    var captureDate: String? = null
    var modificationDate: String? = null
    var keywords: String? = null

    @JvmField var handle: Int = h

    constructor(conn: URLConnection, devInfo: DeviceInfo, f: NameFactory) : this(0, f) {
        `in` = false
        data = ByteArray(1024)
        val type = conn.contentType
        objectCompressedSize = conn.contentLength

        if (type.startsWith("image/")) {
            var error = false
            when {
                "image/jpeg" == type -> {
                    if (devInfo.supportsImageFormat(JFIF))
                        objectFormatCode = JFIF
                    else if (devInfo.supportsImageFormat(EXIF_JPEG))
                        objectFormatCode = EXIF_JPEG
                    else
                        error = true
                }
                "image/tiff" == type -> {
                    if (devInfo.supportsImageFormat(TIFF))
                        objectFormatCode = TIFF
                    else if (devInfo.supportsImageFormat(TIFF_EP))
                        objectFormatCode = TIFF_EP
                    else if (devInfo.supportsImageFormat(TIFF_IT))
                        objectFormatCode = TIFF_IT
                    else
                        error = true
                }
                else -> {
                    objectFormatCode = when (type) {
                        "image/gif" -> GIF
                        "image/png" -> PNG
                        "image/vnd.fpx" -> FlashPix
                        "image/x-MS-bmp" -> BMP
                        "image/x-photo-cd" -> PCD
                        else -> UnknownImage
                    }
                }
            }
            if (error || !devInfo.supportsImageFormat(objectFormatCode))
                throw IllegalArgumentException("device doesn't support $type")
        } else if ("text/html" == type)
            objectFormatCode = HTML
        else if ("text/plain" == type)
            objectFormatCode = Text
        else if ("audio/mp3" == type)
            objectFormatCode = MP3
        else if ("audio/x-aiff" == type)
            objectFormatCode = AIFF
        else if ("audio/x-wav" == type)
            objectFormatCode = WAV
        else if ("video/mpeg" == type)
            objectFormatCode = MPEG
        else
            objectFormatCode = Undefined

        marshal()
    }

    private fun marshal() {
        offset = Container.HDR_LEN
        put32(storageId)
        put16(objectFormatCode)
        put16(protectionStatus)
        put32(objectCompressedSize)
        put16(thumbFormat)
        put32(thumbCompressedSize)
        put32(thumbPixWidth)
        put32(thumbPixHeight)
        put32(imagePixWidth)
        put32(imagePixHeight)
        put32(imageBitDepth)
        put32(parentObject)
        put16(associationType)
        put32(associationDesc)
        put32(sequenceNumber)
        putString(filename)
        putString(captureDate)
        putString(modificationDate)
        putString(keywords)
        length = offset
        offset = 0
        val temp = ByteArray(length)
        System.arraycopy(data, 0, temp, 0, length)
        data = temp
    }

    override fun getLength(): Int = if (`in`) super.getLength() else data.size

    override fun parse() {
        super.parse()
        storageId = nextS32()
        objectFormatCode = nextU16()
        protectionStatus = nextU16()
        objectCompressedSize = nextS32()
        thumbFormat = nextU16()
        thumbCompressedSize = nextS32()
        thumbPixWidth = nextS32()
        thumbPixHeight = nextS32()
        imagePixWidth = nextS32()
        imagePixHeight = nextS32()
        imageBitDepth = nextS32()
        parentObject = nextS32()
        associationType = nextU16()
        associationDesc = nextS32()
        sequenceNumber = nextS32()
        filename = nextString()
        captureDate = nextString()
        modificationDate = nextString()
        keywords = nextString()
    }

    internal fun line(out: PrintStream) {
        if (filename != null) {
            out.print(filename)
            out.print("; ")
        }
        if (objectFormatCode == Association) {
            val associationString = associationString(associationType)
            if (associationString != null)
                out.print(associationString)
        } else {
            out.print(objectCompressedSize)
            out.print(" bytes, ")
            out.print(factory.getFormatString(objectFormatCode))
            if (thumbFormat != 0) {
                if (imagePixWidth != 0 && imagePixHeight != 0) {
                    out.print(" ")
                    out.print(imagePixWidth)
                    out.print("x")
                    out.print(imagePixHeight)
                }
                if (imageBitDepth != 0) {
                    out.print(", ")
                    out.print(imageBitDepth)
                    out.print(" bits")
                }
            }
        }
        out.println()
    }

    internal fun line(tv: TextView) {
        if (filename != null) {
            tv.text = filename
            tv.append("; ")
        }
        if (objectFormatCode == Association) {
            val associationString = associationString(associationType)
            if (associationString != null)
                tv.append(associationString)
        } else {
            tv.append(objectCompressedSize.toString())
            tv.append(" bytes, ")
            tv.append(factory.getFormatString(objectFormatCode))
            if (thumbFormat != 0) {
                if (imagePixWidth != 0 && imagePixHeight != 0) {
                    tv.append(" ")
                    tv.append(imagePixWidth.toString())
                    tv.append("x")
                    tv.append(imagePixHeight.toString())
                }
                if (imageBitDepth != 0) {
                    tv.append(", ")
                    tv.append(imageBitDepth.toString())
                    tv.append(" bits")
                }
            }
        }
        tv.append("\n")
    }

    override fun dump(out: PrintStream) {
        super.dump(out)
        out.println("ObjectInfo:")
        if (storageId != 0) {
            out.print("StorageID: 0x")
            out.print(Integer.toHexString(storageId))
            when (protectionStatus) {
                0 -> out.println(", unprotected")
                1 -> out.println(", read-only")
                else -> {
                    out.print(", reserved protectionStatus 0x")
                    out.println(Integer.toHexString(protectionStatus))
                }
            }
        }
        if (parentObject != 0)
            out.println("Parent: 0x" + Integer.toHexString(parentObject))
        if (filename != null)
            out.println("Filename $filename")
        if (sequenceNumber != 0) {
            out.print("Sequence = ")
            out.print(sequenceNumber)
        }
        if (thumbFormat != 0) {
            out.print("Image format: ")
            out.print(factory.getFormatString(objectFormatCode))
            out.print(", size ")
            out.print(objectCompressedSize)
            out.print(", width ")
            out.print(imagePixWidth)
            out.print(", height ")
            out.print(imagePixHeight)
            out.print(", depth ")
            out.println(imageBitDepth)
            out.print("Thumbnail format: ")
            out.print(factory.getFormatString(thumbFormat))
            out.print(", size ")
            out.print(thumbCompressedSize)
            out.print(", width ")
            out.print(thumbPixWidth)
            out.print(", height ")
            out.print(thumbPixHeight)
            out.print(", depth ")
            out.println(imageBitDepth)
        } else {
            out.print("Object format: ")
            out.print(factory.getFormatString(objectFormatCode))
            out.print(", size ")
            out.println(objectCompressedSize)
            if (objectFormatCode == Association) {
                val associationString = associationString(associationType)
                if (associationString != null) {
                    out.print("Association type: ")
                    out.print(associationString)
                    if (associationDesc != 0) {
                        out.print(", desc 0x")
                        out.print(Integer.toHexString(associationDesc))
                    }
                    out.println()
                }
            }
        }
        if (captureDate != null)
            out.println("capture date: $captureDate")
        if (modificationDate != null)
            out.println("modification date: $modificationDate")
        if (keywords != null)
            out.println("keywords: $keywords")
    }

    internal fun showInTextView(tv: TextView) {
        tv.text = "ObjectInfo: \n"
        if (storageId != 0) {
            tv.append("StorageID: 0x")
            tv.append(Integer.toHexString(storageId))
            when (protectionStatus) {
                0 -> tv.append(", unprotected \n")
                1 -> tv.append(", read-only \n")
                else -> {
                    tv.append(", reserved protectionStatus 0x")
                    tv.append(Integer.toHexString(protectionStatus).toString() + "\n")
                }
            }
        }
        if (parentObject != 0)
            tv.append("Parent: 0x" + Integer.toHexString(parentObject) + "\n")
        if (filename != null)
            tv.append("Filename $filename\n")
        if (sequenceNumber != 0) {
            tv.append("Sequence = ")
            tv.append(sequenceNumber.toString())
        }
        if (thumbFormat != 0) {
            tv.append("Image format: ")
            tv.append(factory.getFormatString(objectFormatCode))
            tv.append(", size ")
            tv.append(objectCompressedSize.toString())
            tv.append(", width ")
            tv.append(imagePixWidth.toString())
            tv.append(", height ")
            tv.append(imagePixHeight.toString())
            tv.append(", depth ")
            tv.append("$imageBitDepth\n")
            tv.append("Thumbnail format: ")
            tv.append(factory.getFormatString(thumbFormat))
            tv.append(", size ")
            tv.append(thumbCompressedSize.toString())
            tv.append(", width ")
            tv.append(thumbPixWidth.toString())
            tv.append(", height ")
            tv.append(thumbPixHeight.toString())
            tv.append(", depth ")
            tv.append("$imageBitDepth\n")
        } else {
            tv.append("Object format: ")
            tv.append(factory.getFormatString(objectFormatCode))
            tv.append(", size ")
            tv.append("$objectCompressedSize\n")
            if (objectFormatCode == Association) {
                val associationString = associationString(associationType)
                if (associationString != null) {
                    tv.append("Association type: ")
                    tv.append(associationString)
                    if (associationDesc != 0) {
                        tv.append(", desc 0x")
                        tv.append(Integer.toHexString(associationDesc))
                    }
                    tv.append("\n")
                }
            }
        }
        if (captureDate != null)
            tv.append("capture date: $captureDate\n")
        if (modificationDate != null)
            tv.append("modification date: $modificationDate\n")
        if (keywords != null)
            tv.append("keywords: $keywords\n")
    }

    fun isImage(): Boolean = (objectFormatCode and 0xf800) == 0x3800

    fun isVideo(): Boolean = when (objectFormatCode) {
        AVI, MPEG, ASF, QuickTime -> true
        else -> false
    }

    override fun getCodeName(code: Int): String = factory.getFormatString(code)

    internal fun associationString(associationType: Int): String? = when (associationType) {
        0 -> null
        1 -> "GenericFolder"
        2 -> "Album"
        3 -> "TimeSequence"
        4 -> "HorizontalPanorama"
        5 -> "VerticalPanorama"
        6 -> "2DPanorama"
        7 -> "AncillaryData"
        else -> {
            val retval = StringBuilder()
            if ((associationType and 0x8000) == 0)
                retval.append("Reserved-0x")
            else
                retval.append("Vendor-0x")
            retval.append(Integer.toHexString(associationType))
            retval.toString()
        }
    }

    companion object {
        @JvmField val Undefined: Int = 0x3000
        @JvmField val Association: Int = 0x3001
        @JvmField val Script: Int = 0x3002
        @JvmField val Executable: Int = 0x3003
        @JvmField val Text: Int = 0x3004
        @JvmField val HTML: Int = 0x3005
        @JvmField val DPOF: Int = 0x3006
        @JvmField val AIFF: Int = 0x3007
        @JvmField val WAV: Int = 0x3008
        @JvmField val MP3: Int = 0x3009
        @JvmField val AVI: Int = 0x300a
        @JvmField val MPEG: Int = 0x300b
        @JvmField val ASF: Int = 0x300c
        @JvmField val QuickTime: Int = 0x300d
        @JvmField val UnknownImage: Int = 0x3800
        @JvmField val EXIF_JPEG: Int = 0x3801
        @JvmField val TIFF_EP: Int = 0x3802
        @JvmField val FlashPix: Int = 0x3803
        @JvmField val BMP: Int = 0x3804
        @JvmField val CIFF: Int = 0x3805
        @JvmField val GIF: Int = 0x3807
        @JvmField val JFIF: Int = 0x3808
        @JvmField val PCD: Int = 0x3809
        @JvmField val PICT: Int = 0x380a
        @JvmField val PNG: Int = 0x380b
        @JvmField val TIFF: Int = 0x380d
        @JvmField val TIFF_IT: Int = 0x380e
        @JvmField val JP2: Int = 0x380f
        @JvmField val JPX: Int = 0x3810

        @JvmStatic
        fun _getFormatString(code: Int): String = when (code) {
            Undefined -> "UnknownFormat"
            Association -> "Association"
            Script -> "Script"
            Executable -> "Executable"
            Text -> "Text"
            HTML -> "HTML"
            DPOF -> "DPOF"
            AIFF -> "AIFF"
            WAV -> "WAV"
            MP3 -> "MP3"
            AVI -> "AVI"
            MPEG -> "MPEG"
            ASF -> "ASF"
            QuickTime -> "QuickTime"
            UnknownImage -> "UnknownImage"
            EXIF_JPEG -> "EXIF/JPEG"
            TIFF_EP -> "TIFF/EP"
            FlashPix -> "FlashPix"
            BMP -> "BMP"
            CIFF -> "CIFF"
            GIF -> "GIF"
            JFIF -> "JFIF"
            PCD -> "PCD"
            PICT -> "PICT"
            PNG -> "PNG"
            TIFF -> "TIFF"
            TIFF_IT -> "TIFF/IT"
            JP2 -> "JP2"
            JPX -> "JPX"
            else -> Container.getCodeString(code)
        }
    }
}
