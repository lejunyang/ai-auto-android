package lan

// 功能用途：本文件提供无网络副作用的 invitation、client hello 和能力协商校验。

import (
	"crypto/subtle"
	"net/netip"
	"slices"
	"time"
)

// ValidateInvitation 按 N36 规定的错误优先级校验时效、地址、网卡、密钥和指纹。
func ValidateInvitation(invitation Invitation, context ValidationContext) error {
	if invitation.Kind != invitationKind || invitation.Version != protocolVersion {
		return fail(CodeVersionIncompatible, "invitation version is incompatible")
	}
	if invitation.Security.Suite != securitySuite ||
		invitation.Security.Transcript != transcriptDomain ||
		invitation.Security.Confirmation != confirmationAlgorithm {
		return fail(CodeDowngradeRejected, "invitation security suite cannot be downgraded")
	}

	issuedAt, issuedErr := time.Parse(time.RFC3339Nano, invitation.IssuedAt)
	expiresAt, expiresErr := time.Parse(time.RFC3339Nano, invitation.ExpiresAt)
	if issuedErr != nil || expiresErr != nil {
		return fail(CodeInvitationTimeInvalid, "invitation timestamps are invalid")
	}
	if invitation.TTLSeconds < minInvitationTTLSeconds ||
		invitation.TTLSeconds > maxInvitationTTLSeconds {
		return fail(CodeInvitationTTLInvalid, "invitation TTL is outside the allowed range")
	}
	if expiresAt.Sub(issuedAt) != time.Duration(invitation.TTLSeconds)*time.Second {
		return fail(CodeInvitationTTLMismatch, "invitation TTL does not match its timestamps")
	}
	now := context.Now
	if now.IsZero() {
		now = time.Now()
	}
	if now.Before(issuedAt) {
		return fail(CodeInvitationNotYetValid, "invitation is not valid yet")
	}
	if !now.Before(expiresAt) {
		return fail(CodeInvitationExpired, "invitation has expired")
	}
	if _, consumed := context.ConsumedInvitationIDs[invitation.InvitationID]; consumed {
		return fail(CodeInvitationReplayed, "invitation ID was already consumed")
	}
	if _, consumed := context.ConsumedNonces[invitation.Nonce]; consumed {
		return fail(CodeNonceReplayed, "invitation nonce was already consumed")
	}

	if err := validateInvitationAddresses(invitation.Listener); err != nil {
		return err
	}
	if context.ExpectedInterface != nil &&
		*context.ExpectedInterface != invitation.Listener.SelectedInterface {
		return fail(CodeInterfaceMismatch, "invitation interface does not match the selected interface")
	}

	publicKey := decodeBase64URL32(invitation.EphemeralKey.PublicKey)
	if publicKey == nil {
		return fail(CodeEphemeralKeyInvalid, "desktop ephemeral public key is invalid")
	}
	defer clear(publicKey)
	if allBytesZero(publicKey) {
		return fail(CodeEphemeralKeyWeak, "desktop ephemeral public key is weak")
	}

	if !containsCapability(invitation.Capabilities, requiredRPC) ||
		!containsCapability(invitation.Capabilities, requiredConfirmation) {
		return fail(CodeCapabilityMismatch, "invitation is missing a required capability")
	}
	fingerprint, err := ComputeInvitationFingerprint(invitation)
	if err != nil {
		return wrap(CodeInvitationSchemaInvalid, "invitation fingerprint input is invalid", err)
	}
	if subtle.ConstantTimeCompare([]byte(fingerprint), []byte(invitation.Fingerprint)) != 1 {
		return fail(CodeFingerprintMismatch, "invitation fingerprint does not match")
	}
	return nil
}

func validateInvitationAddresses(listener ListenerDescriptor) error {
	for _, candidate := range listener.AddressCandidates {
		address, err := netip.ParseAddr(candidate.Host)
		if err != nil || address.Zone() != "" {
			return fail(CodeAddressInvalid, "invitation address is not an IP literal")
		}
		if address.IsUnspecified() {
			return fail(CodeAddressUnspecified, "invitation address is unspecified")
		}
		if address.IsMulticast() {
			return fail(CodeAddressMulticast, "invitation address is multicast")
		}
		if address.IsLoopback() || (!address.IsPrivate() && !address.IsLinkLocalUnicast()) {
			return fail(CodeAddressNotPrivate, "invitation address is not private or link-local")
		}

		family := "ipv6"
		if address.Is4() {
			family = "ipv4"
		}
		if candidate.Family != family {
			return fail(CodeAddressInvalid, "invitation address family does not match")
		}
		scope := "private"
		if address.IsLinkLocalUnicast() {
			scope = "linkLocal"
		}
		if candidate.Scope != scope {
			return fail(CodeAddressScopeMismatch, "invitation address scope does not match")
		}
		if candidate.InterfaceID != listener.SelectedInterface.ID {
			return fail(CodeInterfaceMismatch, "invitation candidate belongs to another interface")
		}
		if address.Is6() && address.IsLinkLocalUnicast() {
			if candidate.ZoneID != listener.SelectedInterface.Name {
				return fail(CodeInterfaceMismatch, "IPv6 link-local candidate has the wrong zone")
			}
		} else if candidate.ZoneID != "" {
			return fail(CodeInterfaceMismatch, "non-link-local candidate unexpectedly has a zone")
		}
	}
	return nil
}

