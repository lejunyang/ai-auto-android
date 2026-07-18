package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/output"
)

type Forwarder interface {
	Forward(ctx context.Context, serial string, remotePort int) (adb.Forward, error)
	RemoveForward(ctx context.Context, serial string, localPort int) error
}

type RPCClient interface {
	Open(
		ctx context.Context,
		localPort int,
		pairingCode string,
		hostName string,
	) (HelloResult, SessionOpenResult, error)
	Call(
		ctx context.Context,
		localPort int,
		token string,
		method string,
		params any,
		result any,
	) error
}

type Service struct {
	forwarder Forwarder
	client    RPCClient
	store     SessionStore
	now       func() time.Time
	hostName  func() (string, error)
}

type OpenResult struct {
	Device          string `json:"device"`
	LocalPort       int    `json:"localPort"`
	RemotePort      int    `json:"remotePort"`
	ExpiresAt       string `json:"expiresAt"`
	ProtocolVersion string `json:"protocolVersion"`
	ServerVersion   string `json:"serverVersion"`
}

type CloseResult struct {
	Device         string `json:"device"`
	SessionClosed  bool   `json:"sessionClosed"`
	ForwardRemoved bool   `json:"forwardRemoved"`
}

func NewService(forwarder Forwarder, client RPCClient, store SessionStore) *Service {
	return &Service{
		forwarder: forwarder,
		client:    client,
		store:     store,
		now:       time.Now,
		hostName:  os.Hostname,
	}
}

func (s *Service) Open(
	ctx context.Context,
	device string,
	pairingCode string,
) (OpenResult, error) {
	if existing, err := s.store.Load(device); err == nil {
		var closed SessionCloseResult
		_ = s.client.Call(
			ctx,
			existing.LocalPort,
			existing.Token,
			"session.close",
			map[string]any{},
			&closed,
		)
		if cleanupErr := s.cleanup(context.WithoutCancel(ctx), existing); cleanupErr != nil {
			return OpenResult{}, cleanupErr
		}
	} else if !isAuthRequired(err) {
		return OpenResult{}, err
	}

	forward, err := s.forwarder.Forward(ctx, device, AndroidBridgePort)
	if err != nil {
		return OpenResult{}, err
	}
	cleanupForward := true
	openedToken := ""
	defer func() {
		if cleanupForward {
			if openedToken != "" {
				closeContext, cancelClose := context.WithTimeout(
					context.WithoutCancel(ctx),
					3*time.Second,
				)
				var closed SessionCloseResult
				_ = s.client.Call(
					closeContext,
					forward.LocalPort,
					openedToken,
					"session.close",
					map[string]any{},
					&closed,
				)
				cancelClose()
			}
			cleanupContext, cancel := context.WithTimeout(
				context.WithoutCancel(ctx),
				5*time.Second,
			)
			defer cancel()
			_ = s.forwarder.RemoveForward(cleanupContext, device, forward.LocalPort)
		}
	}()

	hostName, err := s.hostName()
	if err != nil || hostName == "" {
		hostName = "aactl-desktop"
	}
	hello, opened, err := s.client.Open(
		ctx,
		forward.LocalPort,
		pairingCode,
		hostName,
	)
	if err != nil {
		return OpenResult{}, err
	}
	openedToken = opened.Token
	expiresAt, err := time.Parse(time.RFC3339Nano, opened.ExpiresAt)
	if err != nil {
		return OpenResult{}, protocolError("session expiration is invalid")
	}
	now := s.now()
	if !expiresAt.After(now) {
		return OpenResult{}, apperr.New(
			apperr.CodeAuthExpired,
			"The Android bridge returned an expired session.",
			false,
			nil,
		)
	}
	if expiresAt.After(now.Add((SessionTokenTTL + 5) * time.Second)) {
		return OpenResult{}, protocolError("session expiration exceeds the negotiated limit")
	}
	session := Session{
		Device:     device,
		LocalPort:  forward.LocalPort,
		RemotePort: forward.RemotePort,
		Token:      opened.Token,
		ExpiresAt:  expiresAt,
		OpenedAt:   now.UTC(),
		HostName:   hostName,
	}
	if err := s.store.Save(session); err != nil {
		return OpenResult{}, err
	}
	cleanupForward = false
	return OpenResult{
		Device:          device,
		LocalPort:       forward.LocalPort,
		RemotePort:      forward.RemotePort,
		ExpiresAt:       expiresAt.UTC().Format(time.RFC3339),
		ProtocolVersion: opened.ProtocolVersion,
		ServerVersion:   hello.ServerVersion,
	}, nil
}

