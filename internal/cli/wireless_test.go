package cli

// 测试用途：验证无线 watch、可信档案和安全重连命令保持有界且不暴露配对秘密。

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

type sequenceExecutor struct {
	results []process.Result
	calls   [][]string
}

func (e *sequenceExecutor) Run(
	_ context.Context,
	_ string,
	args []string,
	_ process.Options,
) (process.Result, error) {
	e.calls = append(e.calls, append([]string(nil), args...))
	index := len(e.calls) - 1
	if index >= len(e.results) {
		return e.results[len(e.results)-1], nil
	}
	return e.results[index], nil
}

func TestDevicesWatchUsesBoundedJSONContract(t *testing.T) {
	executor := &sequenceExecutor{results: []process.Result{
		{Stdout: []byte("List of devices attached\n")},
		{Stdout: []byte("")},
	}}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{
		"devices", "watch", "--duration", "3ms", "--interval", "1ms", "--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	data := envelope.Data.(map[string]any)
	if _, ok := data["events"]; !ok {
		t.Fatalf("watch data = %#v", data)
	}
	for _, call := range executor.calls {
		if strings.Join(call, " ") == "kill-server" {
			t.Fatalf("watch invoked kill-server: %#v", executor.calls)
		}
	}
}

func TestTrustedDeviceCommandsDoNotPersistPairingCode(t *testing.T) {
	store := newCLIMemoryTrustedStore(adb.TrustedDeviceProfile{
		Identity:      "adb-device",
		Serial:        "adb-device._adb-tls-connect._tcp",
		Model:         "Pixel 8",
		LastTransport: "wifi",
		LastEndpoint:  "192.168.1.8:40117",
		MDNSInstance:  "adb-device",
		UpdatedAt:     time.Now(),
	})
	executor := &sequenceExecutor{results: []process.Result{
		{Stdout: []byte("adb-device _adb-tls-connect._tcp 192.168.1.8:40222\n")},
		{Stdout: []byte("connected to 192.168.1.8:40222\n")},
		{Stdout: []byte("List of devices attached\nadb-device._adb-tls-connect._tcp\tdevice model:Pixel_8\n")},
	}}
	app, stdout := testApp(executor, "")
	app.TrustedDevices = store
	exitCode := app.Run(context.Background(), []string{
		"devices", "reconnect", "--identity", "adb-device", "--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	payload, err := json.Marshal(store.profile)
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"pairingCode", "token", "apiKey"} {
		if bytes.Contains(payload, []byte(forbidden)) {
			t.Fatalf("trusted profile leaked %q: %s", forbidden, payload)
		}
	}
}

func TestReconnectRejectsIdentityInjectionBeforeADBOrStore(t *testing.T) {
	executor := &sequenceExecutor{results: []process.Result{{}}}
	app, stdout := testApp(executor, "")
	app.TrustedDevices = newCLIMemoryTrustedStore(adb.TrustedDeviceProfile{})
	exitCode := app.Run(context.Background(), []string{
		"devices", "reconnect", "--identity", "../device;rm", "--json",
	})
	if exitCode == 0 {
		t.Fatalf("unexpected success: %s", stdout.String())
	}
	if len(executor.calls) != 0 {
		t.Fatalf("ADB calls = %#v, want none", executor.calls)
	}
}

type cliMemoryTrustedStore struct {
	profile adb.TrustedDeviceProfile
}

func newCLIMemoryTrustedStore(profile adb.TrustedDeviceProfile) *cliMemoryTrustedStore {
	return &cliMemoryTrustedStore{profile: profile}
}

func (s *cliMemoryTrustedStore) Save(profile adb.TrustedDeviceProfile) error {
	s.profile = profile
	return nil
}

func (s *cliMemoryTrustedStore) Load(identity string) (adb.TrustedDeviceProfile, error) {
	return s.profile, nil
}

func (s *cliMemoryTrustedStore) List() ([]adb.TrustedDeviceProfile, error) {
	return []adb.TrustedDeviceProfile{s.profile}, nil
}

func (s *cliMemoryTrustedStore) Delete(string) error {
	s.profile = adb.TrustedDeviceProfile{}
	return nil
}
