package cli

// 功能用途：为 aactl 注册无需 ADB 的 LAN 网卡发现和一次性 invitation listener，
// 并以流式协议信封管理 accept、认证、清理和秘密脱敏边界。

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"slices"
	"strconv"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge/lan"
	"github.com/lejunyang/ai-auto-android/internal/output"
)

type lanListenerStarter interface {
	Start(context.Context, lan.ListenerOptions) (lanPending, error)
}

type lanPending interface {
	Bundle() lan.InvitationBundle
	Accept(context.Context, lan.HandshakePolicy) (lanSession, error)
	Close() error
}

type lanSession interface {
	Capabilities() []string
	Endpoint() lan.Endpoint
	ExpiresAt() time.Time
	Call(context.Context, string, any, any) error
	Close() error
}

type platformLANListenerStarter struct{}

func (platformLANListenerStarter) Start(
	ctx context.Context,
	options lan.ListenerOptions,
) (lanPending, error) {
	pending, err := lan.StartListener(ctx, options)
	if err != nil {
		return nil, err
	}
	return platformLANPending{pending: pending}, nil
}

type platformLANPending struct {
	pending *lan.PendingListener
}

func (adapter platformLANPending) Bundle() lan.InvitationBundle {
	return adapter.pending.Bundle()
}

func (adapter platformLANPending) Accept(
	ctx context.Context,
	policy lan.HandshakePolicy,
) (lanSession, error) {
	return adapter.pending.Accept(ctx, policy)
}

func (adapter platformLANPending) Close() error {
	return adapter.pending.Close()
}

type lanInterfacesResult struct {
	Interfaces []lan.NetworkInterface `json:"interfaces"`
	Count      int                    `json:"count"`
}

type lanInvitationResult struct {
	Phase          string          `json:"phase"`
	InvitationJSON json.RawMessage `json:"invitationJson"`
	ManualCode     string          `json:"manualCode"`
	Fingerprint    string          `json:"fingerprint"`
	Endpoint       lan.Endpoint    `json:"endpoint"`
	ExpiresAt      string          `json:"expiresAt"`
	QRGenerated    bool            `json:"qrGenerated"`
	QRFormat       string          `json:"qrFormat"`
	QRText         string          `json:"qrText,omitempty"`
}

type lanClosedResult struct {
	Phase         string       `json:"phase"`
	Authenticated bool         `json:"authenticated"`
	Endpoint      lan.Endpoint `json:"endpoint"`
	Capabilities  []string     `json:"capabilities"`
	ExpiresAt     string       `json:"expiresAt"`
}

type lanRPCResult struct {
	Phase     string          `json:"phase"`
	Method    string          `json:"method"`
	Iteration int             `json:"iteration"`
	Result    json.RawMessage `json:"result"`
}

type lanOutputMode struct {
	compactJSON bool
	terminal    bool
}

func (a *App) runLANCommand(
	ctx context.Context,
	requestID string,
	startedAt time.Time,
	args []string,
	mode lanOutputMode,
) int {
	if len(args) == 0 {
		return a.writeLANFailure(
			requestID,
			startedAt,
			usageError("Usage: aactl bridge lan interfaces|listen [options]"),
			mode,
		)
	}
	switch args[0] {
	case "interfaces":
		if len(args) != 1 {
			return a.writeLANFailure(
				requestID,
				startedAt,
				usageError("Usage: aactl bridge lan interfaces [--json]"),
				mode,
			)
		}
		interfaces, err := lan.DiscoverInterfaces(a.lanInterfaceSource())
		if err != nil {
			return a.writeLANFailure(requestID, startedAt, err, mode)
		}
		result := lanInterfacesResult{
			Interfaces: interfaces,
			Count:      len(interfaces),
		}
		if mode.terminal {
			_, err = fmt.Fprintf(a.Stdout, "可用 LAN 网卡：%d\n", result.Count)
		} else {
			err = output.Write(
				a.Stdout,
				output.Success(requestID, startedAt, result),
				mode.compactJSON,
			)
		}
		if err != nil {
			return apperr.ExitInternal
		}
		return apperr.ExitSuccess
	case "listen":
		return a.runLANListen(ctx, requestID, startedAt, args[1:], mode)
	default:
		return a.writeLANFailure(
			requestID,
			startedAt,
			usageError("Unknown bridge lan command. Supported commands: interfaces, listen."),
			mode,
		)
	}
}

