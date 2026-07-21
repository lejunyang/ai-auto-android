package process

// 测试用途：本文件验证 argv 不经 Shell、超时与输出预算生效，并确保标准输入秘密被脱敏。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"
)

func TestRunnerPassesArgumentsWithoutShellInterpretation(t *testing.T) {
	t.Setenv("GO_WANT_PROCESS_HELPER", "1")
	payload := `serial; touch /tmp/should-not-exist && echo injected`
	result, err := (Runner{}).Run(
		context.Background(),
		os.Args[0],
		[]string{"-test.run=TestProcessHelper", "--", "args", payload},
		Options{Timeout: helperTimeout},
	)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	var arguments []string
	if err := json.Unmarshal(result.Stdout, &arguments); err != nil {
		t.Fatalf("decode helper output: %v", err)
	}
	if len(arguments) != 1 || arguments[0] != payload {
		t.Fatalf("arguments = %#v, want exact payload", arguments)
	}
}

func TestRunnerTimesOut(t *testing.T) {
	t.Setenv("GO_WANT_PROCESS_HELPER", "1")
	_, err := (Runner{}).Run(
		context.Background(),
		os.Args[0],
		[]string{"-test.run=TestProcessHelper", "--", "sleep"},
		Options{Timeout: 20 * time.Millisecond},
	)
	if err != ErrTimeout {
		t.Fatalf("Run() error = %v, want ErrTimeout", err)
	}
}

func TestRunnerLimitsOutput(t *testing.T) {
	t.Setenv("GO_WANT_PROCESS_HELPER", "1")
	result, err := (Runner{}).Run(
		context.Background(),
		os.Args[0],
		[]string{"-test.run=TestProcessHelper", "--", "output"},
		Options{Timeout: helperTimeout, MaxOutput: 16},
	)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(result.Stdout) != 16 || !result.OutputTruncated {
		t.Fatalf("stdout length = %d, truncated = %v", len(result.Stdout), result.OutputTruncated)
	}
}

func TestRunnerRedactsStdinSecretFromOutput(t *testing.T) {
	t.Setenv("GO_WANT_PROCESS_HELPER", "1")
	secret := "123456"
	result, err := (Runner{}).Run(
		context.Background(),
		os.Args[0],
		[]string{"-test.run=TestProcessHelper", "--", "echo-stdin"},
		Options{
			Timeout:    helperTimeout,
			Stdin:      []byte(secret),
			Redactions: []string{secret},
		},
	)
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if strings.Contains(string(result.Stdout), secret) || string(result.Stdout) != "[REDACTED]" {
		t.Fatalf("stdout was not redacted: %q", result.Stdout)
	}
}

func TestProcessHelper(t *testing.T) {
	if os.Getenv("GO_WANT_PROCESS_HELPER") != "1" {
		return
	}
	separator := -1
	for index, argument := range os.Args {
		if argument == "--" {
			separator = index
			break
		}
	}
	if separator < 0 || separator+1 >= len(os.Args) {
		os.Exit(2)
	}
	arguments := os.Args[separator+1:]
	switch arguments[0] {
	case "args":
		_ = json.NewEncoder(os.Stdout).Encode(arguments[1:])
	case "sleep":
		time.Sleep(time.Second)
	case "output":
		_, _ = fmt.Fprint(os.Stdout, strings.Repeat("x", 1024))
	case "echo-stdin":
		_, _ = io.Copy(os.Stdout, os.Stdin)
	default:
		os.Exit(2)
	}
	os.Exit(0)
}

const helperTimeout = 10 * time.Second
