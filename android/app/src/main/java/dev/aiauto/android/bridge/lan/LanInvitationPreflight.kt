package dev.aiauto.android.bridge.lan

/**
 * 功能用途：在任何 socket 尝试前验证 invitation 时效、重放、地址、网卡、密钥和指纹。
 */

import java.time.Instant
import java.time.temporal.ChronoUnit

data class LanPreflightContext(
    val now: Instant,
    val consumedInvitationIds: Set<String> = emptySet(),
    val consumedNonces: Set<String> = emptySet(),
    val expectedDesktopInterface: LanInterface? = null,
)

data class LanPreflightResult(
    val accepted: Boolean,
    val code: String?,
)

object LanInvitationPreflight {
    private val requiredCapabilities = setOf(
        "lan.bridge.mutual-confirmation.v1",
        "lan.bridge.rpc.v1",
    )

    fun validate(
        invitation: LanInvitation,
        context: LanPreflightContext,
    ): LanPreflightResult {
        if (invitation.kind != "ai-auto-lan-invitation" || invitation.version != "1.0") {
            return failure("LAN_VERSION_INCOMPATIBLE")
        }
        val expectedTtl = ChronoUnit.MILLIS.between(invitation.issuedAt, invitation.expiresAt)
        if (expectedTtl != invitation.ttlSeconds * 1_000L) {
            return failure("LAN_INVITATION_TTL_MISMATCH")
        }
        if (context.now < invitation.issuedAt) {
            return failure("LAN_INVITATION_NOT_YET_VALID")
        }
        if (context.now >= invitation.expiresAt) {
            return failure("LAN_INVITATION_EXPIRED")
        }
        if (invitation.invitationId in context.consumedInvitationIds) {
            return failure("LAN_INVITATION_REPLAYED")
        }
        if (invitation.nonceBase64Url() in context.consumedNonces) {
            return failure("LAN_NONCE_REPLAYED")
        }
        context.expectedDesktopInterface?.let { expected ->
            if (invitation.desktopInterface != expected) {
                return failure("LAN_INTERFACE_MISMATCH")
            }
        }
        invitation.addressCandidates.forEach { candidate ->
            val addressFailure = validateAddress(invitation.desktopInterface, candidate)
            if (addressFailure != null) return failure(addressFailure)
        }
        if (LanX25519PublicKeys.isLowOrder(invitation.desktopEphemeralPublicKey)) {
            return failure("LAN_EPHEMERAL_KEY_WEAK")
        }
        if (!invitation.capabilities.containsAll(requiredCapabilities)) {
            return failure("LAN_CAPABILITY_MISMATCH")
        }
        if (LanCrypto.invitationFingerprint(invitation.original) != invitation.fingerprint) {
            return failure("LAN_FINGERPRINT_MISMATCH")
        }
        return LanPreflightResult(accepted = true, code = null)
    }

    private fun validateAddress(
        selectedInterface: LanInterface,
        candidate: LanAddressCandidate,
    ): String? {
        val classification = classifyLiteral(candidate.host, candidate.family)
            ?: return "LAN_ADDRESS_INVALID"
        when (classification) {
            "unspecified" -> return "LAN_ADDRESS_UNSPECIFIED"
            "multicast" -> return "LAN_ADDRESS_MULTICAST"
            "loopback", "public" -> return "LAN_ADDRESS_NOT_PRIVATE"
        }
        if (candidate.scope != classification) return "LAN_ADDRESS_SCOPE_MISMATCH"
        if (candidate.interfaceId != selectedInterface.id) return "LAN_INTERFACE_MISMATCH"
        if (
            candidate.family == "ipv6" &&
            classification == "linkLocal" &&
            candidate.zoneId != selectedInterface.name
        ) {
            return "LAN_INTERFACE_MISMATCH"
        }
        if (classification != "linkLocal" && candidate.zoneId != null) {
            return "LAN_INTERFACE_MISMATCH"
        }
        return null
    }

    private fun classifyLiteral(host: String, family: String): String? = when (family) {
        "ipv4" -> parseIpv4(host)?.let(::classifyIpv4)
        "ipv6" -> parseIpv6(host)?.let(::classifyIpv6)
        else -> null
    }

    private fun parseIpv4(host: String): IntArray? {
        if (!IPV4_PATTERN.matches(host)) return null
        val octets = host.split('.').map(String::toInt)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return octets.toIntArray()
    }

    private fun classifyIpv4(octets: IntArray): String = when {
        octets.all { it == 0 } -> "unspecified"
        octets[0] in 224..239 -> "multicast"
        octets[0] == 127 -> "loopback"
        octets[0] == 169 && octets[1] == 254 -> "linkLocal"
        octets[0] == 10 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168 -> "private"
        else -> "public"
    }

    private fun parseIpv6(host: String): IntArray? {
        if ('%' in host || !IPV6_PATTERN.matches(host)) return null
        val halves = host.lowercase().split("::")
        if (halves.size > 2) return null
        val left = parseIpv6Half(halves[0]) ?: return null
        val right = parseIpv6Half(halves.getOrElse(1) { "" }) ?: return null
        val missing = 8 - left.size - right.size
        if (halves.size == 1 && missing != 0 || halves.size == 2 && missing < 1) return null
        return (left + List(missing) { 0 } + right).toIntArray()
    }

    private fun parseIpv6Half(half: String): List<Int>? {
        if (half.isEmpty()) return emptyList()
        val output = mutableListOf<Int>()
        half.split(':').forEach { group ->
            if ('.' in group) {
                val ipv4 = parseIpv4(group) ?: return null
                output += (ipv4[0] shl 8) or ipv4[1]
                output += (ipv4[2] shl 8) or ipv4[3]
            } else {
                if (!IPV6_GROUP_PATTERN.matches(group)) return null
                output += group.toInt(16)
            }
        }
        return output
    }

    private fun classifyIpv6(groups: IntArray): String = when {
        groups.all { it == 0 } -> "unspecified"
        groups.take(7).all { it == 0 } && groups[7] == 1 -> "loopback"
        groups[0] and 0xff00 == 0xff00 -> "multicast"
        groups[0] and 0xfe00 == 0xfc00 -> "private"
        groups[0] and 0xffc0 == 0xfe80 -> "linkLocal"
        else -> "public"
    }

    private fun failure(code: String) = LanPreflightResult(accepted = false, code = code)

    private val IPV4_PATTERN = Regex(
        "^(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}$",
    )
    private val IPV6_PATTERN = Regex("^[0-9A-Fa-f:.]+$")
    private val IPV6_GROUP_PATTERN = Regex("^[0-9a-f]{1,4}$")
}
