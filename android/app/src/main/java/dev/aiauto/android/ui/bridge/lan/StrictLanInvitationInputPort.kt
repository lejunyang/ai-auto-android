package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：让扫码与手工输入复用同一严格 parser/preflight，并在生成安全摘要后清除秘密。
 */

import dev.aiauto.android.bridge.lan.LanClock
import dev.aiauto.android.bridge.lan.LanConnectRequest
import dev.aiauto.android.bridge.lan.LanInvitation
import dev.aiauto.android.bridge.lan.LanInvitationParser
import dev.aiauto.android.bridge.lan.LanInvitationPreflight
import dev.aiauto.android.bridge.lan.LanPreflightContext
import dev.aiauto.android.bridge.lan.LanProtocolException

class StrictLanInvitationInputPort(
    private val clock: LanClock,
) : LanInvitationInputPort, AutoCloseable {
    private var pendingInvitation: LanInvitation? = null

    @Synchronized
    override fun parse(
        source: InvitationInputSource,
        payload: String,
    ) = run {
        clear()
        if (payload.length > MAX_INVITATION_BYTES) {
            throw LanProtocolException("LAN_FRAME_TOO_LARGE")
        }
        val payloadBytes = payload.encodeToByteArray()
        try {
            if (payloadBytes.size > MAX_INVITATION_BYTES) {
                throw LanProtocolException("LAN_FRAME_TOO_LARGE")
            }
        } finally {
            payloadBytes.fill(0)
        }
        val invitation = LanInvitationParser.parse(payload)
        try {
            val preflight = LanInvitationPreflight.validate(
                invitation = invitation,
                context = LanPreflightContext(now = clock.now()),
            )
            if (!preflight.accepted) {
                throw LanProtocolException(requireNotNull(preflight.code))
            }
            invitation.summary().also {
                pendingInvitation = invitation
            }
        } catch (error: Exception) {
            invitation.close()
            throw error
        }
    }

    @Synchronized
    fun takeConnectRequest(
        state: LanPairingUiState,
        requestedCapabilities: List<String>,
    ): LanConnectRequest {
        if (!state.canRequestConnection) {
            throw LanProtocolException("LAN_USER_CONFIRMATION_REQUIRED")
        }
        val invitation = pendingInvitation
            ?: throw LanProtocolException("LAN_INVITATION_DESTROYED")
        val candidate = state.selectedCandidate
        if (
            candidate !in invitation.addressCandidates ||
            state.desktopFingerprint != invitation.fingerprint
        ) {
            clear()
            throw LanProtocolException("LAN_USER_CONFIRMATION_REQUIRED")
        }
        pendingInvitation = null
        return LanConnectRequest(
            invitation = invitation,
            selectedCandidate = candidate,
            selectedLocalInterface = state.selectedLocalInterface,
            confirmedFingerprint = state.desktopFingerprint,
            requestedCapabilities = requestedCapabilities,
        )
    }

    @Synchronized
    override fun clear() {
        pendingInvitation?.close()
        pendingInvitation = null
    }

    override fun close() {
        clear()
    }

    private companion object {
        const val MAX_INVITATION_BYTES = 64 * 1024
    }
}
