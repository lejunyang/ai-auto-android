package bridge

// 测试用途：本文件验证会话建立失败回滚、过期清理、幂等键和录制 RPC 编排。

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/visual"
)

func TestServiceOpenStoresSessionWithoutReturningToken(t *testing.T) {
	forwarder := &fakeForwarder{
		forward: adb.Forward{Device: "SERIAL", LocalPort: 41237, RemotePort: AndroidBridgePort},
	}
	client := &fakeRPCClient{
		hello: HelloResult{ServerVersion: "0.1.0", SelectedProtocolVersion: "1.0"},
		opened: SessionOpenResult{
			Token:           "random-token-value-that-is-longer-than-32-bytes",
			ExpiresAt:       "2026-07-18T00:15:00Z",
			ProtocolVersion: "1.0",
		},
	}
	store := newMemoryStore()
	service := testService(forwarder, client, store)

	result, err := service.Open(context.Background(), "SERIAL", "123456")
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if result.LocalPort != 41237 || result.ProtocolVersion != "1.0" {
		t.Fatalf("result = %#v", result)
	}
	encoded, _ := json.Marshal(result)
	if string(encoded) == "" || containsSecret(string(encoded), client.opened.Token, "123456") {
		t.Fatalf("open result leaked secret: %s", encoded)
	}
	if store.session.Token != client.opened.Token {
		t.Fatal("session token was not persisted")
	}
	if len(forwarder.removed) != 0 {
		t.Fatalf("forward removed after success: %#v", forwarder.removed)
	}
}

func TestServiceOpenRejectsSessionLongerThanProtocolLimitAndCleansForward(t *testing.T) {
	forwarder := &fakeForwarder{
		forward: adb.Forward{Device: "SERIAL", LocalPort: 41237, RemotePort: AndroidBridgePort},
	}
	client := &fakeRPCClient{
		hello: HelloResult{ServerVersion: "0.1.0", SelectedProtocolVersion: "1.0"},
		opened: SessionOpenResult{
			Token:           "random-token-value-that-is-longer-than-32-bytes",
			ExpiresAt:       "2026-07-18T01:00:00Z",
			ProtocolVersion: "1.0",
		},
	}
	store := newMemoryStore()
	service := testService(forwarder, client, store)

	_, err := service.Open(context.Background(), "SERIAL", "123456")
	assertBridgeErrorCode(t, err, apperr.CodeProtocol)
	if client.callCount != 1 || client.lastMethod != "session.close" {
		t.Fatal("invalid remote session was not revoked")
	}
	if len(forwarder.removed) != 1 || store.hasSession {
		t.Fatal("invalid remote expiration was not cleaned")
	}
}

func TestServiceOpenCleansForwardWhenPairingFails(t *testing.T) {
	forwarder := &fakeForwarder{
		forward: adb.Forward{Device: "SERIAL", LocalPort: 41237, RemotePort: AndroidBridgePort},
	}
	client := &fakeRPCClient{
		openErr: apperr.New(apperr.CodeAuthInvalid, "Invalid code.", false, nil),
	}
	store := newMemoryStore()
	service := testService(forwarder, client, store)

	_, err := service.Open(context.Background(), "SERIAL", "999999")
	assertBridgeErrorCode(t, err, apperr.CodeAuthInvalid)
	if len(forwarder.removed) != 1 || forwarder.removed[0] != 41237 {
		t.Fatalf("removed forwards = %#v", forwarder.removed)
	}
	if store.hasSession {
		t.Fatal("failed open persisted a session")
	}
}

func TestServiceRejectsExpiredLocalTokenAndCleansForward(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{}
	store := newMemoryStore()
	store.Save(Session{
		Device:     "SERIAL",
		LocalPort:  41237,
		RemotePort: AndroidBridgePort,
		Token:      "random-token-value-that-is-longer-than-32-bytes",
		ExpiresAt:  time.Date(2026, 7, 17, 23, 59, 0, 0, time.UTC),
		OpenedAt:   time.Date(2026, 7, 17, 23, 44, 0, 0, time.UTC),
		HostName:   "test-host",
	})
	service := testService(forwarder, client, store)

	_, err := service.Info(context.Background(), "SERIAL")
	assertBridgeErrorCode(t, err, apperr.CodeAuthExpired)
	if client.callCount != 0 {
		t.Fatalf("RPC calls = %d, want none", client.callCount)
	}
	if store.hasSession || len(forwarder.removed) != 1 {
		t.Fatal("expired session was not fully cleaned")
	}
}

func TestServiceCloseCleansForwardEvenWhenPeerDisconnected(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{
		callErr: apperr.New(
			apperr.CodeDeviceUnreachable,
			"disconnected",
			true,
			nil,
		),
	}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	result, err := service.Close(context.Background(), "SERIAL")
	assertBridgeErrorCode(t, err, apperr.CodeDeviceUnreachable)
	if !result.ForwardRemoved || store.hasSession {
		t.Fatalf("cleanup result = %#v, hasSession = %v", result, store.hasSession)
	}
}

