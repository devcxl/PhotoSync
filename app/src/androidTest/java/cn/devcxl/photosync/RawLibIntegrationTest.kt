package cn.devcxl.photosync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import cn.devcxl.photosync.wrapper.RawWrapper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class RawLibIntegrationTest {
    @Test
    fun librawVersionNotBlank() {
        val version = RawWrapper.version()
        assertTrue("LibRaw version should not be blank", version.isNotBlank())
    }
}

