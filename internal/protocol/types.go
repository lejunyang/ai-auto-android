// Package protocol 定义 CLI、Bridge、MCP 与测试夹具共享的稳定数据模型。
package protocol

// SchemaVersion 是当前 CLI 响应信封使用的协议模式版本。
const SchemaVersion = "1.0"

// Error 使用与 protocol/schema/v1/error.schema.json 一致的稳定机器错误码。
type Error struct {
	Code      string         `json:"code"`
	Message   string         `json:"message"`
	Retryable bool           `json:"retryable"`
	Details   map[string]any `json:"details,omitempty"`
}

// Meta 保存不影响业务数据的响应元信息。
type Meta struct {
	DurationMS int64 `json:"durationMs"`
}

// Envelope 是所有非 MCP CLI 命令统一返回的版本化响应信封。
type Envelope struct {
	SchemaVersion string `json:"schemaVersion"`
	RequestID     string `json:"requestId"`
	OK            bool   `json:"ok"`
	Data          any    `json:"data"`
	Error         *Error `json:"error"`
	Meta          Meta   `json:"meta"`
}

// Capability 描述设备或 Bridge 能力的版本、可用性、权限和限制。
type Capability struct {
	Name       string         `json:"name"`
	Version    string         `json:"version"`
	Available  bool           `json:"available"`
	Permission string         `json:"permission,omitempty"`
	Reason     string         `json:"reason,omitempty"`
	Limits     map[string]any `json:"limits,omitempty"`
}

// DeviceConnection 描述设备传输端点及 ADB 连接元数据。
type DeviceConnection struct {
	Endpoint    string `json:"endpoint,omitempty"`
	MDNSService string `json:"mdnsService,omitempty"`
	TransportID int    `json:"transportId,omitempty"`
}

// Device 是跨 CLI、MCP 和 Bridge 使用的规范化 Android 设备模型。
type Device struct {
	Serial         string           `json:"serial"`
	State          string           `json:"state"`
	Transport      string           `json:"transport"`
	Manufacturer   string           `json:"manufacturer,omitempty"`
	Model          string           `json:"model,omitempty"`
	Product        string           `json:"product,omitempty"`
	AndroidVersion string           `json:"androidVersion,omitempty"`
	APILevel       int              `json:"apiLevel,omitempty"`
	Connection     DeviceConnection `json:"connection,omitempty"`
	Capabilities   []Capability     `json:"capabilities"`
}
