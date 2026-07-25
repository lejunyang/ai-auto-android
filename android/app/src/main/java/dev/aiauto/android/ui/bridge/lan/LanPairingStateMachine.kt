package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：提供与具体相机库无关的扫码/手工输入状态，并强制候选、网卡和指纹确认门。
 */

import java.time.Instant

import dev.aiauto.android.bridge.lan.LanAddressCandidate
import dev.aiauto.android.bridge.lan.LanInvitationSummary
import dev.aiauto.android.bridge.lan.LanLocalInterface

enum class CameraHardware {
    PRESENT,
    ABSENT,
}

enum class CameraPermission {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
}

enum class ScannerAvailability {
    READY,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    NO_CAMERA,
    PROVIDER_UNAVAILABLE,
}

enum class InvitationInputSource {
    SCANNER,
    MANUAL,
}

enum class LanPairingPhase {
    INPUT,
    REVIEW,
    CONNECTING,
    CONNECTED,
    STOPPED,
    FAILED,
}

fun interface LanInvitationInputPort {
    fun parse(source: InvitationInputSource, payload: String): LanInvitationSummary

    fun clear() = Unit
}

data class LanPairingRestoreState(
    val desktopFingerprint: String?,
    val selectedRemoteHost: String?,
    val selectedLocalInterfaceName: String?,
    val sessionExpiresAt: Instant?,
)

data class LanPairingUiState(
    val phase: LanPairingPhase = LanPairingPhase.INPUT,
    val scannerAvailability: ScannerAvailability = ScannerAvailability.PERMISSION_REQUIRED,
    val manualEntryAvailable: Boolean = true,
    val invitation: LanInvitationSummary? = null,
    val selectedCandidate: LanAddressCandidate? = null,
    val selectedLocalInterface: LanLocalInterface? = null,
    val desktopFingerprint: String? = null,
    val fingerprintConfirmed: Boolean = false,
    val sessionExpiresAt: Instant? = null,
    val errorCode: String? = null,
) {
    val rawPayload: String? = null

    val localInterfaceName: String?
        get() = selectedLocalInterface?.name

    val canRequestConnection: Boolean
        get() = phase == LanPairingPhase.REVIEW &&
            invitation != null &&
            selectedCandidate != null &&
            selectedLocalInterface != null &&
            fingerprintConfirmed

    val stopAvailable: Boolean
        get() = phase == LanPairingPhase.CONNECTING || phase == LanPairingPhase.CONNECTED
}

class LanPairingStateMachine(
    private val invitationInput: LanInvitationInputPort = LanInvitationInputPort { _, _ ->
        throw IllegalStateException("LAN invitation input provider is not configured")
    },
    restored: LanPairingRestoreState? = null,
) {
    var state: LanPairingUiState = if (restored == null) {
        LanPairingUiState()
    } else {
        LanPairingUiState(
            phase = LanPairingPhase.STOPPED,
            errorCode = "LAN_PROCESS_RESTORED_RECONFIRM_REQUIRED",
        )
    }
        private set

    fun onCameraCapability(
        hardware: CameraHardware,
        permission: CameraPermission,
    ) {
        state = state.copy(
            scannerAvailability = when {
                hardware == CameraHardware.ABSENT -> ScannerAvailability.NO_CAMERA
                permission == CameraPermission.GRANTED -> ScannerAvailability.READY
                permission == CameraPermission.DENIED -> ScannerAvailability.PERMISSION_DENIED
                else -> ScannerAvailability.PERMISSION_REQUIRED
            },
            manualEntryAvailable = true,
        )
    }

    fun onScannerUnavailable() {
        state = state.copy(
            scannerAvailability = ScannerAvailability.PROVIDER_UNAVAILABLE,
            manualEntryAvailable = true,
        )
    }

    fun onScannedPayload(payload: String) {
        consumePayload(InvitationInputSource.SCANNER, payload)
    }

    fun onManualPayload(payload: String) {
        consumePayload(InvitationInputSource.MANUAL, payload)
    }

    fun selectCandidate(host: String, interfaceId: String) {
        val candidate = state.invitation?.candidates?.singleOrNull {
            it.host == host && it.interfaceId == interfaceId
        } ?: return
        state = state.copy(
            selectedCandidate = candidate,
            fingerprintConfirmed = false,
        )
    }

    fun selectLocalInterface(localInterface: LanLocalInterface) {
        state = state.copy(
            selectedLocalInterface = localInterface,
            fingerprintConfirmed = false,
        )
    }

    fun confirmFingerprint(input: String) {
        state = state.copy(
            fingerprintConfirmed = input == state.desktopFingerprint,
        )
    }

    fun beginConnecting() {
        if (!state.canRequestConnection) return
        state = state.copy(
            phase = LanPairingPhase.CONNECTING,
            errorCode = null,
        )
    }

    fun onConnected(expiresAt: Instant) {
        if (!state.canRequestConnection && state.phase != LanPairingPhase.CONNECTING) return
        state = state.copy(
            phase = LanPairingPhase.CONNECTED,
            sessionExpiresAt = expiresAt,
            errorCode = null,
        )
    }

    fun onFailure(code: String) {
        invitationInput.clear()
        state = LanPairingUiState(
            phase = LanPairingPhase.FAILED,
            scannerAvailability = state.scannerAvailability,
            errorCode = code,
        )
    }

    fun stop() {
        invitationInput.clear()
        state = LanPairingUiState(
            phase = LanPairingPhase.STOPPED,
            scannerAvailability = state.scannerAvailability,
        )
    }

    private fun consumePayload(source: InvitationInputSource, payload: String) {
        val summary = try {
            invitationInput.parse(source, payload)
        } catch (error: dev.aiauto.android.bridge.lan.LanProtocolException) {
            onFailure(error.code)
            return
        } catch (_: Exception) {
            onFailure("LAN_INVITATION_SCHEMA_INVALID")
            return
        } finally {
            // String 无法可靠清零，因此 payload 仅在同步端口调用栈内使用且绝不进入状态或日志。
        }
        state = LanPairingUiState(
            phase = LanPairingPhase.REVIEW,
            scannerAvailability = state.scannerAvailability,
            invitation = summary,
            desktopFingerprint = summary.desktopFingerprint,
        )
    }
}
