package protocol

const SchemaVersion = "1.0"

// Error uses stable machine-readable codes shared with protocol/schema/v1/error.schema.json.
type Error struct {
	Code      string         `json:"code"`
	Message   string         `json:"message"`
	Retryable bool           `json:"retryable"`
	Details   map[string]any `json:"details,omitempty"`
}

type Meta struct {
	DurationMS int64 `json:"durationMs"`
}

type Envelope struct {
	SchemaVersion string `json:"schemaVersion"`
	RequestID     string `json:"requestId"`
	OK            bool   `json:"ok"`
	Data          any    `json:"data"`
	Error         *Error `json:"error"`
	Meta          Meta   `json:"meta"`
}

type Capability struct {
	Name       string         `json:"name"`
	Version    string         `json:"version"`
	Available  bool           `json:"available"`
	Permission string         `json:"permission,omitempty"`
	Reason     string         `json:"reason,omitempty"`
	Limits     map[string]any `json:"limits,omitempty"`
}

type DeviceConnection struct {
	Endpoint    string `json:"endpoint,omitempty"`
	MDNSService string `json:"mdnsService,omitempty"`
	TransportID int    `json:"transportId,omitempty"`
}

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
