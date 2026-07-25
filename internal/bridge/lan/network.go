package lan

// 功能用途：本文件枚举可信 LAN 地址、要求明确网卡选择并限定 socket 绑定目标。

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
)

// InterfaceSnapshot 是便于测试和生产重新观察的最小网卡状态。
type InterfaceSnapshot struct {
	Index     int
	Name      string
	Up        bool
	Loopback  bool
	Addresses []string
}

// InterfaceSource 提供当前网卡快照；listener 在 accept 前会重新读取以发现切网。
type InterfaceSource interface {
	Interfaces() ([]InterfaceSnapshot, error)
}

// SystemInterfaceSource 使用标准库读取当前系统网卡及其地址。
type SystemInterfaceSource struct{}

// Interfaces 返回不解析 DNS 的系统 IPNet 地址快照。
func (SystemInterfaceSource) Interfaces() ([]InterfaceSnapshot, error) {
	systemInterfaces, err := net.Interfaces()
	if err != nil {
		return nil, wrap(CodeInterfaceUnavailable, "system interfaces could not be enumerated", err)
	}
	result := make([]InterfaceSnapshot, 0, len(systemInterfaces))
	for _, systemInterface := range systemInterfaces {
		addresses, addressErr := systemInterface.Addrs()
		if addressErr != nil {
			return nil, wrap(CodeInterfaceUnavailable, "interface addresses could not be enumerated", addressErr)
		}
		values := make([]string, 0, len(addresses))
		for _, address := range addresses {
			values = append(values, address.String())
		}
		result = append(result, InterfaceSnapshot{
			Index:     systemInterface.Index,
			Name:      systemInterface.Name,
			Up:        systemInterface.Flags&net.FlagUp != 0,
			Loopback:  systemInterface.Flags&net.FlagLoopback != 0,
			Addresses: values,
		})
	}
	return result, nil
}

// NetworkInterface 是已分类、可展示给操作员选择的 LAN 接口。
type NetworkInterface struct {
	ID         string             `json:"id"`
	Name       string             `json:"name"`
	Kind       string             `json:"kind"`
	Tunnel     bool               `json:"tunnel"`
	Candidates []AddressCandidate `json:"addressCandidates"`
}

// DiscoverInterfaces 只保留 Up、非 loopback 且至少含一个私网或链路本地地址的接口。
func DiscoverInterfaces(source InterfaceSource) ([]NetworkInterface, error) {
	if source == nil {
		return nil, fail(CodeInterfaceUnavailable, "interface source is required")
	}
	snapshots, err := source.Interfaces()
	if err != nil {
		return nil, wrap(CodeInterfaceUnavailable, "interfaces could not be read", err)
	}
	result := make([]NetworkInterface, 0, len(snapshots))
	for _, snapshot := range snapshots {
		if !snapshot.Up || snapshot.Loopback || snapshot.Index <= 0 || snapshot.Name == "" {
			continue
		}
		id := stableInterfaceID(snapshot.Index, snapshot.Name)
		candidates := make([]AddressCandidate, 0, len(snapshot.Addresses))
		for _, rawAddress := range snapshot.Addresses {
			candidate, ok := candidateFromAddress(rawAddress, id, snapshot.Name)
			if ok {
				candidates = append(candidates, candidate)
			}
		}
		if len(candidates) == 0 {
			continue
		}
		result = append(result, NetworkInterface{
			ID:         id,
			Name:       snapshot.Name,
			Kind:       classifyInterfaceKind(snapshot.Name),
			Tunnel:     isTunnelInterface(snapshot.Name),
			Candidates: candidates,
		})
	}
	slices.SortFunc(result, func(left, right NetworkInterface) int {
		return leftIndex(left.ID) - leftIndex(right.ID)
	})
	return result, nil
}