func TestServiceCloseKeepsForwardWhenSessionDeleteFails(t *testing.T) {
	operations := make([]string, 0, 2)
	forwarder := &fakeForwarder{operations: &operations}
	client := &fakeRPCClient{}
	store := newMemoryStore()
	store.operations = &operations
	store.deleteErr = apperr.New(
		apperr.CodeInternal,
		"delete failed",
		false,
		nil,
	)
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	result, err := service.Close(context.Background(), "SERIAL")
	assertBridgeErrorCode(t, err, apperr.CodeInternal)
	if len(forwarder.removed) != 0 {
		t.Fatalf("removed forwards = %#v, want none", forwarder.removed)
	}
	if !store.hasSession {
		t.Fatal("delete failure discarded the retryable local session")
	}
	if result.ForwardRemoved {
		t.Fatalf("result = %#v, forward was not removed", result)
	}
	if len(operations) != 1 || operations[0] != "store.delete" {
		t.Fatalf("operations = %#v, want only store.delete", operations)
	}
}

func TestServiceCloseDeletesSessionBeforeRemovingForward(t *testing.T) {
	operations := make([]string, 0, 2)
	forwarder := &fakeForwarder{operations: &operations}
	client := &fakeRPCClient{}
	store := newMemoryStore()
	store.operations = &operations
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	result, err := service.Close(context.Background(), "SERIAL")
	if err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if !result.SessionClosed || !result.ForwardRemoved {
		t.Fatalf("result = %#v", result)
	}
	want := []string{"store.delete", "forward.remove"}
	if !reflect.DeepEqual(operations, want) {
		t.Fatalf("operations = %#v, want %#v", operations, want)
	}
}

func TestServiceCloseDoesNotRestoreSessionWhenForwardRemovalFails(t *testing.T) {
	forwardErr := apperr.New(
		apperr.CodeDeviceUnreachable,
		"forward cleanup failed",
		true,
		nil,
	)
	forwarder := &fakeForwarder{removeErr: forwardErr}
	client := &fakeRPCClient{}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	result, err := service.Close(context.Background(), "SERIAL")
	assertBridgeErrorCode(t, err, apperr.CodeDeviceUnreachable)
	if store.hasSession {
		t.Fatal("forward failure restored a local session whose token was already revoked")
	}
	if !result.SessionClosed || result.ForwardRemoved {
		t.Fatalf("result = %#v", result)
	}
}

func TestServiceCloseIsIdempotentOnSecondCall(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	first, err := service.Close(context.Background(), "SERIAL")
	if err != nil {
		t.Fatalf("first Close() error = %v", err)
	}
	if !first.SessionClosed || !first.ForwardRemoved || first.AlreadyClean {
		t.Fatalf("first result = %#v", first)
	}

	result, err := service.Close(context.Background(), "SERIAL")
	if err != nil {
		t.Fatalf("second Close() error = %v", err)
	}
	if result.Device != "SERIAL" || !result.ForwardRemoved || !result.AlreadyClean {
		t.Fatalf("second result = %#v", result)
	}
	if client.callCount != 1 || len(forwarder.removed) != 1 {
		t.Fatalf("RPC calls = %d, removed forwards = %#v", client.callCount, forwarder.removed)
	}
}

func TestServiceClosePreservesRemoteErrors(t *testing.T) {
	tests := []struct {
		name string
		code string
	}{
		{name: "auth invalid", code: apperr.CodeAuthInvalid},
		{name: "auth expired", code: apperr.CodeAuthExpired},
		{name: "protocol", code: apperr.CodeProtocol},
		{name: "remote internal", code: apperr.CodeInternal},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			forwarder := &fakeForwarder{}
			client := &fakeRPCClient{
				callErr: apperr.New(test.code, "remote close failed", false, nil),
			}
			store := newMemoryStore()
			store.Save(validSessionAt("2026-07-18T00:15:00Z"))
			service := testService(forwarder, client, store)

			result, err := service.Close(context.Background(), "SERIAL")
			assertBridgeErrorCode(t, err, test.code)
			if result.SessionClosed || !result.ForwardRemoved {
				t.Fatalf("result = %#v", result)
			}
			if store.hasSession || len(forwarder.removed) != 1 {
				t.Fatal("remote error did not clean local state")
			}
		})
	}
}

func TestServiceRemoteAuthFailureRemovesStaleLocalSessionAndForward(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{
		callErr: apperr.New(apperr.CodeAuthExpired, "expired", false, nil),
	}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	_, err := service.Info(context.Background(), "SERIAL")
	assertBridgeErrorCode(t, err, apperr.CodeAuthExpired)
	if store.hasSession || len(forwarder.removed) != 1 {
		t.Fatal("stale remote session was not cleaned")
	}
}

