package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证严格输入端口在界面状态之外持有一次性邀请，并在移交、失败或清理后不再
 * 暴露可复用的连接秘密。
 */

import java.io.File
import java.time.Instant

import dev.aiauto.android.bridge.lan.LanClock
import dev.aiauto.android.bridge.lan.LanLocalInterface
import dev.aiauto.android.bridge.lan.LanProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class StrictLanInvitationInputPortTest {
    @Test
    fun `validated invitation is transferred exactly once after explicit confirmation`() {
        val port = StrictLanInvitationInputPort(
            clock = LanClock { Instant.parse("2026-07-25T10:00:30Z") },
        )
        val summary = port.parse(InvitationInputSource.SCANNER, validPayload)
        val state = confirmedState(summary)

        val request = port.takeConnectRequest(state, REQUESTED_CAPABILITIES)
        assertEquals(summary.desktopFingerprint, request.confirmedFingerprint)
        assertEquals(summary.candidates.first(), request.selectedCandidate)
        assertFalse(request.invitation.destroyed)

        assertFailure("LAN_INVITATION_DESTROYED") {
            port.takeConnectRequest(state, REQUESTED_CAPABILITIES)
        }
        request.invitation.close()
    }

    @Test
    fun `clear destroys pending invitation and requires a new parse`() {
        val port = StrictLanInvitationInputPort(
            clock = LanClock { Instant.parse("2026-07-25T10:00:30Z") },
        )
        val summary = port.parse(InvitationInputSource.MANUAL, validPayload)
        port.clear()

        assertFailure("LAN_INVITATION_DESTROYED") {
            port.takeConnectRequest(confirmedState(summary), REQUESTED_CAPABILITIES)
        }
    }

    private fun confirmedState(summary: dev.aiauto.android.bridge.lan.LanInvitationSummary) =
        LanPairingUiState(
            phase = LanPairingPhase.REVIEW,
            invitation = summary,
            selectedCandidate = summary.candidates.first(),
            selectedLocalInterface = LanLocalInterface(
                id = "android-network-42",
                name = "wlan0",
                kind = "wifi",
            ),
            desktopFingerprint = summary.desktopFingerprint,
            fingerprintConfirmed = true,
        )

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
            fail("expected $expectedCode")
        } catch (error: LanProtocolException) {
            assertEquals(expectedCode, error.code)
        }
    }

    private companion object {
        val REQUESTED_CAPABILITIES = listOf(
            "lan.bridge.mutual-confirmation.v1",
            "lan.bridge.rpc.v1",
        )

        val validPayload: String by lazy {
            val start = requireNotNull(System.getProperty("user.dir")).let(::File)
            val fixture = generateSequence(start, File::getParentFile)
                .map { directory ->
                    File(directory, "protocol/fixtures/lan-invitation-v1-valid.json")
                }
                .firstOrNull(File::isFile)
            requireNotNull(fixture) { "Unable to locate LAN invitation fixture" }.readText()
        }
    }
}