// SelectInterface 要求调用方传入明确 ID，并拒绝 tunnel/VPN 或未知接口。
func SelectInterface(available []NetworkInterface, selectedID string) (NetworkInterface, error) {
	if selectedID == "" {
		if len(available) > 1 {
			return NetworkInterface{}, fail(CodeInterfaceAmbiguous, "multiple LAN interfaces require an explicit selection")
		}
		return NetworkInterface{}, fail(CodeInterfaceUnavailable, "an explicit LAN interface selection is required")
	}
	for _, candidate := range available {
		if candidate.ID != selectedID {
			continue
		}
		if candidate.Tunnel || len(candidate.Candidates) == 0 {
			return NetworkInterface{}, fail(CodeInterfaceUnavailable, "tunnel or empty interfaces cannot host a LAN invitation")
		}
		return cloneNetworkInterface(candidate), nil
	}
	return NetworkInterface{}, fail(CodeInterfaceUnavailable, "the selected LAN interface is not available")
}

// VerifySelectedInterface 重新观察地址归属，接口或候选变化时返回切网错误。
func VerifySelectedInterface(source InterfaceSource, selected NetworkInterface) error {
	current, err := DiscoverInterfaces(source)
	if err != nil {
		return err
	}
	for _, candidate := range current {
		if candidate.ID != selected.ID ||
			candidate.Name != selected.Name ||
			candidate.Kind != selected.Kind ||
			candidate.Tunnel {
			continue
		}
		currentSet := make(map[string]struct{}, len(candidate.Candidates))
		for _, address := range candidate.Candidates {
			currentSet[addressIdentity(address)] = struct{}{}
		}
		for _, expected := range selected.Candidates {
			if _, ok := currentSet[addressIdentity(expected)]; !ok {
				return fail(CodeInterfaceMismatch, "the selected interface address changed")
			}
		}
		return nil
	}
	return fail(CodeInterfaceMismatch, "the selected interface is no longer available")
}

// ListenerBinder 抽象真实绑定行为，使协议测试可用 localhost 而不伪装为 LAN 验收。
type ListenerBinder interface {
	Listen(
		ctx context.Context,
		selected NetworkInterface,
		candidate AddressCandidate,
		port int,
	) (net.Listener, error)
}

// SystemBinder 只将验证后的单个候选地址交给 net.ListenConfig。
type SystemBinder struct {
	listen func(context.Context, string, string) (net.Listener, error)
}

// Listen 验证候选属于明确接口后绑定单个 IP literal，绝不使用 wildcard。
func (binder SystemBinder) Listen(
	ctx context.Context,
	selected NetworkInterface,
	candidate AddressCandidate,
	port int,
) (net.Listener, error) {
	if err := validateBindCandidate(selected, candidate, port); err != nil {
		return nil, err
	}
	network := "tcp6"
	host := candidate.Host
	if candidate.Family == "ipv4" {
		network = "tcp4"
	} else if candidate.Scope == "linkLocal" {
		host += "%" + candidate.ZoneID
	}
	listen := binder.listen
	if listen == nil {
		listenConfig := &net.ListenConfig{}
		listen = listenConfig.Listen
	}
	listener, err := listen(ctx, network, net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, wrap(CodeListenerBindFailed, "the selected LAN address could not be bound", err)
	}
	return listener, nil
}

