package dev.aiauto.android.automation.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSessionRuntimeTest {
    @Test
    fun `registry authorizes screenshots only for active target BitsUT`() {
        val registry = ActiveAutomationSessionRegistry()
        val registration = registry.register(
            targetPackage = TARGET_PACKAGE,
            screenshotsAllowed = true,
            stopHandler = UserTouchStopHandler {},
        )

        assertTrue(registry.isScreenshotAuthorized(TARGET_PACKAGE))
        assertFalse(registry.isScreenshotAuthorized("com.other.app"))

        registration.close()
        assertFalse(registry.isScreenshotAuthorized(TARGET_PACKAGE))
    }

    @Test
    fun `registry denies screenshots when session toggle is off BitsUT`() {
        val registry = ActiveAutomationSessionRegistry()
        registry.register(
            targetPackage = TARGET_PACKAGE,
            screenshotsAllowed = false,
            stopHandler = UserTouchStopHandler {},
        ).use {
            assertFalse(registry.isScreenshotAuthorized(TARGET_PACKAGE))
        }
    }

    @Test
    fun `registry ignores non target package touch BitsUT`() {
        var stops = 0
        val registry = ActiveAutomationSessionRegistry()
        registry.register(
            targetPackage = TARGET_PACKAGE,
            screenshotsAllowed = false,
            stopHandler = UserTouchStopHandler { stops += 1 },
        ).use {
            assertFalse(registry.notifyUserTouch("com.other.app"))
        }

        assertEquals(0, stops)
    }

    @Test
    fun `target touch stops immediately without time suppression BitsUT`() {
        var stops = 0
        val registry = ActiveAutomationSessionRegistry()
        registry.register(
            targetPackage = TARGET_PACKAGE,
            screenshotsAllowed = false,
            stopHandler = UserTouchStopHandler { stops += 1 },
        ).use {
            assertTrue(registry.notifyUserTouch(TARGET_PACKAGE))
        }

        assertEquals(1, stops)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
