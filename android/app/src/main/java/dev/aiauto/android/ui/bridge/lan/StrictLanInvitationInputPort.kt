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
        val maximumInputChars = when {
            source == InvitationInputSource.MANUAL &&
                payload.startsWith(MANUAL_CODE_PREFIX) -> MAX_MANUAL_CODE_CHARS
            else -> MAX_INVITATION_BYTES
        }
        if (payload.length > maximumInputChars) {
            throw LanProtocolException("LAN_FRAME_TOO_LARGE")
        }
        val payloadBytes = when (source) {
            InvitationInputSource.SCANNER -> {
                if (payload.startsWith(MANUAL_CODE_PREFIX)) {
                    throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
                }
                payload.encodeToByteArray()
            }
            InvitationInputSource.MANUAL -> {
                if (payload.startsWith(MANUAL_CODE_PREFIX)) {
                    decodeManualCode(payload)
                } else {
                    payload.encodeToByteArray()
                }
            }
        }
        try {
            if (payloadBytes.size > MAX_INVITATION_BYTES) {
                throw LanProtocolException("LAN_FRAME_TOO_LARGE")
            }
            val invitation = LanInvitationParser.parse(payloadBytes.decodeToString())
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
        } finally {
            payloadBytes.fill(0)
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

    private fun decodeManualCode(code: String): ByteArray {
        if (!code.startsWith(MANUAL_CODE_PREFIX)) {
            throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
        }
        val encoded = code.removePrefix(MANUAL_CODE_PREFIX)
        if (encoded.isEmpty() || encoded.length > MAX_MANUAL_ENCODED_CHARS) {
            throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
        }
        val result = ByteArray(encoded.length * 5 / 8)
        var accumulator = 0
        var bits = 0
        var outputIndex = 0
        try {
            encoded.forEach { character ->
                val value = BASE32_ALPHABET.indexOf(character)
                if (value < 0) {
                    throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
                }
                accumulator = (accumulator shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    if (outputIndex >= result.size) {
                        throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
                    }
                    result[outputIndex++] = (accumulator shr bits).toByte()
                    accumulator = if (bits == 0) {
                        0
                    } else {
                        accumulator and ((1 shl bits) - 1)
                    }
                }
            }
            if (bits > 0 && accumulator and ((1 shl bits) - 1) != 0) {
                throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
            }
            return result.copyOf(outputIndex).also {
                result.fill(0)
            }
        } catch (error: Exception) {
            result.fill(0)
            throw error
        }
    }

    private companion object {
        const val MAX_INVITATION_BYTES = 64 * 1024
        const val MANUAL_CODE_PREFIX = "AIAUTO1-"
        const val MAX_MANUAL_ENCODED_CHARS = (MAX_INVITATION_BYTES * 8 + 4) / 5
        const val MAX_MANUAL_CODE_CHARS =
            MANUAL_CODE_PREFIX.length + MAX_MANUAL_ENCODED_CHARS
        const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
