package dev.aiauto.android.accessibility

/**
 * 测试用途：验证 SessionStopVerificationHarness 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.automation.session.AutomationSessionRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStopVerificationHarnessTest {
    @Test
    fun `debug planner gate follows planned action contract without visual context BitsUT`() {
        val source = debugHarnessSource()

        assertTrue(source.contains("CompletableDeferred<SessionPlannedAction>()"))
        assertTrue(source.contains("plannerGate.await()"))
        assertFalse(source.contains("CompletableDeferred<ProviderAction>()"))
        assertFalse(source.contains("SessionActionContext"))
    }

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

    private fun debugHarnessSource(): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.firstOrNull {
            it.resolve("src/debug/java/dev/aiauto/android/accessibility").isDirectory
        } ?: error("unable to locate app module root")
        return root.resolve(
            "src/debug/java/dev/aiauto/android/accessibility/SessionStopVerificationHarness.kt",
        ).readText()
    }
}
