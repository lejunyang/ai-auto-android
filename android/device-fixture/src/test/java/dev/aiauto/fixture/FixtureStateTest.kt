package dev.aiauto.fixture

/**
 * 测试用途：验证原生 Fixture 的全部进程内动作状态和复位边界，避免 UI 场景之间互相污染。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureStateTest {
    @Test
    fun actionsExposeIndependentMachineReadableState() {
        val state = FixtureState()

        state.recordInput("fixture input")
        state.recordClick()
        state.recordLongPress()
        state.recordVerticalScroll()
        state.recordHorizontalSwipe()
        state.recordDialogDismissed()

        assertEquals("fixture input", state.inputValue)
        assertEquals(1, state.clickCount)
        assertEquals(1, state.longPressCount)
        assertEquals(1, state.verticalScrollCount)
        assertEquals(1, state.horizontalSwipeCount)
        assertTrue(state.dialogDismissed)
    }

    @Test
    fun foregroundHandshakeDistinguishesSystemReturnFromInitialLaunch() {
        val state = FixtureState()

        state.recordExternalNavigation("HOME")
        assertEquals("HOME", state.pendingExternalNavigation)
        assertFalse(state.hasReturnedFromSystem)

        state.recordForegroundReturn()
        assertEquals("HOME", state.pendingExternalNavigation)
        assertFalse(state.hasReturnedFromSystem)
        assertEquals("NONE", state.lastExternalNavigation)

        state.recordForegroundExit()
        state.recordForegroundReturn()
        assertEquals("NONE", state.pendingExternalNavigation)
        assertTrue(state.hasReturnedFromSystem)
        assertEquals("HOME", state.lastExternalNavigation)

        state.recordForegroundReturn()
        assertEquals("HOME", state.lastExternalNavigation)
    }

    @Test
    fun resetRestoresEveryMutableField() {
        val state = FixtureState().apply {
            recordInput("private transient value")
            recordClick()
            recordLongPress()
            recordVerticalScroll()
            recordHorizontalSwipe()
            recordDialogDismissed()
            recordExternalNavigation("RECENTS")
            recordForegroundExit()
            recordForegroundReturn()
        }

        state.reset()

        assertEquals("", state.inputValue)
        assertEquals(0, state.clickCount)
        assertEquals(0, state.longPressCount)
        assertEquals(0, state.verticalScrollCount)
        assertEquals(0, state.horizontalSwipeCount)
        assertFalse(state.dialogDismissed)
        assertFalse(state.hasReturnedFromSystem)
        assertEquals("NONE", state.lastExternalNavigation)
        assertEquals("NONE", state.pendingExternalNavigation)
    }
}
