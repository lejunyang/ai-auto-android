package dev.aiauto.android.bridge.lan

/**
 * 测试用途：在 N31 disposable emulator 上通过真实 TCP socket 验证 Go/Kotlin LAN RPC
 * 互操作；只接受公开 invitation，不输出 token、密钥或加密 frame。
 */

import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.bridge.AndroidBridgeMethodsFactory
import dev.aiauto.android.testcontrol.InstrumentationTestIdentityProvider
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanCrossRuntimeDeviceTest {
    @Test
    fun productionKotlinSessionInteroperatesWithGoHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val targetContext = instrumentation.targetContext
        val identity = InstrumentationTestIdentityProvider(
            targetContext = targetContext,
            instrumentationContext = instrumentation.context,
            arguments = arguments,
        ).current()
        assertTrue(identity.isEmulator)
        assertEquals(requiredArgument(arguments, ARGUMENT_EXPECTED_API).toInt(), Build.VERSION.SDK_INT)

        val invitationBytes = decodeInvitation(
            requiredArgument(arguments, ARGUMENT_INVITATION),
        )
        val invitation = try {
            LanInvitationParser.parse(invitationBytes.decodeToString())
        } finally {
            invitationBytes.fill(0)
        }
        val scenario = requiredArgument(arguments, ARGUMENT_SCENARIO)
        assertTrue(scenario in SCENARIOS)
        val candidate = invitation.addressCandidates.single()
        val directory = AndroidLanNetworkDirectory.from(targetContext)
        val localInterface = directory.interfaces().singleOrNull()
            ?: throw AssertionError("N31 emulator must expose exactly one eligible LAN interface")
        val connector = AndroidLanSocketConnector(directory)
        /*
         * N31 clean snapshot 会恢复创建快照时的 wall clock；本测试只替换已有可注入时钟，
         * 不放宽生产 TTL，也不修改握手、socket、密钥或 frame 实现。
         */
        val acceptanceTime = invitation.issuedAt.plusSeconds(1)
        assertTrue(acceptanceTime < invitation.expiresAt)
        val session = LanOutboundCoordinator(
            socketConnector = connector,
            replayStore = SingleUseReplayStore(),
            networkIdentity = LanNetworkIdentity { directory.current(localInterface) },
            clock = LanClock { acceptanceTime },
        ).connect(
            LanConnectRequest(
                invitation = invitation,
                selectedCandidate = candidate,
                selectedLocalInterface = localInterface,
                confirmedFingerprint = invitation.fingerprint,
                requestedCapabilities = invitation.capabilities,
            ),
        )
        try {
            session.serve(AndroidBridgeMethodsFactory.create(targetContext))
            assertFalse(session.canSubmitActions)
            assertEquals("LAN_SESSION_REMOTE_CLOSED", session.stopCode)
        } finally {
            session.close()
            connector.close()
        }
    }

    private fun requiredArgument(arguments: android.os.Bundle, name: String): String =
        checkNotNull(arguments.getString(name)?.takeIf(String::isNotBlank)) {
            "Missing required LAN interoperability argument: $name"
        }

    private fun decodeInvitation(encoded: String): ByteArray {
        require(encoded.length in 64..MAX_INVITATION_ARGUMENT_CHARS)
        require(BASE64_URL.matches(encoded))
        return try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw AssertionError("LAN invitation argument is not canonical base64url", error)
        }.also { decoded ->
            require(decoded.size in 1..MAX_INVITATION_BYTES)
            require(
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded,
            )
        }
    }

    private class SingleUseReplayStore : LanReplayStore {
        private var claimed = false

        @Synchronized
        override fun claim(key: LanReplayKey, now: Instant): LanReplayClaim {
            if (claimed) return LanReplayClaim.INVITATION_REPLAYED
            claimed = true
            return LanReplayClaim.CLAIMED
        }
    }

    private companion object {
        const val ARGUMENT_INVITATION = "lanInteropInvitation"
        const val ARGUMENT_SCENARIO = "lanInteropScenario"
        const val ARGUMENT_EXPECTED_API = "lanInteropExpectedApi"
        const val MAX_INVITATION_ARGUMENT_CHARS = 32_768
        const val MAX_INVITATION_BYTES = 24_576
        val BASE64_URL = Regex("^[A-Za-z0-9_-]+$")
        val SCENARIOS = setOf("multi-rpc-close", "auth-invalid")
    }
}
