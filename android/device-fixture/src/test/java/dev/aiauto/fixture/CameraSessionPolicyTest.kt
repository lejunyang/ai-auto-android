package dev.aiauto.fixture

import org.junit.Assert.*
import org.junit.Test

/** 测试用途：验证断流最多三次退避重连、旧回调取消与严格4:3预览边界，不依赖设备相机或权限。 */
class CameraSessionPolicyTest {
    @Test fun retriesAreBoundedAcrossSuccessfulReopens() {
        val policy = CameraSessionPolicy()
        policy.resume()
        for (attempt in 1..3) {
            val token = requireNotNull(policy.beginOpen())
            assertTrue(policy.opened(token))
            val retry = requireNotNull(policy.failed(token, "DISCONNECTED"))
            assertEquals(attempt, retry.attempt)
            assertEquals(listOf(500L, 1000L, 2000L)[attempt - 1], retry.delayMs)
            assertFalse(policy.opened(token))
            assertNull(policy.failed(token, "late duplicate"))
            assertTrue(policy.retryReady(retry.generation))
        }
        val token = requireNotNull(policy.beginOpen())
        assertNull(policy.failed(token, "OPEN_3"))
        assertEquals(CameraSessionPolicy.Phase.EXHAUSTED, policy.phase)
        assertEquals(3, policy.retries)
        assertEquals("OPEN_3", policy.lastError)
        assertNull(policy.beginOpen())
    }
    @Test fun pauseCancelsPendingRetryAndLateCallbacks() {
        val policy = CameraSessionPolicy(); policy.resume()
        val token = requireNotNull(policy.beginOpen())
        val retry = requireNotNull(policy.failed(token, "CONFIGURE_FAILED"))
        policy.stop()
        assertFalse(policy.retryReady(retry.generation))
        assertFalse(policy.opened(token))
        assertNull(policy.beginOpen())
        policy.resume()
        assertEquals(0, policy.retries)
        assertNotEquals(token, policy.beginOpen())
    }
    @Test fun openingDoesNotDuplicateAndErrorsRemainBounded() {
        val policy = CameraSessionPolicy(); policy.resume()
        val token = requireNotNull(policy.beginOpen())
        assertNull(policy.beginOpen())
        policy.failed(token, "reason\n" + "x".repeat(600))
        assertTrue(requireNotNull(policy.lastError).length <= 256)
        assertFalse(requireNotNull(policy.lastError).contains('\n'))
    }
    @Test fun previewContainsFourByThreeWithoutStretching() {
        assertEquals(720 to 540, CameraPreviewSize.fit(720, 1100))
        assertEquals(640 to 480, CameraPreviewSize.fit(1280, 480))
        for ((w, h) in listOf(719 to 1001, 480 to 320, 4 to 3)) {
            val (actualW, actualH) = CameraPreviewSize.fit(w, h)
            assertEquals(actualW * 3, actualH * 4)
            assertTrue(actualW <= w && actualH <= h)
        }
        assertEquals(0 to 0, CameraPreviewSize.fit(0, 640))
        assertEquals(0 to 0, CameraPreviewSize.fit(-1, 0))
    }
}
