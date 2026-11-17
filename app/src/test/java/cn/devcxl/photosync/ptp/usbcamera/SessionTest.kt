package cn.devcxl.photosync.ptp.usbcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    @Test
    fun shouldIncrementSessionIdAndKeepXidZero_whenInactive() {
        val session = Session()

        assertFalse(session.isActive())
        assertEquals(0, session.nextXID)
        assertEquals(1, session.nextSessionID)
        assertEquals(1, session.getSessionId())
    }

    @Test
    fun shouldAdvanceXidStartingFromOne_whenOpened() {
        val session = Session()
        session.nextSessionID
        session.open()

        assertTrue(session.isActive())
        assertEquals(1, session.nextXID)
        assertEquals(2, session.nextXID)
    }

    @Test
    fun shouldResetCountersAndPreserveFactory_whenResetCalled() {
        val factory = NameFactory()
        val session = Session()
        session.setFactory(factory)
        session.nextSessionID
        session.open()
        session.nextXID

        session.reset()

        assertFalse(session.isActive())
        assertEquals(0, session.getSessionId())
        assertEquals(0, session.nextXID)
        assertEquals(1, session.nextSessionID)
        assertSame(factory, session.factory)
    }
}
