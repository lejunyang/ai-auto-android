package adb

// 功能用途：本文件解析 ADB mDNS 服务，并只接受局域网内可验证的无线调试端点。

import (
	"bufio"
	"fmt"
	"net"
	"regexp"
	"sort"
	"strings"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

const (
	// MDNSPairingService 标识 Android 无线调试的一次性配对服务。
	MDNSPairingService = "_adb-tls-pairing._tcp"
	// MDNSConnectService 标识已配对设备的无线连接服务。
	MDNSConnectService = "_adb-tls-connect._tcp"
)

var mdnsIdentityPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$`)

// MDNSService 表示 ADB 发现的一条配对或连接服务，不包含任何配对秘密。
type MDNSService struct {
	Identity string `json:"identity"`
	Instance string `json:"instance"`
	Service  string `json:"service"`
	Endpoint string `json:"endpoint"`
}

// ParseMDNSServices 解析 adb mdns services 输出并拒绝公网、未知或身份歧义的记录。
func ParseMDNSServices(output string) ([]MDNSService, error) {
	services := make([]MDNSService, 0)
	seen := make(map[string]string)
	scanner := bufio.NewScanner(strings.NewReader(output))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(strings.ToLower(line), "list of") {
			continue
		}
		service, ok, err := parseMDNSLine(line)
		if err != nil {
			return nil, err
		}
		if !ok {
			continue
		}
		key := service.Service + "\x00" + service.Identity
		if endpoint, exists := seen[key]; exists {
			if endpoint != service.Endpoint {
				return nil, apperr.New(
					apperr.CodeMultipleDevices,
					"ADB mDNS advertised one trusted identity on multiple endpoints.",
					false,
					map[string]any{"identity": service.Identity},
				)
			}
			continue
		}
		seen[key] = service.Endpoint
		services = append(services, service)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read adb mdns output: %w", err)
	}
	sort.Slice(services, func(left, right int) bool {
		if services[left].Identity == services[right].Identity {
			return services[left].Service < services[right].Service
		}
		return services[left].Identity < services[right].Identity
	})
	return services, nil
}

func parseMDNSLine(line string) (MDNSService, bool, error) {
	fields := strings.Fields(line)
	if len(fields) < 2 {
		return MDNSService{}, false, nil
	}
	endpoint := fields[len(fields)-1]
	var instance, serviceType string
	for index, field := range fields[:len(fields)-1] {
		normalized := normalizeMDNSService(field)
		switch normalized {
		case MDNSPairingService, MDNSConnectService:
			serviceType = normalized
			if index > 0 {
				instance = strings.TrimSuffix(fields[index-1], ".")
			}
		}
	}
	if serviceType == "" {
		combined := strings.TrimSuffix(fields[0], ".")
		for _, candidate := range []string{MDNSPairingService, MDNSConnectService} {
			suffix := "." + candidate
			if strings.HasSuffix(combined, suffix) {
				instance = strings.TrimSuffix(combined, suffix)
				serviceType = candidate
				break
			}
		}
	}
	if serviceType == "" {
		return MDNSService{}, false, nil
	}
	if !mdnsIdentityPattern.MatchString(instance) {
		return MDNSService{}, false, apperr.New(
			apperr.CodeInvalidArgument,
			"ADB mDNS service identity is invalid.",
			false,
			map[string]any{"field": "identity"},
		)
	}
	if err := validateLocalMDNSEndpoint(endpoint); err != nil {
		return MDNSService{}, false, err
	}
	return MDNSService{
		Identity: instance,
		Instance: instance,
		Service:  serviceType,
		Endpoint: endpoint,
	}, true, nil
}

func normalizeMDNSService(value string) string {
	return strings.TrimSuffix(strings.TrimSpace(value), ".")
}

func validateLocalMDNSEndpoint(endpoint string) error {
	if err := ValidateEndpoint(endpoint); err != nil {
		return err
	}
	host, _, _ := net.SplitHostPort(endpoint)
	trimmedHost := strings.Trim(strings.TrimSpace(host), "[]")
	// 拆分zone和裸IP/hostname
	rawHost := trimmedHost
	if zoneIndex := strings.LastIndex(trimmedHost, "%"); zoneIndex >= 0 {
		rawHost = trimmedHost[:zoneIndex]
	}
	ip := net.ParseIP(rawHost)
	if ip != nil {
		// 仅允许私有、环回、链路本地地址；公网地址即使带zone也拒绝
		if ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			return nil
		}
	} else if strings.HasSuffix(strings.ToLower(rawHost), ".local") && !strings.ContainsAny(rawHost, ";$&|`") {
		// .local域名额外校验不含命令注入字符
		return nil
	}
	return apperr.New(
		apperr.CodePermissionDenied,
		"ADB mDNS endpoint is not a private or link-local address.",
		false,
		map[string]any{"endpoint": endpoint},
	)
}
