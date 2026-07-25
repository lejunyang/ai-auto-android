// Package lan 实现桌面 LAN invitation、加密握手和短期 RPC 会话的安全边界。
package lan

import "time"

const (
	invitationKind          = "ai-auto-lan-invitation"
	protocolVersion         = "1.0"
	securitySuite           = "X25519-HKDF-SHA256-AES-256-GCM"
	transcriptDomain        = "AIAUTO-LAN-BRIDGE-TRANSCRIPT-V1"
	confirmationAlgorithm   = "HMAC-SHA256"
	fingerprintDomain       = "AIAUTO-LAN-INVITATION-FINGERPRINT-V1"
	hkdfSaltDomain          = "AIAUTO-LAN-BRIDGE-HKDF-SALT-V1"
	hkdfInfoDomain          = "AIAUTO-LAN-BRIDGE-KEYS-V1"
	confirmationDomain      = "AIAUTO-LAN-BRIDGE-CONFIRMATION-V1"
	requiredRPC             = "lan.bridge.rpc.v1"
	requiredConfirmation    = "lan.bridge.mutual-confirmation.v1"
	minInvitationTTLSeconds = 15
	maxInvitationTTLSeconds = 120
)

// Invitation 是 N36 固定的公开 QR payload，不承载任何最终 token 或私钥。
type Invitation struct {
	Kind         string             `json:"kind"`
	Version      string             `json:"version"`
	InvitationID string             `json:"invitationId"`
	IssuedAt     string             `json:"issuedAt"`
	ExpiresAt    string             `json:"expiresAt"`
	TTLSeconds   int                `json:"ttlSeconds"`
	Nonce        string             `json:"nonce"`
	Listener     ListenerDescriptor `json:"listener"`
	EphemeralKey EphemeralKey       `json:"ephemeralKey"`
	Fingerprint  string             `json:"fingerprint"`
	Capabilities []string           `json:"capabilities"`
	Security     SecurityDescriptor `json:"security"`
}

// ListenerDescriptor 将临时端口和候选地址绑定到一个明确选择的网卡。
type ListenerDescriptor struct {
	Port              int                `json:"port"`
	SelectedInterface SelectedInterface  `json:"selectedInterface"`
	AddressCandidates []AddressCandidate `json:"addressCandidates"`
}

// SelectedInterface 是 invitation 中可由操作员核对的稳定接口身份。
type SelectedInterface struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"`
}

// AddressCandidate 是不经 DNS 解析的私网或链路本地 IP literal。
type AddressCandidate struct {
	Host        string `json:"host"`
	Family      string `json:"family"`
	Scope       string `json:"scope"`
	InterfaceID string `json:"interfaceId"`
	ZoneID      string `json:"zoneId,omitempty"`
}

// EphemeralKey 只公开本次 invitation 的 X25519 公钥。
type EphemeralKey struct {
	Algorithm string `json:"algorithm"`
	Encoding  string `json:"encoding"`
	PublicKey string `json:"publicKey"`
}

// SecurityDescriptor 固定 N36 密码套件，不提供降级协商入口。
type SecurityDescriptor struct {
	Suite        string `json:"suite"`
	Transcript   string `json:"transcript"`
	Confirmation string `json:"confirmation"`
}

// Endpoint 是客户端实际选择并连接的 invitation 候选。
type Endpoint struct {
	Host        string `json:"host"`
	Family      string `json:"family"`
	Port        int    `json:"port"`
	InterfaceID string `json:"interfaceId"`
}

// ClientHello 将 App 临时公钥、endpoint、版本和能力绑定原 invitation。
type ClientHello struct {
	Type                     string   `json:"type"`
	Version                  string   `json:"version"`
	InvitationID             string   `json:"invitationId"`
	Nonce                    string   `json:"nonce"`
	ClientEphemeralPublicKey string   `json:"clientEphemeralPublicKey"`
	SelectedEndpoint         Endpoint `json:"selectedEndpoint"`
	SupportedBridgeVersions  []string `json:"supportedBridgeVersions"`
	RequestedCapabilities    []string `json:"requestedCapabilities"`
}

// Selection 是桌面策略和客户端请求的有序交集，也是 transcript 的组成部分。
type Selection struct {
	SelectedBridgeVersion string   `json:"selectedBridgeVersion"`
	SelectedCapabilities  []string `json:"selectedCapabilities"`
}

// ValidationContext 为无网络副作用的预检提供时钟、重放集合和生产者接口。
type ValidationContext struct {
	Now                   time.Time
	ConsumedInvitationIDs map[string]struct{}
	ConsumedNonces        map[string]struct{}
	ExpectedInterface     *SelectedInterface
}

// ConfirmationRole 对双方确认标签进行角色分域。
type ConfirmationRole string

const (
	// ConfirmationDesktop 表示由桌面发送的确认标签。
	ConfirmationDesktop ConfirmationRole = "desktop"
	// ConfirmationClient 表示由 Android 客户端发送的确认标签。
	ConfirmationClient ConfirmationRole = "client"
)
