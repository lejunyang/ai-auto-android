package lan

// 安全约束：严格按 N36 顺序验证 client hello、transcript、X25519 与双方确认后再建会话。

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net"
	"slices"
	"time"
)

const (
	defaultHandshakeTimeout = 15 * time.Second
	lanTokenDomain          = "AIAUTO-LAN-BRIDGE-SESSION-TOKEN-V1"
)

type desktopSelectionMessage struct {
	Type                  string   `json:"type"`
	Version               string   `json:"version"`
	InvitationID          string   `json:"invitationId"`
	TranscriptHash        string   `json:"transcriptHash"`
	SelectedBridgeVersion string   `json:"selectedBridgeVersion"`
	SelectedCapabilities  []string `json:"selectedCapabilities"`
}

type confirmationMessage struct {
	Type           string           `json:"type"`
	Version        string           `json:"version"`
	InvitationID   string           `json:"invitationId"`
	Role           ConfirmationRole `json:"role"`
	TranscriptHash string           `json:"transcriptHash"`
	Tag            string           `json:"tag"`
}

func (pending *PendingListener) performHandshake(
	ctx context.Context,
	connection net.Conn,
	policy HandshakePolicy,
) (*Session, error) {
	invitation := pending.Invitation()
	now := pending.now().UTC()
	expectedInterface := SelectedInterface{
		ID:   pending.selected.ID,
		Name: pending.selected.Name,
		Kind: pending.selected.Kind,
	}
	if err := ValidateInvitation(invitation, ValidationContext{
		Now:               now,
		ExpectedInterface: &expectedInterface,
	}); err != nil {
		return nil, err
	}

	timeout := policy.HandshakeTimeout
	if timeout <= 0 {
		timeout = defaultHandshakeTimeout
	}
	expiresAt, err := time.Parse(time.RFC3339Nano, invitation.ExpiresAt)
	if err != nil {
		return nil, fail(CodeInvitationTimeInvalid, "invitation expiration is invalid")
	}
	if remaining := expiresAt.Sub(now); remaining < timeout {
		timeout = remaining
	}
	if timeout <= 0 {
		return nil, fail(CodeInvitationExpired, "invitation expired before handshake")
	}
	handshakeContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if deadline, ok := handshakeContext.Deadline(); ok {
		if err := connection.SetDeadline(deadline); err != nil {
			return nil, wrap(CodeConnectionClosed, "handshake deadline could not be set", err)
		}
	}
	stopCloser := make(chan struct{})
	go func() {
		select {
		case <-handshakeContext.Done():
			_ = connection.Close()
		case <-stopCloser:
		}
	}()
	defer close(stopCloser)

	reader := bufio.NewReaderSize(connection, maxHandshakeBytes)
	var hello ClientHello
	if err := readHandshakeMessage(reader, &hello); err != nil {
		return nil, mapHandshakeContextError(handshakeContext, err)
	}
	if err := ValidateClientHello(invitation, hello); err != nil {
		return nil, err
	}
	if err := pending.replayGuard.Consume(
		invitation.InvitationID,
		invitation.Nonce,
		expiresAt,
		pending.now().UTC(),
	); err != nil {
		return nil, err
	}

	selection, err := NegotiateSelection(
		invitation,
		hello,
		policy.AllowedCapabilities,
	)
	if err != nil {
		return nil, err
	}
	if !slices.IsSorted(selection.SelectedCapabilities) {
		return nil, fail(CodeCapabilityMismatch, "selected capabilities are not canonical")
	}
	transcriptHash, err := BuildTranscriptHash(invitation, hello, selection)
	if err != nil {
		return nil, wrap(CodeTranscriptMismatch, "transcript could not be built", err)
	}
	clientPublicKey := decodeBase64URL32(hello.ClientEphemeralPublicKey)
	if clientPublicKey == nil {
		clear(transcriptHash[:])
		return nil, fail(CodeEphemeralKeyInvalid, "client ephemeral public key is invalid")
	}
	sharedSecret, err := pending.privateKey.ECDH(clientPublicKey)
	clear(clientPublicKey)
	if err != nil {
		clear(transcriptHash[:])
		return nil, err
	}
	keys, err := DeriveSessionKeys(sharedSecret, transcriptHash)
	clear(sharedSecret)
	if err != nil {
		clear(transcriptHash[:])
		return nil, err
	}
	cleanupKeys := true
	defer func() {
		if cleanupKeys {
			keys.Destroy()
			clear(transcriptHash[:])
		}
	}()

	if err := writeHandshakeMessage(connection, desktopSelectionMessage{
		Type:                  "lan.desktopSelection",
		Version:               protocolVersion,
		InvitationID:          invitation.InvitationID,
		TranscriptHash:        base64.RawURLEncoding.EncodeToString(transcriptHash[:]),
		SelectedBridgeVersion: selection.SelectedBridgeVersion,
		SelectedCapabilities:  append([]string(nil), selection.SelectedCapabilities...),
	}); err != nil {
		return nil, mapHandshakeContextError(handshakeContext, err)
	}
	confirmationKey := keys.Confirmation()
	desktopTag, err := SignConfirmation(
		ConfirmationDesktop,
		confirmationKey,
		transcriptHash,
	)
	if err != nil {
		clear(confirmationKey)
		return nil, err
	}
	if err := writeHandshakeMessage(connection, confirmationMessage{
		Type:           "lan.confirmation",
		Version:        protocolVersion,
		InvitationID:   invitation.InvitationID,
		Role:           ConfirmationDesktop,
		TranscriptHash: base64.RawURLEncoding.EncodeToString(transcriptHash[:]),
		Tag:            base64.RawURLEncoding.EncodeToString(desktopTag),
	}); err != nil {
		clear(desktopTag)
		clear(confirmationKey)
		return nil, mapHandshakeContextError(handshakeContext, err)
	}
	clear(desktopTag)

	var clientConfirmation confirmationMessage
	if err := readHandshakeMessage(reader, &clientConfirmation); err != nil {
		clear(confirmationKey)
		return nil, mapHandshakeContextError(handshakeContext, err)
	}
	if err := validateConfirmationMessage(
		clientConfirmation,
		invitation.InvitationID,
		ConfirmationClient,
		transcriptHash,
		confirmationKey,
	); err != nil {
		clear(confirmationKey)
		return nil, err
	}
	clear(confirmationKey)

	framer, err := NewFramer(FramerDesktop, keys, transcriptHash)
	if err != nil {
		return nil, err
	}
	tokenBindingKey := keys.TokenBinding()
	sessionToken := deriveSessionToken(
		tokenBindingKey,
		transcriptHash,
		invitation.InvitationID,
		expiresAt,
	)
	clear(tokenBindingKey)
	keys.Destroy()
	cleanupKeys = false
	if err := connection.SetDeadline(time.Time{}); err != nil {
		framer.Destroy()
		clear(sessionToken)
		clear(transcriptHash[:])
		return nil, wrap(CodeConnectionClosed, "session deadline could not be reset", err)
	}
	return newSession(
		connection,
		framer,
		sessionToken,
		transcriptHash,
		selection.SelectedCapabilities,
		hello.SelectedEndpoint,
		expiresAt,
	), nil
}