func (a *App) runLANListen(
	ctx context.Context,
	requestID string,
	startedAt time.Time,
	args []string,
	mode lanOutputMode,
) int {
	options, err := parseNamedOptions(args, optionSpec{
		allowed: optionSet(
			"interface",
			"address",
			"ttl",
			"accept-timeout",
			"port",
			"rpc-method",
			"rpc-count",
		),
		required: []string{"interface", "address"},
	})
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	ttl, err := parseLANDuration(options, "ttl", defaultLANInvitationTTL)
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	acceptTimeout, err := parseLANAcceptTimeout(options["accept-timeout"])
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	port, err := parseLANPort(options["port"])
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	rpcMethod, rpcCount, err := parseLANRPCProbe(
		options["rpc-method"],
		options["rpc-count"],
	)
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	interfaces, err := lan.DiscoverInterfaces(a.lanInterfaceSource())
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	selected, err := lan.SelectInterface(interfaces, options["interface"])
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	candidate, err := selectLANCandidate(selected, options["address"])
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	pending, err := a.lanListenerStarter().Start(ctx, lan.ListenerOptions{
		InterfaceSource: a.lanInterfaceSource(),
		Binder:          a.lanListenerBinder(),
		Selected:        selected,
		Candidate:       candidate,
		Port:            port,
		TTL:             ttl,
		AcceptTimeout:   acceptTimeout,
		Capabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
		Random:     a.lanRandomSource(),
		QRProvider: a.LANQR,
		Now:        a.LANNow,
	})
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	defer pending.Close()

	bundle := pending.Bundle()
	defer clear(bundle.Payload)
	defer clear(bundle.QR.Data)
	invitationEvent := lanInvitationResult{
		Phase:          "invitation",
		InvitationJSON: append(json.RawMessage(nil), bundle.Payload...),
		ManualCode:     bundle.ManualCode,
		Fingerprint:    bundle.Invitation.Fingerprint,
		Endpoint: lan.Endpoint{
			Host:        candidate.Host,
			Family:      candidate.Family,
			Port:        bundle.Invitation.Listener.Port,
			InterfaceID: candidate.InterfaceID,
		},
		ExpiresAt:   bundle.Invitation.ExpiresAt,
		QRGenerated: bundle.QR.Generated,
		QRFormat:    bundle.QR.Format,
		QRText:      string(bundle.QR.Data),
	}
	writeErr := a.writeLANInvitation(
		requestID,
		startedAt,
		invitationEvent,
		mode,
	)
	clear(invitationEvent.InvitationJSON)
	invitationEvent.QRText = ""
	if writeErr != nil {
		return apperr.ExitInternal
	}

	session, err := pending.Accept(ctx, lan.HandshakePolicy{
		AllowedCapabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
	})
	if err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	defer session.Close()
	capabilities := session.Capabilities()
	slices.Sort(capabilities)
	endpoint := session.Endpoint()
	expiresAt := session.ExpiresAt()
	for iteration := 1; iteration <= rpcCount; iteration++ {
		var result json.RawMessage
		if err := session.Call(ctx, rpcMethod, map[string]any{}, &result); err != nil {
			clear(result)
			return a.writeLANFailure(requestID, startedAt, err, mode)
		}
		event := lanRPCResult{
			Phase:     "rpc",
			Method:    rpcMethod,
			Iteration: iteration,
			Result:    append(json.RawMessage(nil), result...),
		}
		clear(result)
		if err := a.writeLANRPC(requestID, startedAt, event, mode); err != nil {
			clear(event.Result)
			return apperr.ExitInternal
		}
		clear(event.Result)
	}
	if rpcCount > 0 {
		var closeResult struct {
			Closed bool `json:"closed"`
		}
		if err := session.Call(
			ctx,
			"session.close",
			map[string]any{},
			&closeResult,
		); err != nil {
			return a.writeLANFailure(requestID, startedAt, err, mode)
		}
		if !closeResult.Closed {
			return a.writeLANFailure(
				requestID,
				startedAt,
				&lan.Error{
					Code:    lan.CodeFrameInvalid,
					Message: "LAN RPC peer did not confirm session close",
				},
				mode,
			)
		}
	}
	closed := lanClosedResult{
		Phase:         "closed",
		Authenticated: true,
		Endpoint:      endpoint,
		Capabilities:  capabilities,
		ExpiresAt:     expiresAt.UTC().Format(time.RFC3339),
	}
	if err := session.Close(); err != nil {
		return a.writeLANFailure(requestID, startedAt, err, mode)
	}
	if err := a.writeLANClosed(requestID, startedAt, closed, mode); err != nil {
		return apperr.ExitInternal
	}
	return apperr.ExitSuccess
}

