package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证扫码权限拒绝、唯一网络自动选择和一次短指纹确认连接安全门。
 */

import java.time.Instant

import dev.aiauto.android.bridge.lan.LanAddressCandidate
import dev.aiauto.android.bridge.lan.LanInvitationSummary
import dev.aiauto.android.bridge.lan.LanLocalInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingStateMachineTest {
    @Test
    fun `camera denied keeps manual invitation path available`() {
        val machine = LanPairingStateMachine()

        machine.onCameraCapability(
            hardware = CameraHardware.PRESENT,
            permission = CameraPermission.DENIED,
        )

        assertEquals(ScannerAvailability.PERMISSION_DENIED, machine.state.scannerAvailability)
        assertTrue(machine.state.manualEntryAvailable)
        assertFalse(machine.state.canRequestConnection)
    }

    @Test
    fun `missing camera keeps manual invitation path available`() {
        val machine = LanPairingStateMachine()

        machine.onCameraCapability(
            hardware = CameraHardware.ABSENT,
            permission = CameraPermission.NOT_REQUESTED,
        )

        assertEquals(ScannerAvailability.NO_CAMERA, machine.state.scannerAvailability)
        assertTrue(machine.state.manualEntryAvailable)
    }

    @Test
    fun `scanner and manual inputs use the same payload port without retaining the payload`() {
        val payloads = mutableListOf<Pair<InvitationInputSource, String>>()
        val machine = LanPairingStateMachine(
            invitationInput = LanInvitationInputPort { source, payload ->
                payloads += source to payload
                invitation()
            },
        )

        machine.onScannedPayload("qr-secret-payload")
        assertEquals(InvitationInputSource.SCANNER, payloads.single().first)
        assertEquals("qr-secret-payload", payloads.single().second)
        assertFalse(machine.state.toString().contains("qr-secret-payload"))
        assertNull(machine.state.rawPayload)

        payloads.clear()
        machine.onManualPayload("manual-secret-payload")
        assertEquals(InvitationInputSource.MANUAL, payloads.single().first)
        assertEquals("manual-secret-payload", payloads.single().second)
        assertFalse(machine.state.toString().contains("manual-secret-payload"))
        assertNull(machine.state.rawPayload)
    }

    @Test
    fun `unique candidate and interface auto select but explicit confirmation remains required`() {
        val machine = LanPairingStateMachine(
            invitationInput = LanInvitationInputPort { _, _ -> invitation() },
        )
        machine.onManualPayload("one-time-invitation")
        machine.autoSelectLocalInterface(
            listOf(LanLocalInterface(id = "android-wlan0", name = "wlan0", kind = "wifi")),
        )

        assertFalse(machine.state.canRequestConnection)
        assertEquals("192.168.50.12", machine.state.selectedCandidate?.host)
        assertEquals("wlan0", machine.state.selectedLocalInterface?.name)
        machine.confirmDisplayedFingerprint()
        assertTrue(machine.state.canRequestConnection)
    }

    @Test
    fun `multiple candidates and interfaces never auto select`() {
        val first = invitation()
        val machine = LanPairingStateMachine(
            invitationInput = LanInvitationInputPort { _, _ ->
                first.copy(
                    candidates = first.candidates + LanAddressCandidate(
                        host = "192.168.50.13",
                        family = "ipv4",
                        scope = "private",
                        interfaceId = "if-wifi-en0-7f2a",
                        zoneId = null,
                        port = 47831,
                    ),
                )
            },
        )

        machine.onScannedPayload("one-time-invitation")
        machine.autoSelectLocalInterface(
            listOf(
                LanLocalInterface("android-network-42", "wlan0", "wifi"),
                LanLocalInterface("android-network-43", "eth0", "ethernet"),
            ),
        )

        assertNull(machine.state.selectedCandidate)
        assertNull(machine.state.selectedLocalInterface)
        assertFalse(machine.state.canRequestConnection)
    }

    @Test
    fun `process restore never restores confirmation or an active session`() {
        val restored = LanPairingStateMachine(
            restored = LanPairingRestoreState(
                desktopFingerprint = "8975-0256-F8CF-C56A",
                selectedRemoteHost = "192.168.50.12",
                selectedLocalInterfaceName = "wlan0",
                sessionExpiresAt = Instant.parse("2026-07-25T10:01:30Z"),
            ),
        )

        assertEquals(LanPairingPhase.STOPPED, restored.state.phase)
        assertFalse(restored.state.fingerprintConfirmed)
        assertFalse(restored.state.canRequestConnection)
        assertNull(restored.state.sessionExpiresAt)
        assertEquals("LAN_PROCESS_RESTORED_RECONFIRM_REQUIRED", restored.state.errorCode)
    }

    @Test
    fun `connected UI exposes interface fingerprint expiry and explicit stop without secrets`() {
        val machine = LanPairingStateMachine(
            invitationInput = LanInvitationInputPort { _, _ -> invitation() },
        )
        machine.onManualPayload("one-time-invitation")
        machine.selectCandidate("192.168.50.12", "if-wifi-en0-7f2a")
        machine.selectLocalInterface(
            LanLocalInterface(id = "android-wlan0", name = "wlan0", kind = "wifi"),
        )
        machine.confirmDisplayedFingerprint()
        machine.onConnected(Instant.parse("2026-07-25T10:01:20Z"))

        assertEquals(LanPairingPhase.CONNECTED, machine.state.phase)
        assertEquals("wlan0", machine.state.localInterfaceName)
        assertEquals("8975-0256-F8CF-C56A", machine.state.desktopFingerprint)
        assertEquals(Instant.parse("2026-07-25T10:01:20Z"), machine.state.sessionExpiresAt)
        assertTrue(machine.state.stopAvailable)
        assertFalse(machine.state.toString().contains("nonce"))
        assertFalse(machine.state.toString().contains("token"))

        machine.stop()
        assertEquals(LanPairingPhase.STOPPED, machine.state.phase)
        assertFalse(machine.state.canRequestConnection)
    }

    @Test
    fun `failure clears invitation selection fingerprint and session metadata`() {
        val machine = LanPairingStateMachine(
            invitationInput = LanInvitationInputPort { _, _ -> invitation() },
        )
        machine.onManualPayload("one-time-invitation")
        machine.selectCandidate("192.168.50.12", "if-wifi-en0-7f2a")
        machine.selectLocalInterface(
            LanLocalInterface(id = "android-wlan0", name = "wlan0", kind = "wifi"),
        )
        machine.confirmDisplayedFingerprint()
        machine.onConnected(Instant.parse("2026-07-25T10:01:20Z"))
        assertNotNull(machine.state.invitation)

        machine.onFailure("LAN_NETWORK_CHANGED")

        assertEquals(LanPairingPhase.FAILED, machine.state.phase)
        assertEquals("LAN_NETWORK_CHANGED", machine.state.errorCode)
        assertNull(machine.state.invitation)
        assertNull(machine.state.selectedCandidate)
        assertNull(machine.state.selectedLocalInterface)
        assertNull(machine.state.desktopFingerprint)
        assertNull(machine.state.sessionExpiresAt)
        assertFalse(machine.state.fingerprintConfirmed)
    }

    @Test
    fun `parse failure clears an older invitation and keeps only safe error state`() {
        var shouldFail = false
        val machine = LanPairingStateMachine(
            invitationInput = LanInvitationInputPort { _, _ ->
                if (shouldFail) throw dev.aiauto.android.bridge.lan.LanProtocolException(
                    "LAN_INVITATION_SCHEMA_INVALID",
                )
                invitation()
            },
        )
        machine.onManualPayload("first-invitation")
        assertNotNull(machine.state.invitation)

        shouldFail = true
        machine.onScannedPayload("invalid-invitation")

        assertEquals(LanPairingPhase.FAILED, machine.state.phase)
        assertEquals("LAN_INVITATION_SCHEMA_INVALID", machine.state.errorCode)
        assertNull(machine.state.invitation)
        assertNull(machine.state.desktopFingerprint)
        assertFalse(machine.state.canRequestConnection)
    }

    private companion object {
        fun invitation() = LanInvitationSummary(
            invitationIdHash = "3d73eac4c623",
            desktopInterfaceName = "en0",
            desktopInterfaceKind = "wifi",
            desktopFingerprint = "8975-0256-F8CF-C56A",
            expiresAt = Instant.parse("2026-07-25T10:01:30Z"),
            candidates = listOf(
                LanAddressCandidate(
                    host = "192.168.50.12",
                    family = "ipv4",
                    scope = "private",
                    interfaceId = "if-wifi-en0-7f2a",
                    zoneId = null,
                    port = 47831,
                ),
            ),
        )
    }
}