func TestServiceActionAddsIdempotencyKey(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{callResult: json.RawMessage(`{"route":"globalAction"}`)}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	result, err := service.Action(
		context.Background(),
		"SERIAL",
		json.RawMessage(`{"type":"ui.back","params":{}}`),
	)
	if err != nil {
		t.Fatalf("Action() error = %v", err)
	}
	if string(result) != `{"route":"globalAction"}` {
		t.Fatalf("result = %s", result)
	}
	params, ok := client.lastParams.(map[string]any)
	if !ok || params["idempotencyKey"] == "" {
		t.Fatalf("params = %#v", client.lastParams)
	}
}

func TestServiceAttestedVisualActionClearsImageAndNeverRetriesUnknownCommit(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{
		callErr: apperr.New(
			apperr.CodeDeviceUnreachable,
			"connection reset after write",
			true,
			nil,
		),
	}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)
	image := []byte("private-png")
	request := visual.AttestedActionPortRequest{
		DeviceSerial:      "SERIAL",
		DeviceFingerprint: strings.Repeat("a", 64),
		TargetPackage:     "com.example.notes",
		Observation: visual.Observation{
			ID:                "019fbcaa-0000-7000-8000-000000000047",
			ForegroundPackage: "com.example.notes",
		},
		Candidate: visual.Candidate{
			ObservationID: "019fbcaa-0000-7000-8000-000000000047",
		},
		PNGBytes: image,
	}

	_, err := service.ExecuteAttestedVisualAction(
		context.Background(),
		request,
	)

	status, code, ok := visual.AttestedActionPortErrorStatus(err)
	if !ok || status != visual.ActionCommitUnknown ||
		code != visual.CodeActionCommitUnknown {
		t.Fatalf("error status=%q code=%q ok=%v error=%v", status, code, ok, err)
	}
	if client.callCount != 1 || client.lastMethod != "visual.action.execute" {
		t.Fatalf("calls=%d method=%q", client.callCount, client.lastMethod)
	}
	if !allBridgeZero(image) {
		t.Fatal("attested action image was not cleared")
	}
}

func TestServiceAttestedVisualActionRejectsMissingSessionBeforeRPC(t *testing.T) {
	client := &fakeRPCClient{}
	service := testService(&fakeForwarder{}, client, newMemoryStore())
	image := []byte("private-png")
	request := visual.AttestedActionPortRequest{
		DeviceSerial:      "SERIAL",
		DeviceFingerprint: strings.Repeat("a", 64),
		TargetPackage:     "com.example.notes",
		Observation: visual.Observation{
			ID:                "019fbcaa-0000-7000-8000-000000000047",
			ForegroundPackage: "com.example.notes",
		},
		Candidate: visual.Candidate{
			ObservationID: "019fbcaa-0000-7000-8000-000000000047",
		},
		PNGBytes: image,
	}

	_, err := service.ExecuteAttestedVisualAction(
		context.Background(),
		request,
	)

	status, code, ok := visual.AttestedActionPortErrorStatus(err)
	if !ok || status != visual.ActionCommitNotCommitted ||
		code != visual.CodeActionNotAuthorized {
		t.Fatalf("error status=%q code=%q ok=%v error=%v", status, code, ok, err)
	}
	if client.callCount != 0 || !allBridgeZero(image) {
		t.Fatalf("rpc calls=%d image=%q", client.callCount, image)
	}
}

func TestServiceReplayAddsIdempotencyKeyAndScriptID(t *testing.T) {
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{
		callResult: json.RawMessage(`{
			"scriptId":"123e4567-e89b-42d3-a456-426614174000",
			"startedAtMs":10,
			"finishedAtMs":20,
			"succeeded":true,
			"requiresIntervention":false,
			"steps":[]
		}`),
	}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	_, err := service.Replay(
		context.Background(),
		"SERIAL",
		"123e4567-e89b-42d3-a456-426614174000",
	)
	if err != nil {
		t.Fatalf("Replay() error = %v", err)
	}
	if client.lastMethod != "recording.replay" {
		t.Fatalf("method = %q", client.lastMethod)
	}
	params, ok := client.lastParams.(map[string]any)
	if !ok ||
		params["scriptId"] != "123e4567-e89b-42d3-a456-426614174000" ||
		params["idempotencyKey"] == "" {
		t.Fatalf("params = %#v", client.lastParams)
	}
}

