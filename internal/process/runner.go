// Package process 提供不经过命令行 Shell 的受限子进程执行能力。
package process

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"time"
)

// ErrTimeout 表示子进程超过调用方或默认截止时间。
var ErrTimeout = errors.New("process deadline exceeded")

// Options 定义超时、输出预算、标准输入和敏感值脱敏规则。
type Options struct {
	Timeout    time.Duration
	MaxOutput  int
	Stdin      []byte
	Redactions []string
}

// Result 保存受限后的标准输出、标准错误和进程退出状态。
type Result struct {
	Stdout          []byte
	Stderr          []byte
	ExitCode        int
	OutputTruncated bool
}

// Executor 抽象安全子进程执行，便于生产代码和 fake ADB 测试共享契约。
type Executor interface {
	Run(ctx context.Context, path string, args []string, options Options) (Result, error)
}

// Runner 使用操作系统进程 API 直接传递 argv，不解析 Shell 语法。
type Runner struct{}

// ExitError 保存非零退出码及已脱敏、已限长的执行结果。
type ExitError struct {
	Result Result
	Cause  error
}

// Error 返回包含稳定非零退出码的错误消息。
func (e *ExitError) Error() string {
	return fmt.Sprintf("process exited with code %d", e.Result.ExitCode)
}

// Unwrap 允许调用方检查操作系统返回的原始进程错误。
func (e *ExitError) Unwrap() error {
	return e.Cause
}

// Run 在超时和输出预算内直接执行 path 与 argv，并在返回前统一脱敏。
func (Runner) Run(ctx context.Context, path string, args []string, options Options) (Result, error) {
	if options.Timeout <= 0 {
		options.Timeout = 10 * time.Second
	}
	if options.MaxOutput <= 0 {
		options.MaxOutput = 1024 * 1024
	}

	runContext, cancel := context.WithTimeout(ctx, options.Timeout)
	defer cancel()

	// path 与 args 直接交给 execve/CreateProcess，避免 Shell 注入与二次展开。
	command := exec.CommandContext(runContext, path, args...)
	if len(options.Stdin) > 0 {
		command.Stdin = bytes.NewReader(options.Stdin)
	}

	stdout := newLimitedBuffer(options.MaxOutput)
	stderr := newLimitedBuffer(options.MaxOutput)
	command.Stdout = stdout
	command.Stderr = stderr

	err := command.Run()
	result := Result{
		Stdout:          redact(stdout.Bytes(), options.Redactions),
		Stderr:          redact(stderr.Bytes(), options.Redactions),
		ExitCode:        0,
		OutputTruncated: stdout.Truncated() || stderr.Truncated(),
	}

	if runContext.Err() == context.DeadlineExceeded {
		result.ExitCode = -1
		return result, ErrTimeout
	}
	if err == nil {
		return result, nil
	}

	var exitError *exec.ExitError
	if errors.As(err, &exitError) {
		result.ExitCode = exitError.ExitCode()
		return result, &ExitError{Result: result, Cause: err}
	}
	result.ExitCode = -1
	return result, err
}

type limitedBuffer struct {
	buffer    bytes.Buffer
	remaining int
	truncated bool
}

func newLimitedBuffer(limit int) *limitedBuffer {
	return &limitedBuffer{remaining: limit}
}

// Write 在达到预算后仍报告已消费全部输入，避免子进程因管道回压阻塞。
func (b *limitedBuffer) Write(data []byte) (int, error) {
	originalLength := len(data)
	if b.remaining <= 0 {
		b.truncated = b.truncated || originalLength > 0
		return originalLength, nil
	}
	writeLength := min(originalLength, b.remaining)
	_, _ = b.buffer.Write(data[:writeLength])
	b.remaining -= writeLength
	if writeLength < originalLength {
		b.truncated = true
	}
	return originalLength, nil
}

// Bytes 返回预算范围内实际保留的输出。
func (b *limitedBuffer) Bytes() []byte {
	return b.buffer.Bytes()
}

// Truncated 报告是否有输出因预算限制被丢弃。
func (b *limitedBuffer) Truncated() bool {
	return b.truncated
}

func redact(data []byte, values []string) []byte {
	if len(data) == 0 {
		return nil
	}
	text := string(data)
	for _, value := range values {
		if value == "" {
			continue
		}
		text = strings.ReplaceAll(text, value, "[REDACTED]")
	}
	return []byte(text)
}

var _ io.Writer = (*limitedBuffer)(nil)
