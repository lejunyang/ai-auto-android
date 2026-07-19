package dev.aiauto.android.accessibility

import dev.aiauto.android.automation.session.AutomationSessionRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStopVerificationHarnessTest {
    @Test
    fun `manual session lifetime is independent from assertion timeout BitsUT`() {
        assertEquals(
            120_000L,
            SessionStopVerificationHarness.MANUAL_SESSION_TIMEOUT_MS,
        )
        assertEquals(
            10_000L,
            SessionStopVerificationHarness.ASSERTION_TIMEOUT_MS,
        )
        assertTrue(
            SessionStopVerificationHarness.MANUAL_SESSION_TIMEOUT_MS >
                SessionStopVerificationHarness.ASSERTION_TIMEOUT_MS,
        )
    }

    @Test
    fun `user touch produces stable stopped status without executor submission BitsUT`() = runBlocking {
        val targetPackage = "dev.aiauto.android"
        val harness = SessionStopVerificationHarness(targetPackage)

        try {
            assertTrue(harness.start())
            assertTrue(
                harness.recordAutomationClick(
                    performed = true,
                    userTouchNotifications = 0,
                ),
            )

            assertTrue(AutomationSessionRuntime.notifyUserTouch(targetPackage))
            assertEquals(
                "SESSION_STOP_PASS phase=Stopped executorCalls=0",
                harness.verifyStopped(),
            )
            assertNull(AutomationSessionRuntime.activeSessionId(targetPackage))
        } finally {
            harness.close()
        }
    }
}