func validateBindCandidate(
	selected NetworkInterface,
	candidate AddressCandidate,
	port int,
) error {
	if port < 0 || port > 65535 {
		return fail(CodeListenerBindFailed, "listener port is outside the valid range")
	}
	address, err := netip.ParseAddr(candidate.Host)
	if err != nil {
		return fail(CodeAddressInvalid, "listener host is not an IP literal")
	}
	if address.IsUnspecified() {
		return fail(CodeAddressUnspecified, "listener host cannot be unspecified")
	}
	if address.IsMulticast() {
		return fail(CodeAddressMulticast, "listener host cannot be multicast")
	}
	if address.IsLoopback() || (!address.IsPrivate() && !address.IsLinkLocalUnicast()) {
		return fail(CodeAddressNotPrivate, "listener host must be private or link-local")
	}
	if selected.Tunnel {
		return fail(CodeInterfaceUnavailable, "tunnel interfaces cannot host a LAN invitation")
	}
	if candidate.InterfaceID != selected.ID {
		return fail(CodeInterfaceMismatch, "listener candidate belongs to another interface")
	}
	found := false
	for _, known := range selected.Candidates {
		if addressIdentity(known) == addressIdentity(candidate) {
			found = true
			break
		}
	}
	if !found {
		return fail(CodeInterfaceMismatch, "listener candidate is not owned by the selected interface")
	}
	if address.Is6() && address.IsLinkLocalUnicast() && candidate.ZoneID != selected.Name {
		return fail(CodeInterfaceMismatch, "IPv6 link-local listener has the wrong zone")
	}
	return nil
}

func candidateFromAddress(rawAddress, interfaceID, interfaceName string) (AddressCandidate, bool) {
	addressText := rawAddress
	if prefix, err := netip.ParsePrefix(rawAddress); err == nil {
		addressText = prefix.Addr().String()
	} else if host, _, splitErr := net.SplitHostPort(rawAddress); splitErr == nil {
		addressText = host
	}
	addressText = strings.Trim(addressText, "[]")
	if zoneIndex := strings.LastIndex(addressText, "%"); zoneIndex >= 0 {
		addressText = addressText[:zoneIndex]
	}
	address, err := netip.ParseAddr(addressText)
	if err != nil ||
		address.IsUnspecified() ||
		address.IsLoopback() ||
		address.IsMulticast() ||
		(!address.IsPrivate() && !address.IsLinkLocalUnicast()) {
		return AddressCandidate{}, false
	}
	candidate := AddressCandidate{
		Host:        address.String(),
		Family:      "ipv6",
		Scope:       "private",
		InterfaceID: interfaceID,
	}
	if address.Is4() {
		candidate.Family = "ipv4"
	}
	if address.IsLinkLocalUnicast() {
		candidate.Scope = "linkLocal"
		if address.Is6() {
			candidate.ZoneID = interfaceName
		}
	}
	return candidate, true
}

func classifyInterfaceKind(name string) string {
	lower := strings.ToLower(name)
	switch {
	case strings.Contains(lower, "hotspot"), strings.Contains(lower, "ap"):
		return "hotspot"
	case strings.HasPrefix(lower, "wl"),
		strings.Contains(lower, "wi-fi"),
		strings.Contains(lower, "wifi"),
		lower == "en0":
		return "wifi"
	case strings.HasPrefix(lower, "en"),
		strings.HasPrefix(lower, "eth"),
		strings.Contains(lower, "ethernet"):
		return "ethernet"
	default:
		return "other"
	}
}

func isTunnelInterface(name string) bool {
	lower := strings.ToLower(name)
	for _, marker := range []string{
		"utun", "tun", "tap", "vpn", "wireguard", "wg", "tailscale", "zerotier",
	} {
		if strings.HasPrefix(lower, marker) || strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}

func stableInterfaceID(index int, name string) string {
	replacer := strings.NewReplacer(" ", "-", "/", "-", "\\", "-", "%", "-")
	return fmt.Sprintf("if-%d-%s", index, replacer.Replace(name))
}

func leftIndex(id string) int {
	parts := strings.SplitN(id, "-", 3)
	if len(parts) < 3 {
		return 0
	}
	value, _ := strconv.Atoi(parts[1])
	return value
}

func addressIdentity(candidate AddressCandidate) string {
	return candidate.Host + "|" + candidate.Family + "|" + candidate.Scope + "|" +
		candidate.InterfaceID + "|" + candidate.ZoneID
}

func cloneNetworkInterface(source NetworkInterface) NetworkInterface {
	source.Candidates = append([]AddressCandidate(nil), source.Candidates...)
	return source
}