func validateConfirmationMessage(
	message confirmationMessage,
	invitationID string,
	role ConfirmationRole,
	transcriptHash [32]byte,
	confirmationKey []byte,
) error {
	if message.Type != "lan.confirmation" ||
		message.Version != protocolVersion ||
		message.InvitationID != invitationID ||
		message.Role != role {
		return fail(CodeConfirmationInvalid, "confirmation metadata is invalid")
	}
	receivedHash := decodeBase64URL32(message.TranscriptHash)
	if receivedHash == nil {
		return fail(CodeTranscriptMismatch, "confirmation transcript hash is invalid")
	}
	hashMatches := subtle.ConstantTimeCompare(receivedHash, transcriptHash[:]) == 1
	clear(receivedHash)
	if !hashMatches {
		return fail(CodeTranscriptMismatch, "confirmation transcript hash does not match")
	}
	receivedTag := decodeBase64URL32(message.Tag)
	if receivedTag == nil {
		return fail(CodeConfirmationInvalid, "confirmation tag is invalid")
	}
	defer clear(receivedTag)
	if !VerifyConfirmation(role, confirmationKey, transcriptHash, receivedTag) {
		return fail(CodeConfirmationInvalid, "confirmation tag does not verify")
	}
	return nil
}

func deriveSessionToken(
	tokenBindingKey []byte,
	transcriptHash [32]byte,
	invitationID string,
	expiresAt time.Time,
) []byte {
	mac := hmac.New(sha256.New, tokenBindingKey)
	mac.Write([]byte(lanTokenDomain))
	mac.Write([]byte{0})
	mac.Write(transcriptHash[:])
	mac.Write([]byte{0})
	mac.Write([]byte(invitationID))
	mac.Write([]byte{0})
	mac.Write([]byte(expiresAt.UTC().Format(time.RFC3339Nano)))
	return mac.Sum(nil)
}

func mapHandshakeContextError(ctx context.Context, err error) error {
	if errors.Is(ctx.Err(), context.Canceled) {
		return fail(CodeCancelled, "handshake was cancelled")
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return fail(CodeAcceptTimeout, "handshake timed out")
	}
	return err
}
