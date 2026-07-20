package adb

// 本文件验证临时端口分配、精确转发清理及调用 ADB 前的参数校验。

import (
	"context"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

func TestForwardUsesADBAllocatedEphemeralPort(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1\n")},
			{Stdout: []byte("41237\n")},
		},
		errors: []error{nil, nil},
	}

	forward, err := NewClient("/fake/adb", fake).Forward(context.Background(), "SERIAL", 38383)
	if err != nil {
		t.Fatalf("Forward() error = %v", err)
	}
	if forward.LocalPort != 41237 || forward.RemotePort != 38383 {
		t.Fatalf("forward = %#v", forward)
	}
	assertArgs(t, fake.calls[1], "-s", "SERIAL", "forward", "tcp:0", "tcp:38383")
}

func TestForwardRejectsInvalidAllocatedPort(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1\n")},
			{Stdout: []byte("not-a-port\n")},
		},
		errors: []error{nil, nil},
	}

	_, err := NewClient("/fake/adb", fake).Forward(context.Background(), "SERIAL", 38383)
	assertAppErrorCode(t, err, apperr.CodeProtocol)
}

func TestRemoveForwardUsesExactDeviceAndPort(t *testing.T) {
	fake := &fakeADB{results: []process.Result{{}}, errors: []error{nil}}

	err := NewClient("/fake/adb", fake).RemoveForward(context.Background(), "SERIAL", 41237)
	if err != nil {
		t.Fatalf("RemoveForward() error = %v", err)
	}
	assertArgs(t, fake.calls[0], "-s", "SERIAL", "forward", "--remove", "tcp:41237")
}

func TestForwardValidatesArgumentsBeforeADB(t *testing.T) {
	fake := &fakeADB{}
	client := NewClient("/fake/adb", fake)

	_, serialErr := client.Forward(context.Background(), "SERIAL;reboot", 38383)
	assertAppErrorCode(t, serialErr, apperr.CodeInvalidArgument)
	_, portErr := client.Forward(context.Background(), "SERIAL", 0)
	assertAppErrorCode(t, portErr, apperr.CodeInvalidArgument)

	if len(fake.calls) != 0 {
		t.Fatalf("ADB calls = %#v, want none", fake.calls)
	}
}
