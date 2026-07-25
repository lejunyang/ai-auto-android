// Package visual 测试 Go 模型与独立 visual schema canonical example 的一致性。
// 测试用途：验证严格 JSON round-trip、Schema example 和敏感节点字段约束。
package visual

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/google/jsonschema-go/jsonschema"
)

func TestGoCanonicalBundleEqualsAndValidatesStandaloneSchemaExample(t *testing.T) {
	payload := readVisualSchema(t)
	var document struct {
		Examples []json.RawMessage `json:"examples"`
	}
	if err := json.Unmarshal(payload, &document); err != nil {
		t.Fatalf("decode schema examples: %v", err)
	}
	if len(document.Examples) != 1 {
		t.Fatalf("schema examples = %d, want 1", len(document.Examples))
	}
	var bundle ProposalBundle
	if err := DecodeBundle(document.Examples[0], &bundle); err != nil {
		t.Fatalf("DecodeBundle(example) error = %v", err)
	}
	encoded, err := EncodeBundle(bundle)
	if err != nil {
		t.Fatalf("EncodeBundle() error = %v", err)
	}
	var got, want any
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatalf("decode encoded bundle: %v", err)
	}
	if err := json.Unmarshal(document.Examples[0], &want); err != nil {
		t.Fatalf("decode expected bundle: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("encoded bundle differs from schema example\ngot:  %s\nwant: %s", encoded, document.Examples[0])
	}
	for _, forbidden := range []string{
		"screenshotBase64",
		"pngBytes",
		"filePath",
		"secret",
	} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("encoded bundle contains forbidden field or value %q", forbidden)
		}
	}

	var schema jsonschema.Schema
	if err := json.Unmarshal(payload, &schema); err != nil {
		t.Fatalf("decode schema: %v", err)
	}
	resolved, err := schema.Resolve(nil)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if err := resolved.Validate(got); err != nil {
		t.Fatalf("canonical example failed schema validation: %v", err)
	}
}

func TestSchemaRejectsUnknownFieldsAndSensitiveLabels(t *testing.T) {
	payload := readVisualSchema(t)
	var document struct {
		Examples []map[string]any `json:"examples"`
	}
	if err := json.Unmarshal(payload, &document); err != nil {
		t.Fatalf("decode schema examples: %v", err)
	}
	var schema jsonschema.Schema
	if err := json.Unmarshal(payload, &schema); err != nil {
		t.Fatalf("decode schema: %v", err)
	}
	resolved, err := schema.Resolve(nil)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}

	unknown := cloneJSON(t, document.Examples[0])
	unknown["execute"] = true
	if err := resolved.Validate(unknown); err == nil {
		t.Fatal("schema accepted unknown execute field")
	}
	sensitive := cloneJSON(t, document.Examples[0])
	observation := sensitive["observation"].(map[string]any)
	hierarchy := observation["hierarchy"].([]any)
	hierarchy[1].(map[string]any)["label"] = "must-not-leak"
	if err := resolved.Validate(sensitive); err == nil {
		t.Fatal("schema accepted a label on a sensitive hierarchy node")
	}
}

func readVisualSchema(t *testing.T) []byte {
	t.Helper()
	path := filepath.Join(
		"..",
		"..",
		"protocol",
		"schema",
		"v1",
		"visual-observation-proposal.schema.json",
	)
	payload, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read visual schema: %v", err)
	}
	return payload
}

func cloneJSON(t *testing.T, value any) map[string]any {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("clone marshal: %v", err)
	}
	var clone map[string]any
	if err := json.Unmarshal(payload, &clone); err != nil {
		t.Fatalf("clone unmarshal: %v", err)
	}
	return clone
}
