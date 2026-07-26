package cli

// 安全约束：本文件从受限 reader 读取单个严格 JSON object，并拒绝重复 key 与尾随值。

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"unicode/utf8"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

func readStrictJSONObject(reader io.Reader, maxBytes int) ([]byte, error) {
	if reader == nil || maxBytes < 2 {
		return nil, invalidJSONInput()
	}
	raw, err := io.ReadAll(io.LimitReader(reader, int64(maxBytes)+1))
	if err != nil {
		clear(raw)
		return nil, invalidJSONInput()
	}
	defer clear(raw)
	if len(raw) == 0 || len(raw) > maxBytes || !utf8.Valid(raw) ||
		bytes.IndexByte(raw, 0) >= 0 {
		return nil, invalidJSONInput()
	}
	payload := bytes.Trim(raw, " \t\r\n")
	if len(payload) == 0 || payload[0] != '{' {
		return nil, invalidJSONInput()
	}
	if err := rejectDuplicateJSONObjectKeys(payload); err != nil {
		return nil, invalidJSONInput()
	}
	return append([]byte(nil), payload...), nil
}

func rejectDuplicateJSONObjectKeys(payload []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()
	if err := consumeStrictJSONValue(decoder); err != nil {
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

func consumeStrictJSONValue(decoder *json.Decoder) error {
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
				return errors.New("duplicate object key")
			}
			seen[key] = struct{}{}
			if err := consumeStrictJSONValue(decoder); err != nil {
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
			if err := consumeStrictJSONValue(decoder); err != nil {
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

func invalidJSONInput() error {
	return apperr.New(
		apperr.CodeInvalidArgument,
		"Action input must be one strict JSON object within the message limit.",
		false,
		nil,
	)
}
