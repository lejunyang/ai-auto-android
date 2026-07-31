// Package main 提供仅供 N37/N38 设备验收使用的固定 AUTH_INVALID 跨运行时 host。
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/bridge/lan"
	"github.com/lejunyang/ai-auto-android/internal/output"
)

type invitationEvent struct {
	Phase          string          `json:"phase"`
	InvitationJSON json.RawMessage `json:"invitationJson"`
	Fingerprint    string          `json:"fingerprint"`
}

type resultEvent struct {
	Phase      string `json:"phase"`
	ErrorCode  string `json:"errorCode"`
	PeerClosed bool   `json:"peerClosed"`
}

func main() {
	if err := run(context.Background(), os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, publicError(err))
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	options := flag.NewFlagSet("auth-invalid-host", flag.ContinueOnError)
	options.SetOutput(os.Stderr)
	interfaceID := options.String("interface", "", "explicit production LAN interface ID")
	address := options.String("address", "", "explicit private IP literal")
	if err := options.Parse(args); err != nil {
		return errors.New("LAN_INTEROP_USAGE_INVALID")
	}
	if options.NArg() != 0 || *interfaceID == "" || *address == "" {
		return errors.New("LAN_INTEROP_USAGE_INVALID")
	}
	interfaces, err := lan.DiscoverInterfaces(lan.SystemInterfaceSource{})
	if err != nil {
		return err
	}
	selected, err := lan.SelectInterface(interfaces, *interfaceID)
	if err != nil {
		return err
	}
	var candidate lan.AddressCandidate
	found := false
	for _, current := range selected.Candidates {
		if current.Host == *address {
			candidate = current
			found = true
			break
		}
	}
	if !found {
		return errors.New("LAN_INTEROP_ADDRESS_INVALID")
	}
	pending, err := lan.StartListener(ctx, lan.ListenerOptions{
		InterfaceSource: lan.SystemInterfaceSource{},
		Binder:          lan.SystemBinder{},
		Selected:        selected,
		Candidate:       candidate,
		TTL:             90 * time.Second,
		AcceptTimeout:   60 * time.Second,
		Capabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
	})
	if err != nil {
		return err
	}
	defer pending.Close()

	bundle := pending.Bundle()
	defer clear(bundle.Payload)
	if err := writeEvent(invitationEvent{
		Phase:          "invitation",
		InvitationJSON: append(json.RawMessage(nil), bundle.Payload...),
		Fingerprint:    bundle.Invitation.Fingerprint,
	}); err != nil {
		return err
	}
	session, err := pending.Accept(ctx, lan.HandshakePolicy{
		AllowedCapabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
	})
	if err != nil {
		return err
	}
	defer session.Close()

	requestID, err := output.RequestID()
	if err != nil {
		return err
	}
	rpcID, err := output.RequestID()
	if err != nil {
		return err
	}
	wrongTokenBytes := make([]byte, 32)
	wrongToken := base64.RawURLEncoding.EncodeToString(wrongTokenBytes)
	clear(wrongTokenBytes)
	request := bridge.Request{
		JSONRPC:         "2.0",
		ID:              rpcID,
		RequestID:       requestID,
		ProtocolVersion: bridge.ProtocolVersion,
		Method:          "device.info",
		Params:          map[string]any{},
		DeadlineMS:      bridge.DefaultDeadlineMS,
		Token:           wrongToken,
	}
	payload, err := json.Marshal(request)
	request.Token = ""
	wrongToken = ""
	if err != nil {
		return err
	}
	defer clear(payload)
	callContext, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err := session.WriteFrame(callContext, "bridge.request", payload); err != nil {
		return err
	}
	frameType, responsePayload, err := session.ReadFrame(callContext)
	if err != nil {
		return err
	}
	defer clear(responsePayload)
	if frameType != "bridge.response" {
		return errors.New("LAN_INTEROP_RESPONSE_INVALID")
	}
	var response bridge.Response
	if err := json.Unmarshal(responsePayload, &response); err != nil {
		return errors.New("LAN_INTEROP_RESPONSE_INVALID")
	}
	if err := bridge.ValidateResponse(response, request); err != nil {
		return errors.New("LAN_INTEROP_RESPONSE_INVALID")
	}
	var result map[string]any
	responseErr := response.DecodeResult(&result)
	var appError *apperr.Error
	if !errors.As(responseErr, &appError) || appError.Code != apperr.CodeAuthInvalid {
		return errors.New("LAN_INTEROP_AUTH_RESULT_INVALID")
	}
	peerContext, peerCancel := context.WithTimeout(ctx, 5*time.Second)
	defer peerCancel()
	_, peerPayload, peerErr := session.ReadFrame(peerContext)
	clear(peerPayload)
	peerClosed := peerErr != nil &&
		(lan.ErrorCode(peerErr) == lan.CodeConnectionClosed ||
			lan.ErrorCode(peerErr) == lan.CodeSessionClosed)
	if !peerClosed {
		return errors.New("LAN_INTEROP_PEER_NOT_CLOSED")
	}
	return writeEvent(resultEvent{
		Phase:      "result",
		ErrorCode:  apperr.CodeAuthInvalid,
		PeerClosed: true,
	})
}

func writeEvent(event any) error {
	payload, err := json.Marshal(event)
	if err != nil {
		return err
	}
	defer clear(payload)
	if len(payload) > 128*1024 {
		return errors.New("LAN_INTEROP_OUTPUT_LIMIT")
	}
	_, err = os.Stdout.Write(append(payload, '\n'))
	return err
}

func publicError(err error) string {
	if code := lan.ErrorCode(err); code != "" {
		return string(code)
	}
	return err.Error()
}
