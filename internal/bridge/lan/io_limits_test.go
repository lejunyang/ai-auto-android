package lan

// 测试用途：验证不可信 LAN 输入在换行前触发预算，并保证取消及时回收阻塞 socket 与密钥。

import (
	"bufio"
	"bytes"
	"context"
	"net"
	"testing"
	"time"
)

func TestHandshakeRejectsOversizedUnterminatedLine(t *testing.T) {
	server, peer := net.Pipe()
	defer server.Close()
	defer peer.Close()
	done := make(chan error, 1)
	go func() {
		var hello ClientHello
		done <- readHandshakeMessage(bufio.NewReaderSize(server, 4096), &hello)
	}()
	// 不发送换行，也不关闭连接：旧实现会一直等待并继续分配内存。
	go func() { _, _ = peer.Write(bytes.Repeat([]byte{'x'}, maxHandshakeBytes+4096)) }()
	select {
	case err := <-done:
		if ErrorCode(err) != CodeFrameTooLarge {
			t.Fatalf("error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("oversized handshake waited for a newline")
	}
}

func TestSessionRejectsOversizedUnterminatedFrame(t *testing.T) {
	desktop, peer := pairedRPCSessions(t)
	defer desktop.Close()
	defer peer.Close()
	done := make(chan error, 1)
	go func() { _, _, err := desktop.ReadFrame(context.Background()); done <- err }()
	go func() { _, _ = peer.connection.Write(bytes.Repeat([]byte{'x'}, MaxFramePlaintextBytes+68*1024)) }()
	select {
	case err := <-done:
		if ErrorCode(err) != CodeFrameTooLarge {
			t.Fatalf("error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("oversized encrypted frame waited for a newline")
	}
	if !desktop.Closed() || !desktop.framer.Destroyed() {
		t.Fatal("oversized frame kept session secrets alive")
	}
}

func TestSessionCancellationInterruptsIO(t *testing.T) {
	for _, operation := range []string{"read", "write"} {
		for _, cancelBefore := range []bool{false, true} {
			t.Run(operation+map[bool]string{false: "/during", true: "/before"}[cancelBefore], func(t *testing.T) {
				desktop, peer := pairedRPCSessions(t)
				defer desktop.Close()
				defer peer.Close()
				ctx, cancel := context.WithCancel(context.Background())
				defer cancel()
				if cancelBefore {
					cancel()
				}
				done := make(chan error, 1)
				go func() {
					if operation == "read" {
						_, _, err := desktop.ReadFrame(ctx)
						done <- err
					} else {
						done <- desktop.WriteFrame(ctx, "bridge.request", []byte("{}"))
					}
				}()
				if !cancelBefore {
					time.Sleep(10 * time.Millisecond)
					cancel()
				}
				select {
				case err := <-done:
					if ErrorCode(err) != CodeCancelled {
						t.Fatalf("error = %v", err)
					}
				case <-time.After(time.Second):
					t.Fatal("context cancellation did not interrupt I/O")
				}
				if !desktop.Closed() || !desktop.framer.Destroyed() {
					t.Fatal("cancelled session retained secrets")
				}
			})
		}
	}
}
