package lan

// 功能用途：本文件在任何网络操作前严格解析 N36 invitation 与握手消息。

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
)

var (
	uuidPattern           = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	base64URL32Pattern    = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
	fingerprintPattern    = regexp.MustCompile(`^[0-9A-F]{4}(?:-[0-9A-F]{4}){3}$`)
	interfaceIDPattern    = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`)
	interfaceNamePattern  = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$`)
	capabilityNamePattern = regexp.MustCompile(`^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$`)
	hostPattern           = regexp.MustCompile(`^[0-9A-Fa-f:.]{2,64}$`)
)

// ParseInvitationJSON 拒绝未知字段、重复 key、尾随值和所有 N36 Schema 结构违约。
func ParseInvitationJSON(payload []byte) (Invitation, error) {
	if len(payload) == 0 || len(payload) > 64*1024 {
		return Invitation{}, fail(CodeInvitationSchemaInvalid, "invitation payload size is invalid")
	}
	if err := rejectDuplicateJSONKeys(payload); err != nil {
		return Invitation{}, wrap(CodeInvitationSchemaInvalid, "invitation JSON is not strict", err)
	}
	var invitation Invitation
	if err := decodeStrictJSON(payload, &invitation); err != nil {
		return Invitation{}, wrap(CodeInvitationSchemaInvalid, "invitation JSON does not match the schema", err)
	}
	if err := validateInvitationSchema(invitation); err != nil {
		return Invitation{}, err
	}
	return invitation, nil
}

func decodeStrictJSON(payload []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("trailing JSON value")
		}
		return err
	}
	return nil
}

func rejectDuplicateJSONKeys(payload []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()
	if err := consumeJSONValue(decoder); err != nil {
		return err
	}
	if token, err := decoder.Token(); !errors.Is(err, io.EOF) {
		if err != nil {
			return err
		}
		return fmt.Errorf("unexpected trailing token %v", token)
	}
	return nil
}