func (a *App) writeLANInvitation(
	requestID string,
	startedAt time.Time,
	event lanInvitationResult,
	mode lanOutputMode,
) error {
	if !mode.terminal {
		return output.Write(
			a.Stdout,
			output.Success(requestID, startedAt, event),
			mode.compactJSON,
		)
	}
	if !event.QRGenerated || event.QRFormat != lan.QRFormatTerminalUTF8 ||
		event.QRText == "" {
		return errors.New("terminal QR representation is unavailable")
	}
	_, err := fmt.Fprintf(
		a.Stdout,
		"\n%s\n桌面地址：%s:%d\n桌面短指纹：%s\n邀请过期：%s\n\n",
		event.QRText,
		event.Endpoint.Host,
		event.Endpoint.Port,
		event.Fingerprint,
		event.ExpiresAt,
	)
	return err
}

func (a *App) writeLANRPC(
	requestID string,
	startedAt time.Time,
	event lanRPCResult,
	mode lanOutputMode,
) error {
	if !mode.terminal {
		return output.Write(
			a.Stdout,
			output.Success(requestID, startedAt, event),
			mode.compactJSON,
		)
	}
	_, err := fmt.Fprintf(
		a.Stdout,
		"只读 RPC 已通过：%s，第 %d 次。\n",
		event.Method,
		event.Iteration,
	)
	return err
}

func (a *App) writeLANClosed(
	requestID string,
	startedAt time.Time,
	event lanClosedResult,
	mode lanOutputMode,
) error {
	if !mode.terminal {
		return output.Write(
			a.Stdout,
			output.Success(requestID, startedAt, event),
			mode.compactJSON,
		)
	}
	_, err := fmt.Fprintf(
		a.Stdout,
		"LAN 会话已认证并关闭：authenticated=%t\n",
		event.Authenticated,
	)
	return err
}

func (a *App) writeLANFailure(
	requestID string,
	startedAt time.Time,
	err error,
	mode lanOutputMode,
) int {
	publicError := publicLANError(err)
	exitCode := lanCLIExitCode(err)
	var writeErr error
	if mode.terminal {
		_, writeErr = fmt.Fprintf(a.Stdout, "LAN 失败：%s\n", publicError.Error())
	} else {
		writeErr = output.Write(
			a.Stdout,
			output.Failure(requestID, startedAt, publicError),
			mode.compactJSON,
		)
	}
	if writeErr != nil {
		return apperr.ExitInternal
	}
	if exitCode != apperr.ExitInternal {
		return exitCode
	}
	return apperr.ExitCode(publicError)
}

func (a *App) lanInterfaceSource() lan.InterfaceSource {
	if a.LANInterfaces != nil {
		return a.LANInterfaces
	}
	return lan.SystemInterfaceSource{}
}

func (a *App) lanListenerBinder() lan.ListenerBinder {
	if a.LANBinder != nil {
		return a.LANBinder
	}
	return lan.SystemBinder{}
}

func (a *App) lanListenerStarter() lanListenerStarter {
	if a.LANListeners != nil {
		return a.LANListeners
	}
	return platformLANListenerStarter{}
}

func (a *App) lanRandomSource() io.Reader {
	if a.LANRandom != nil {
		return a.LANRandom
	}
	return rand.Reader
}