// ValidateClientHello 在 X25519 和 token 签发前绑定 invitation、客户端公钥及 endpoint。
func ValidateClientHello(invitation Invitation, hello ClientHello) error {
	if hello.Type != "lan.clientHello" ||
		hello.Version != protocolVersion ||
		!validHandshakeType(hello.Type) ||
		!uuidPattern.MatchString(hello.InvitationID) ||
		!hasOnlyVersionOne(hello.SupportedBridgeVersions) ||
		!validStringList(hello.RequestedCapabilities, 2, 64, capabilityNamePattern) {
		return fail(CodeVersionIncompatible, "client hello version or structure is incompatible")
	}
	if hello.InvitationID != invitation.InvitationID || hello.Nonce != invitation.Nonce {
		return fail(CodeTranscriptMismatch, "client hello does not match the invitation")
	}
	clientPublicKey := decodeBase64URL32(hello.ClientEphemeralPublicKey)
	if clientPublicKey == nil {
		return fail(CodeEphemeralKeyInvalid, "client ephemeral public key is invalid")
	}
	defer clear(clientPublicKey)
	if allBytesZero(clientPublicKey) {
		return fail(CodeEphemeralKeyWeak, "client ephemeral public key is weak")
	}

	for _, candidate := range invitation.Listener.AddressCandidates {
		if candidate.Host == hello.SelectedEndpoint.Host &&
			candidate.Family == hello.SelectedEndpoint.Family &&
			candidate.InterfaceID == hello.SelectedEndpoint.InterfaceID &&
			invitation.Listener.Port == hello.SelectedEndpoint.Port {
			return nil
		}
	}
	return fail(CodeInterfaceMismatch, "client endpoint is not an invitation candidate")
}

// NegotiateSelection 只接受 invitation、客户端请求和桌面策略显式共有的版本及能力。
func NegotiateSelection(
	invitation Invitation,
	hello ClientHello,
	allowedCapabilities []string,
) (Selection, error) {
	if err := ValidateClientHello(invitation, hello); err != nil {
		return Selection{}, err
	}
	if !slices.Contains(hello.SupportedBridgeVersions, protocolVersion) {
		return Selection{}, fail(CodeVersionIncompatible, "no compatible bridge version is available")
	}
	advertised := stringSetFromSlice(invitation.Capabilities)
	allowed := stringSetFromSlice(allowedCapabilities)
	requested := stringSetFromSlice(hello.RequestedCapabilities)
	if _, ok := requested[requiredRPC]; !ok {
		return Selection{}, fail(CodeCapabilityMismatch, "client did not request the RPC capability")
	}
	if _, ok := requested[requiredConfirmation]; !ok {
		return Selection{}, fail(CodeCapabilityMismatch, "client did not request mutual confirmation")
	}
	selected := make([]string, 0, len(requested))
	for capability := range requested {
		if _, ok := advertised[capability]; !ok {
			return Selection{}, fail(CodeCapabilityMismatch, "client requested an unadvertised capability")
		}
		if _, ok := allowed[capability]; !ok {
			return Selection{}, fail(CodeCapabilityMismatch, "client requested a disallowed capability")
		}
		selected = append(selected, capability)
	}
	slices.Sort(selected)
	return Selection{
		SelectedBridgeVersion: protocolVersion,
		SelectedCapabilities:  selected,
	}, nil
}

func containsCapability(capabilities []string, wanted string) bool {
	return slices.Contains(capabilities, wanted)
}

func stringSetFromSlice(values []string) map[string]struct{} {
	result := make(map[string]struct{}, len(values))
	for _, value := range values {
		result[value] = struct{}{}
	}
	return result
}

func allBytesZero(value []byte) bool {
	var combined byte
	for _, item := range value {
		combined |= item
	}
	return combined == 0
}
