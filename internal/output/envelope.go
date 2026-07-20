// Package output 生成并写出 CLI 的版本化响应信封和请求标识。
package output

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

// Success 构造包含耗时元数据的成功响应信封。
func Success(requestID string, startedAt time.Time, data any) protocol.Envelope {
	return protocol.Envelope{
		SchemaVersion: protocol.SchemaVersion,
		RequestID:     requestID,
		OK:            true,
		Data:          data,
		Error:         nil,
		Meta:          protocol.Meta{DurationMS: elapsedMilliseconds(startedAt)},
	}
}

// Failure 构造已清理内部错误细节的失败响应信封。
func Failure(requestID string, startedAt time.Time, err error) protocol.Envelope {
	return protocol.Envelope{
		SchemaVersion: protocol.SchemaVersion,
		RequestID:     requestID,
		OK:            false,
		Data:          nil,
		Error:         apperr.Protocol(err),
		Meta:          protocol.Meta{DurationMS: elapsedMilliseconds(startedAt)},
	}
}

// Write 以紧凑或便于阅读的 JSON 格式写出一个完整响应。
func Write(writer io.Writer, envelope protocol.Envelope, compact bool) error {
	encoder := json.NewEncoder(writer)
	encoder.SetEscapeHTML(false)
	if !compact {
		encoder.SetIndent("", "  ")
	}
	return encoder.Encode(envelope)
}

// RequestID 使用系统随机源生成符合 UUID v4 位布局的请求标识。
func RequestID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", fmt.Errorf("generate request ID: %w", err)
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	return fmt.Sprintf(
		"%s-%s-%s-%s-%s",
		hex.EncodeToString(value[0:4]),
		hex.EncodeToString(value[4:6]),
		hex.EncodeToString(value[6:8]),
		hex.EncodeToString(value[8:10]),
		hex.EncodeToString(value[10:16]),
	), nil
}

func elapsedMilliseconds(startedAt time.Time) int64 {
	duration := time.Since(startedAt).Milliseconds()
	if duration < 0 {
		return 0
	}
	return duration
}