func TestServiceListRecordingsUsesStoredSessionAndEmptyParams(t *testing.T) {
	fixture, err := os.ReadFile("testdata/recording-list-result.json")
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	forwarder := &fakeForwarder{}
	client := &fakeRPCClient{callResult: fixture}
	store := newMemoryStore()
	store.Save(validSessionAt("2026-07-18T00:15:00Z"))
	service := testService(forwarder, client, store)

	result, err := service.ListRecordings(context.Background(), "SERIAL")
	if err != nil {
		t.Fatalf("ListRecordings() error = %v", err)
	}
	if string(result) != string(fixture) {
		t.Fatalf("result = %s, want fixture %s", result, fixture)
	}
	if client.lastMethod != "recording.list" {
		t.Fatalf("method = %q", client.lastMethod)
	}
	if client.lastPort != 41237 ||
		client.lastToken != "random-token-value-that-is-longer-than-32-bytes" {
		t.Fatalf("session call port=%d token=%q", client.lastPort, client.lastToken)
	}
	params, ok := client.lastParams.(map[string]any)
	if !ok || len(params) != 0 {
		t.Fatalf("params = %#v", client.lastParams)
	}
}

func testService(
	forwarder *fakeForwarder,
	client *fakeRPCClient,
	store *memoryStore,
) *Service {
	service := NewService(forwarder, client, store)
	service.now = func() time.Time {
		return time.Date(2026, 7, 18, 0, 0, 0, 0, time.UTC)
	}
	service.hostName = func() (string, error) { return "test-host", nil }
	return service
}

func validSessionAt(expiresAt string) Session {
	expiration, _ := time.Parse(time.RFC3339, expiresAt)
	return Session{
		Device:     "SERIAL",
		LocalPort:  41237,
		RemotePort: AndroidBridgePort,
		Token:      "random-token-value-that-is-longer-than-32-bytes",
		ExpiresAt:  expiration,
		OpenedAt:   time.Date(2026, 7, 18, 0, 0, 0, 0, time.UTC),
		HostName:   "test-host",
	}
}

func containsSecret(value string, secrets ...string) bool {
	for _, secret := range secrets {
		if secret != "" && strings.Contains(value, secret) {
			return true
		}
	}
	return false
}

type fakeForwarder struct {
	forward    adb.Forward
	forwardErr error
	removeErr  error
	removed    []int
	operations *[]string
}

func (f *fakeForwarder) Forward(
	_ context.Context,
	_ string,
	_ int,
) (adb.Forward, error) {
	return f.forward, f.forwardErr
}

func (f *fakeForwarder) RemoveForward(
	_ context.Context,
	_ string,
	localPort int,
) error {
	if f.operations != nil {
		*f.operations = append(*f.operations, "forward.remove")
	}
	f.removed = append(f.removed, localPort)
	return f.removeErr
}

type fakeRPCClient struct {
	hello      HelloResult
	opened     SessionOpenResult
	openErr    error
	callResult json.RawMessage
	callErr    error
	callCount  int
	lastMethod string
	lastParams any
	lastPort   int
	lastToken  string
}

func (f *fakeRPCClient) Open(
	_ context.Context,
	_ int,
	_ string,
	_ string,
) (HelloResult, SessionOpenResult, error) {
	return f.hello, f.opened, f.openErr
}

func (f *fakeRPCClient) Call(
	_ context.Context,
	localPort int,
	token string,
	method string,
	params any,
	result any,
) error {
	f.callCount++
	f.lastMethod = method
	f.lastParams = params
	f.lastPort = localPort
	f.lastToken = token
	if f.callErr != nil {
		return f.callErr
	}
	switch destination := result.(type) {
	case *json.RawMessage:
		*destination = append((*destination)[:0], f.callResult...)
	case *SessionCloseResult:
		destination.Closed = true
	case *visual.AttestedActionPortResult:
		if len(f.callResult) != 0 {
			_ = json.Unmarshal(f.callResult, destination)
		}
	}
	return nil
}

func allBridgeZero(value []byte) bool {
	for _, item := range value {
		if item != 0 {
			return false
		}
	}
	return true
}

type memoryStore struct {
	session    Session
	hasSession bool
	saveErr    error
	deleteErr  error
	operations *[]string
}

func newMemoryStore() *memoryStore {
	return &memoryStore{}
}

func (s *memoryStore) Save(session Session) error {
	if s.saveErr != nil {
		return s.saveErr
	}
	s.session = session
	s.hasSession = true
	return nil
}

func (s *memoryStore) Load(device string) (Session, error) {
	if !s.hasSession {
		return Session{}, apperr.New(
			apperr.CodeAuthRequired,
			"missing",
			false,
			nil,
		)
	}
	if s.session.Device != device {
		return Session{}, errors.New("device mismatch")
	}
	return s.session, nil
}

func (s *memoryStore) Delete(string) error {
	if s.operations != nil {
		*s.operations = append(*s.operations, "store.delete")
	}
	if s.deleteErr != nil {
		return s.deleteErr
	}
	s.hasSession = false
	return nil
}
