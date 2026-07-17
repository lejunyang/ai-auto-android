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

var ErrTimeout = errors.New("process deadline exceeded")

type Options struct {
	Timeout    time.Duration
	MaxOutput  int
	Stdin      []byte
	Redactions []string
}

type Result struct {
	Stdout          []byte
	Stderr          []byte
	ExitCode        int
	OutputTruncated bool
}

type Executor interface {
	Run(ctx context.Context, path string, args []string, options Options) (Result, error)
}

type Runner struct{}

type ExitError struct {
	Result Result
	Cause  error
}

func (e *ExitError) Error() string {
	return fmt.Sprintf("process exited with code %d", e.Result.ExitCode)
}

func (e *ExitError) Unwrap() error {
	return e.Cause
}

func (Runner) Run(ctx context.Context, path string, args []string, options Options) (Result, error) {
	if options.Timeout <= 0 {
		options.Timeout = 10 * time.Second
	}
	if options.MaxOutput <= 0 {
		options.MaxOutput = 1024 * 1024
	}

	runContext, cancel := context.WithTimeout(ctx, options.Timeout)
	defer cancel()

	// path and args are passed directly to execve/CreateProcess. No command shell is involved.
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

func (b *limitedBuffer) Bytes() []byte {
	return b.buffer.Bytes()
}

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
