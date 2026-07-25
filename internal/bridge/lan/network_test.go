package lan

// 测试用途：本文件验证网卡必须显式选择，并拒绝 VPN、歧义、错误归属和 wildcard 绑定。

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
)

func TestDiscoverInterfacesEnumeratesOnlyPrivateAndLinkLocalCandidates(t *testing.T) {
	source := &fakeInterfaceSource{interfaces: []InterfaceSnapshot{
		{
			Index: 7,
			Name:  "en0",
			Up:    true,
			Addresses: []string{
				"192.168.50.12/24",
				"169.254.31.7/16",
				"fd12:3456:789a::12/64",
				"fe80::aede:48ff:fe00:1122/64",
				"8.8.8.8/32",
				"127.0.0.1/8",
			},
		},
		{
			Index:     8,
			Name:      "utun4",
			Up:        true,
			Addresses: []string{"10.8.0.2/24"},
		},
		{
			Index:     9,
			Name:      "en9",
			Up:        false,
			Addresses: []string{"192.168.99.2/24"},
		},
	}}

	interfaces, err := DiscoverInterfaces(source)
	if err != nil {
		t.Fatalf("DiscoverInterfaces() error = %v", err)
	}
	if len(interfaces) != 2 {
		t.Fatalf("interface count = %d, want 2 (wifi + diagnosed VPN)", len(interfaces))
	}
	wifi := interfaces[0]
	if wifi.ID != "if-7-en0" || wifi.Name != "en0" || wifi.Kind != "wifi" || wifi.Tunnel {
		t.Fatalf("wifi interface = %#v", wifi)
	}
	if got, want := len(wifi.Candidates), 4; got != want {
		t.Fatalf("candidate count = %d, want %d", got, want)
	}
	if wifi.Candidates[3].ZoneID != "en0" {
		t.Fatalf("IPv6 link-local zone = %q, want en0", wifi.Candidates[3].ZoneID)
	}
	if !interfaces[1].Tunnel {
		t.Fatal("utun interface was not diagnosed as a tunnel")
	}
}

func TestSelectInterfaceFailsClosedForMissingAmbiguousVPNAndUnknownSelection(t *testing.T) {
	available := []NetworkInterface{
		{ID: "if-7-en0", Name: "en0", Kind: "wifi", Candidates: privateCandidates("if-7-en0", "en0", "192.168.50.12")},
		{ID: "if-8-en7", Name: "en7", Kind: "ethernet", Candidates: privateCandidates("if-8-en7", "en7", "192.168.60.12")},
		{ID: "if-9-utun4", Name: "utun4", Kind: "other", Tunnel: true, Candidates: privateCandidates("if-9-utun4", "utun4", "10.8.0.2")},
	}

	cases := []struct {
		name string
		id   string
		code Code
	}{
		{name: "missing explicit selection", code: CodeInterfaceAmbiguous},
		{name: "VPN selection", id: "if-9-utun4", code: CodeInterfaceUnavailable},
		{name: "unknown selection", id: "if-99-missing", code: CodeInterfaceUnavailable},
	}
	for _, test := range cases {
		test := test
		t.Run(test.name, func(t *testing.T) {
			_, err := SelectInterface(available, test.id)
			if got := ErrorCode(err); got != test.code {
				t.Fatalf("error code = %q, want %q (error=%v)", got, test.code, err)
			}
		})
	}

	selected, err := SelectInterface(available, "if-7-en0")
	if err != nil {
		t.Fatalf("SelectInterface(explicit) error = %v", err)
	}
	if selected.ID != "if-7-en0" {
		t.Fatalf("selected ID = %q", selected.ID)
	}
}

func TestSystemBinderNeverReceivesWildcardAddress(t *testing.T) {
	recorder := &recordingListenConfig{}
	binder := SystemBinder{listen: recorder.listen}
	selected := NetworkInterface{
		ID:         "if-7-en0",
		Name:       "en0",
		Kind:       "wifi",
		Candidates: privateCandidates("if-7-en0", "en0", "192.168.50.12"),
	}

	_, err := binder.Listen(context.Background(), selected, selected.Candidates[0], 47831)
	if !errors.Is(err, errExpectedListen) {
		t.Fatalf("Listen() error = %v, want sentinel", err)
	}
	if recorder.network != "tcp4" || recorder.address != "192.168.50.12:47831" {
		t.Fatalf("listen target = %s %s", recorder.network, recorder.address)
	}

	for _, host := range []string{"0.0.0.0", "::"} {
		candidate := selected.Candidates[0]
		candidate.Host = host
		_, bindErr := binder.Listen(context.Background(), selected, candidate, 47831)
		if code := ErrorCode(bindErr); code != CodeAddressUnspecified {
			t.Fatalf("host %s error code = %q, want %q", host, code, CodeAddressUnspecified)
		}
	}
}

func TestVerifySelectedInterfaceDetectsNetworkSwitch(t *testing.T) {
	source := &fakeInterfaceSource{interfaces: []InterfaceSnapshot{{
		Index:     7,
		Name:      "en0",
		Up:        true,
		Addresses: []string{"192.168.50.12/24"},
	}}}
	selected := NetworkInterface{
		ID:         "if-7-en0",
		Name:       "en0",
		Kind:       "wifi",
		Candidates: privateCandidates("if-7-en0", "en0", "192.168.50.12"),
	}
	if err := VerifySelectedInterface(source, selected); err != nil {
		t.Fatalf("VerifySelectedInterface(stable) error = %v", err)
	}

	source.set([]InterfaceSnapshot{{
		Index:     7,
		Name:      "en0",
		Up:        true,
		Addresses: []string{"192.168.51.9/24"},
	}})
	if code := ErrorCode(VerifySelectedInterface(source, selected)); code != CodeInterfaceMismatch {
		t.Fatalf("network switch error code = %q, want %q", code, CodeInterfaceMismatch)
	}
}

func privateCandidates(interfaceID, name, host string) []AddressCandidate {
	return []AddressCandidate{{
		Host:        host,
		Family:      "ipv4",
		Scope:       "private",
		InterfaceID: interfaceID,
	}}
}

type fakeInterfaceSource struct {
	mu         sync.Mutex
	interfaces []InterfaceSnapshot
	err        error
}

func (source *fakeInterfaceSource) Interfaces() ([]InterfaceSnapshot, error) {
	source.mu.Lock()
	defer source.mu.Unlock()
	result := make([]InterfaceSnapshot, len(source.interfaces))
	copy(result, source.interfaces)
	for index := range result {
		result[index].Addresses = append([]string(nil), result[index].Addresses...)
	}
	return result, source.err
}

func (source *fakeInterfaceSource) set(interfaces []InterfaceSnapshot) {
	source.mu.Lock()
	defer source.mu.Unlock()
	source.interfaces = interfaces
}

var errExpectedListen = errors.New("expected listen call")

type recordingListenConfig struct {
	network string
	address string
}

func (recorder *recordingListenConfig) listen(
	_ context.Context,
	network string,
	address string,
) (net.Listener, error) {
	recorder.network = network
	recorder.address = address
	return nil, errExpectedListen
}
