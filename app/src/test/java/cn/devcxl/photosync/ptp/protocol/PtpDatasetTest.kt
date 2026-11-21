package cn.devcxl.photosync.ptp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpDatasetTest {

    @Test
    fun shouldReadDeviceInfoTablesAndStrings() {
        val dataset = datasetWriter()
            .u16(100)                                   // standard version
            .u32(0x0000_b000.toInt())                   // vendor extension id
            .u16(100)                                   // vendor extension version
            .ptpString("microsoft.com: 1.0")
            .u16(0)                                     // functional mode
            .u16Array(intArrayOf(PtpOp.OPEN_SESSION, PtpOp.DELETE_OBJECT))
            .u16Array(intArrayOf(PtpEvent.OBJECT_ADDED))
            .u16Array(intArrayOf(0x5001))
            .u16Array(intArrayOf(0x3801))
            .u16Array(intArrayOf(0x3801))
            .ptpString("Canon")
            .ptpString("EOS R6")
            .ptpString("1.0.0")
            .ptpString("0123456789")
            .build()

        val info = PtpDeviceInfo.parse(dataset)

        assertEquals(0x0000_b000, info.vendorExtensionId)
        assertTrue(info.supportsOperation(PtpOp.DELETE_OBJECT))
        assertFalse(info.supportsOperation(PtpOp.GET_STORAGE_IDS))
        assertEquals(setOf(PtpEvent.OBJECT_ADDED), info.eventsSupported)
        assertEquals("Canon", info.manufacturer)
        assertEquals("EOS R6", info.model)
        assertEquals("1.0.0", info.deviceVersion)
    }

    @Test
    fun shouldTreatEmptyTrailingStrings_asAbsent() {
        val dataset = datasetWriter()
            .u16(100).u32(0).u16(0).ptpString(null).u16(0)
            .u16Array(intArrayOf()).u16Array(intArrayOf())
            .u16Array(intArrayOf()).u16Array(intArrayOf()).u16Array(intArrayOf())
            .ptpString("Nikon")
            .ptpString(null)
            .ptpString(null)
            .ptpString(null)
            .build()

        val info = PtpDeviceInfo.parse(dataset)

        assertEquals("Nikon", info.manufacturer)
        assertNull(info.model)
        assertNull(info.deviceVersion)
    }

    @Test
    fun shouldReadObjectInfoFields() {
        val dataset = datasetWriter()
            .u32(0x0001_0001)                           // storage id
            .u16(0x3801)                                // format: JPEG
            .u16(0)                                     // protection status
            .u32(9_994_240)                             // object size
            .u16(0x3801).u32(16_384).u32(160).u32(120)  // thumbnail
            .u32(6000).u32(4000).u32(24)                // image geometry
            .u32(0x4000)                                // parent handle
            .u16(0)                                     // association type
            .u32(0).u32(1)                              // description, sequence
            .ptpString("IMG_0550.JPG")
            .ptpString("20260101T101010")
            .ptpString(null)
            .build()

        val info = PtpObjectInfo.parse(0x42, dataset)

        assertEquals(0x42, info.handle)
        assertEquals(0x0001_0001, info.storageId)
        assertEquals(9_994_240L, info.sizeBytes)
        assertEquals("IMG_0550.JPG", info.filename)
        assertFalse(info.isFolder)
        assertFalse(info.isProtected)
    }

    @Test
    fun shouldReportFolder_whenFormatIsAssociation() {
        val dataset = datasetWriter()
            .u32(0x0001_0001).u16(PtpFormat.ASSOCIATION).u16(0).u32(0)
            .u16(0).u32(0).u32(0).u32(0)
            .u32(0).u32(0).u32(0)
            .u32(0).u16(PtpObjectInfo.ASSOCIATION_TYPE_FOLDER).u32(0).u32(0)
            .ptpString("DCIM").ptpString(null).ptpString(null)
            .build()

        assertTrue(PtpObjectInfo.parse(1, dataset).isFolder)
    }

    @Test
    fun shouldReportProtection_whenStatusIsNonZero() {
        val dataset = datasetWriter()
            .u32(1).u16(0x3801).u16(1).u32(100)
            .u16(0).u32(0).u32(0).u32(0)
            .u32(0).u32(0).u32(0)
            .u32(0).u16(0).u32(0).u32(0)
            .ptpString("A.JPG").ptpString(null).ptpString(null)
            .build()

        assertTrue(PtpObjectInfo.parse(1, dataset).isProtected)
    }

    @Test
    fun shouldReadStorageInfo_andFlagReadOnly() {
        val writable = datasetWriter()
            .u16(4).u16(2).u16(0)
            .u64(64L * 1024 * 1024 * 1024)
            .u64(32L * 1024 * 1024 * 1024)
            .u32(1200)
            .ptpString("SD card")
            .ptpString("EOS_DIGITAL")
            .build()

        val readOnly = datasetWriter()
            .u16(4).u16(2).u16(PtpStorageInfo.READ_ONLY)
            .u64(0).u64(0).u32(0)
            .ptpString(null).ptpString(null)
            .build()

        val info = PtpStorageInfo.parse(writable)
        assertEquals(64L * 1024 * 1024 * 1024, info.maxCapacityBytes)
        assertEquals(32L * 1024 * 1024 * 1024, info.freeSpaceBytes)
        assertEquals("EOS_DIGITAL", info.volumeLabel)
        assertFalse(info.isReadOnly)
        assertTrue(PtpStorageInfo.parse(readOnly).isReadOnly)
    }

    private fun datasetWriter() = DatasetWriter()

    /** Test-only builder mirroring how a camera lays out a dataset. */
    private class DatasetWriter {
        private val bytes = java.io.ByteArrayOutputStream()

        fun u16(value: Int): DatasetWriter {
            bytes.write(value and 0xff)
            bytes.write((value shr 8) and 0xff)
            return this
        }

        fun u32(value: Int): DatasetWriter {
            u16(value)
            u16(value shr 16)
            return this
        }

        fun u64(value: Long): DatasetWriter {
            u32(value.toInt())
            u32((value shr 32).toInt())
            return this
        }

        fun u16Array(values: IntArray): DatasetWriter {
            u32(values.size)
            values.forEach { u16(it) }
            return this
        }

        fun ptpString(value: String?): DatasetWriter {
            if (value == null) {
                bytes.write(0)
                return this
            }
            bytes.write(value.length + 1)
            bytes.write(value.toByteArray(Charsets.UTF_16LE))
            bytes.write(0)
            bytes.write(0)
            return this
        }

        fun build(): ByteArray = bytes.toByteArray()
    }
}