func consumeJSONValue(decoder *json.Decoder) error {
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delimiter, structured := token.(json.Delim)
	if !structured {
		return nil
	}
	switch delimiter {
	case '{':
		seen := make(map[string]struct{})
		for decoder.More() {
			keyToken, err := decoder.Token()
			if err != nil {
				return err
			}
			key, ok := keyToken.(string)
			if !ok {
				return errors.New("object key is not a string")
			}
			if _, duplicate := seen[key]; duplicate {
				return fmt.Errorf("duplicate object key %q", key)
			}
			seen[key] = struct{}{}
			if err := consumeJSONValue(decoder); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil {
			return err
		}
		if end != json.Delim('}') {
			return errors.New("object is not terminated")
		}
	case '[':
		for decoder.More() {
			if err := consumeJSONValue(decoder); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil {
			return err
		}
		if end != json.Delim(']') {
			return errors.New("array is not terminated")
		}
	default:
		return errors.New("unexpected closing delimiter")
	}
	return nil
}

func validateInvitationSchema(invitation Invitation) error {
	if invitation.Kind != invitationKind ||
		invitation.Version != protocolVersion ||
		!uuidPattern.MatchString(invitation.InvitationID) ||
		invitation.IssuedAt == "" ||
		invitation.ExpiresAt == "" ||
		invitation.TTLSeconds < minInvitationTTLSeconds ||
		invitation.TTLSeconds > maxInvitationTTLSeconds ||
		decodeBase64URL32(invitation.Nonce) == nil ||
		!fingerprintPattern.MatchString(invitation.Fingerprint) {
		return fail(CodeInvitationSchemaInvalid, "invitation top-level fields are invalid")
	}
	if err := validateListenerSchema(invitation.Listener); err != nil {
		return err
	}
	if invitation.EphemeralKey.Algorithm != "X25519" ||
		invitation.EphemeralKey.Encoding != "base64url" ||
		decodeBase64URL32(invitation.EphemeralKey.PublicKey) == nil {
		return fail(CodeInvitationSchemaInvalid, "invitation ephemeral key is invalid")
	}
	if len(invitation.Capabilities) < 2 || len(invitation.Capabilities) > 64 {
		return fail(CodeInvitationSchemaInvalid, "invitation capabilities are invalid")
	}
	seenCapabilities := make(map[string]struct{}, len(invitation.Capabilities))
	for _, capability := range invitation.Capabilities {
		if len(capability) > 128 || !capabilityNamePattern.MatchString(capability) {
			return fail(CodeInvitationSchemaInvalid, "invitation capability name is invalid")
		}
		if _, duplicate := seenCapabilities[capability]; duplicate {
			return fail(CodeInvitationSchemaInvalid, "invitation capabilities contain a duplicate")
		}
		seenCapabilities[capability] = struct{}{}
	}
	if invitation.Security.Suite != securitySuite ||
		invitation.Security.Transcript != transcriptDomain ||
		invitation.Security.Confirmation != confirmationAlgorithm {
		return fail(CodeInvitationSchemaInvalid, "invitation security suite is invalid")
	}
	return nil
}

func validateListenerSchema(listener ListenerDescriptor) error {
	if listener.Port < 1024 || listener.Port > 65535 ||
		!interfaceIDPattern.MatchString(listener.SelectedInterface.ID) ||
		!interfaceNamePattern.MatchString(listener.SelectedInterface.Name) {
		return fail(CodeInvitationSchemaInvalid, "listener identity or port is invalid")
	}
	switch listener.SelectedInterface.Kind {
	case "wifi", "ethernet", "hotspot", "other":
	default:
		return fail(CodeInvitationSchemaInvalid, "listener interface kind is invalid")
	}
	if len(listener.AddressCandidates) < 1 || len(listener.AddressCandidates) > 16 {
		return fail(CodeInvitationSchemaInvalid, "listener candidates are invalid")
	}
	seenCandidates := make(map[string]struct{}, len(listener.AddressCandidates))
	for _, candidate := range listener.AddressCandidates {
		if !hostPattern.MatchString(candidate.Host) ||
			(candidate.Family != "ipv4" && candidate.Family != "ipv6") ||
			(candidate.Scope != "private" && candidate.Scope != "linkLocal") ||
			!interfaceIDPattern.MatchString(candidate.InterfaceID) ||
			(candidate.ZoneID != "" && !interfaceNamePattern.MatchString(candidate.ZoneID)) {
			return fail(CodeInvitationSchemaInvalid, "listener candidate is invalid")
		}
		encoded, err := json.Marshal(candidate)
		if err != nil {
			return wrap(CodeInvitationSchemaInvalid, "listener candidate could not be encoded", err)
		}
		key := string(encoded)
		if _, duplicate := seenCandidates[key]; duplicate {
			return fail(CodeInvitationSchemaInvalid, "listener candidates contain a duplicate")
		}
		seenCandidates[key] = struct{}{}
	}
	return nil
}

func decodeBase64URL32(value string) []byte {
	if !base64URL32Pattern.MatchString(value) {
		return nil
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) != 32 ||
		base64.RawURLEncoding.EncodeToString(decoded) != value {
		return nil
	}
	return decoded
}

func validStringList(values []string, minimum, maximum int, pattern *regexp.Regexp) bool {
	if len(values) < minimum || len(values) > maximum {
		return false
	}
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		if len(value) > 128 || !pattern.MatchString(value) {
			return false
		}
		if _, duplicate := seen[value]; duplicate {
			return false
		}
		seen[value] = struct{}{}
	}
	return true
}

func hasOnlyVersionOne(versions []string) bool {
	if len(versions) < 1 || len(versions) > 8 {
		return false
	}
	for _, version := range versions {
		if version != protocolVersion {
			return false
		}
	}
	return true
}

func validHandshakeType(value string) bool {
	return strings.HasPrefix(value, "lan.")
}