func parseLANDuration(
	options map[string]string,
	name string,
	fallback time.Duration,
) (time.Duration, error) {
	raw := options[name]
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value < 15*time.Second || value > 600*time.Second ||
		value%time.Second != 0 {
		return 0, usageError(fmt.Sprintf(
			"%s must be a whole number of seconds between 15s and 600s.",
			name,
		))
	}
	return value, nil
}

func parseLANAcceptTimeout(raw string) (time.Duration, error) {
	if raw == "" {
		return defaultLANAcceptTimeout, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value <= 0 || value > 600*time.Second {
		return 0, usageError("accept-timeout must be greater than zero and at most 600s.")
	}
	return value, nil
}

func parseLANPort(raw string) (int, error) {
	if raw == "" {
		return 0, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 1024 || value > 65535 {
		return 0, usageError("port must be between 1024 and 65535.")
	}
	return value, nil
}

func parseLANRPCProbe(method string, rawCount string) (string, int, error) {
	if method == "" {
		if rawCount != "" {
			return "", 0, usageError(
				"rpc-count requires rpc-method.",
			)
		}
		return "", 0, nil
	}
	if method != "device.info" && method != "recording.list" {
		return "", 0, usageError(
			"rpc-method must be device.info or recording.list.",
		)
	}
	count := 1
	if rawCount != "" {
		parsed, err := strconv.Atoi(rawCount)
		if err != nil || parsed < 1 || parsed > 20 {
			return "", 0, usageError("rpc-count must be between 1 and 20.")
		}
		count = parsed
	}
	return method, count, nil
}

func selectLANCandidate(
	selected lan.NetworkInterface,
	host string,
) (lan.AddressCandidate, error) {
	for _, candidate := range selected.Candidates {
		if candidate.Host == host {
			return candidate, nil
		}
	}
	return lan.AddressCandidate{}, usageError(
		"address must be an exact candidate of the selected interface.",
	)
}

func publicLANError(err error) error {
	var lanError *lan.Error
	if !errors.As(err, &lanError) {
		return err
	}
	retryable := lanError.Code == lan.CodeAcceptTimeout ||
		lanError.Code == lan.CodeConnectionClosed ||
		lanError.Code == lan.CodeInterfaceMismatch
	return apperr.New(
		string(lanError.Code),
		safeLANErrorMessage(lanError.Code),
		retryable,
		nil,
	)
}

func lanCLIExitCode(err error) int {
	var lanError *lan.Error
	if !errors.As(err, &lanError) {
		return apperr.ExitInternal
	}
	switch lanError.Code {
	case lan.CodeAcceptTimeout:
		return apperr.ExitTimeout
	case lan.CodeInterfaceUnavailable,
		lan.CodeInterfaceAmbiguous,
		lan.CodeAddressInvalid,
		lan.CodeAddressUnspecified,
		lan.CodeAddressMulticast,
		lan.CodeAddressNotPrivate,
		lan.CodeAddressScopeMismatch,
		lan.CodeListenerBindFailed,
		lan.CodeInvitationTTLInvalid:
		return apperr.ExitArgument
	case lan.CodeCancelled:
		return apperr.ExitInternal
	default:
		return apperr.ExitDevice
	}
}

func safeLANErrorMessage(code lan.Code) string {
	switch code {
	case lan.CodeAcceptTimeout:
		return "LAN listener timed out before authentication completed."
	case lan.CodeCancelled:
		return "LAN listener was cancelled."
	case lan.CodeInterfaceMismatch:
		return "The selected LAN interface changed or is unavailable."
	case lan.CodeInterfaceUnavailable, lan.CodeInterfaceAmbiguous:
		return "An explicit eligible LAN interface is required."
	case lan.CodeAddressInvalid,
		lan.CodeAddressUnspecified,
		lan.CodeAddressMulticast,
		lan.CodeAddressNotPrivate,
		lan.CodeAddressScopeMismatch:
		return "The selected LAN address is not eligible."
	case lan.CodeListenerBindFailed:
		return "The selected LAN address could not be bound."
	case lan.CodeInvitationExpired:
		return "The LAN invitation expired."
	default:
		return "The LAN listener failed closed."
	}
}

const (
	defaultLANInvitationTTL = 300 * time.Second
	defaultLANAcceptTimeout = 300 * time.Second
)