func (s *Service) Info(ctx context.Context, device string) (json.RawMessage, error) {
	return s.call(ctx, device, "device.info", map[string]any{})
}

func (s *Service) Snapshot(
	ctx context.Context,
	device string,
	targetPackage string,
	maxDepth int,
) (json.RawMessage, error) {
	params := make(map[string]any)
	if targetPackage != "" {
		params["targetPackage"] = targetPackage
	}
	if maxDepth != 0 {
		params["maxDepth"] = maxDepth
	}
	return s.call(ctx, device, "ui.snapshot", params)
}

func (s *Service) Action(
	ctx context.Context,
	device string,
	action json.RawMessage,
) (json.RawMessage, error) {
	var actionObject map[string]any
	if err := json.Unmarshal(action, &actionObject); err != nil {
		return nil, apperr.Wrap(
			apperr.CodeInvalidArgument,
			"Action must be one JSON object.",
			false,
			err,
		)
	}
	idempotencyKey, err := newRequestID()
	if err != nil {
		return nil, err
	}
	return s.call(
		ctx,
		device,
		"action.execute",
		map[string]any{
			"action":         actionObject,
			"idempotencyKey": idempotencyKey,
		},
	)
}

func (s *Service) Close(ctx context.Context, device string) (CloseResult, error) {
	session, err := s.store.Load(device)
	if err != nil {
		return CloseResult{}, err
	}

	var closeResult SessionCloseResult
	rpcErr := s.client.Call(
		ctx,
		session.LocalPort,
		session.Token,
		"session.close",
		map[string]any{},
		&closeResult,
	)
	cleanupErr := s.cleanup(context.WithoutCancel(ctx), session)
	result := CloseResult{
		Device:         device,
		SessionClosed:  rpcErr == nil && closeResult.Closed,
		ForwardRemoved: cleanupErr == nil,
	}
	if rpcErr != nil {
		return result, rpcErr
	}
	if cleanupErr != nil {
		return result, cleanupErr
	}
	return result, nil
}

func (s *Service) call(
	ctx context.Context,
	device string,
	method string,
	params any,
) (json.RawMessage, error) {
	session, err := s.store.Load(device)
	if err != nil {
		return nil, err
	}
	if !s.now().Before(session.ExpiresAt) {
		_ = s.cleanup(context.WithoutCancel(ctx), session)
		return nil, apperr.New(
			apperr.CodeAuthExpired,
			"The local bridge session has expired. Run bridge open again.",
			false,
			map[string]any{"device": device},
		)
	}
	var result json.RawMessage
	if err := s.client.Call(
		ctx,
		session.LocalPort,
		session.Token,
		method,
		params,
		&result,
	); err != nil {
		if isSessionAuthError(err) {
			_ = s.cleanup(context.WithoutCancel(ctx), session)
		}
		return nil, err
	}
	return result, nil
}

func (s *Service) cleanup(ctx context.Context, session Session) error {
	cleanupContext, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	forwardErr := s.forwarder.RemoveForward(
		cleanupContext,
		session.Device,
		session.LocalPort,
	)
	storeErr := s.store.Delete(session.Device)
	if forwardErr != nil && storeErr != nil {
		return errors.Join(forwardErr, storeErr)
	}
	if forwardErr != nil {
		return forwardErr
	}
	return storeErr
}

func isAuthRequired(err error) bool {
	var appError *apperr.Error
	return errors.As(err, &appError) && appError.Code == apperr.CodeAuthRequired
}

func isSessionAuthError(err error) bool {
	var appError *apperr.Error
	if !errors.As(err, &appError) {
		return false
	}
	return appError.Code == apperr.CodeAuthInvalid ||
		appError.Code == apperr.CodeAuthExpired
}

func newRequestID() (string, error) {
	value, err := output.RequestID()
	if err != nil {
		return "", apperr.Wrap(
			apperr.CodeInternal,
			"Could not generate an idempotency key.",
			false,
			err,
		)
	}
	return value, nil
}
