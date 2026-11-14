package vdisplay

// 测试用途：假服务端验证协议预算、精确帧读取、错误映射、握手与临时 forward 的失败清理。

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"image"
	"image/png"
	"net"
	"strings"
	"testing"
	"time"
)

const helloJSON = `{"id":1,"ok":true,"result":{"protocolVersion":2,"apiLevel":35,"supported":true,"capabilities":{"binaryPng":true,"credentialLockGuard":true,"virtualDisplay":true,"groupAwakeQuery":true,"focusIsolation":true},"limits":{"maxMessageBytes":16384,"maxFrameBytes":16777216,"maxDisplays":2,"minDimension":128,"maxDimension":2048,"minDpi":72,"maxDpi":640}}}` + "\n"

// 假设备仍经过真实握手/序列化，核对策略透传、API33降级与响应能力字段，避免只测结构体赋值。
func TestFocusPolicyWireAndAPI33Honesty(t *testing.T) {
	for _, api := range []int{33, 35} {
		for _, focus := range []string{"", "isolated", "steal"} {
			want := focus
			if want == "" {
				want = "isolated"
			}
			isolated := api >= 34 && want == "isolated"
			result := Screen{DisplayID: 7, Width: 720, Height: 1280, DPI: 240, Focus: want, FocusIsolated: isolated}
			response, _ := json.Marshal(map[string]any{"id": 1, "ok": true, "result": result})
			c, h := fakeClient(t, nil)
			h.state.APILevel = api
			requests := make(chan map[string]any, 2)
			c.dial = func(context.Context, string) (net.Conn, error) {
				a, b := net.Pipe()
				go func() {
					defer b.Close()
					line, _ := bufio.NewReader(b).ReadBytes('\n')
					var req map[string]any
					_ = json.Unmarshal(line, &req)
					requests <- req
					data := string(response) + "\n"
					if req["method"] == "hello" {
						data = helloJSON
						if api == 33 {
							data = strings.ReplaceAll(strings.ReplaceAll(strings.ReplaceAll(data, `"apiLevel":35`, `"apiLevel":33`), `"groupAwakeQuery":true`, `"groupAwakeQuery":false`), `"focusIsolation":true`, `"focusIsolation":false`)
						}
					}
					_, _ = b.Write([]byte(data))
				}()
				return a, nil
			}
			got, err := c.Create(context.Background(), "SERIAL", 720, 1280, 240, focus)
			if err != nil || got.Focus != want || got.FocusIsolated != isolated {
				t.Fatalf("api=%d focus=%q: %+v %v", api, focus, got, err)
			}
			<-requests
			request := <-requests
			if request["focus"] != want || request["width"] != float64(720) || len(request) != 6 || h.removed != 1 {
				t.Fatal("wire/cleanup", request, h)
			}
		}
	}
	c, h := fakeClient(t, nil)
	_, err := c.Create(context.Background(), "SERIAL", 720, 1280, 240, "raw")
	requireCode(t, err, apperr.CodeInvalidArgument)
	if h.forwarded != 0 {
		t.Fatal("invalid focus reached device")
	}
}

func TestFocusPolicyResponsesRejectMissingUnknownAndFalseIsolation(t *testing.T) {
	for _, fields := range []string{``, `,"focus":"other","focusIsolated":true`, `,"focus":"isolated","focusIsolated":false`, `,"focus":"steal","focusIsolated":true`} {
		screen := `{"displayId":7,"width":720,"height":1280,"dpi":240` + fields + `}`
		for _, method := range []string{"create", "list", "status"} {
			result := screen
			if method == "list" {
				result = `{"displays":[` + screen + `]}`
			}
			if method == "status" {
				result = `{"displays":[` + screen + `],"keepAwakeIntervalMs":2000}`
			}
			c, _ := fakeClient(t, []byte(`{"id":1,"ok":true,"result":`+result+"}\n"))
			var err error
			switch method {
			case "create":
				_, err = c.Create(context.Background(), "SERIAL", 720, 1280, 240, "isolated")
			case "list":
				_, err = c.List(context.Background(), "SERIAL")
			case "status":
				_, err = c.Status(context.Background(), "SERIAL")
			}
			requireCode(t, err, apperr.CodeProtocol)
		}
	}
}

type fakeHost struct {
	removed, forwarded int
	state              adb.DisplaydState
	inspectErr         error
}

func (f *fakeHost) InspectDisplayd(context.Context, string) (adb.DisplaydState, error) {
	return f.state, f.inspectErr
}
func (f *fakeHost) ForwardAbstract(context.Context, string, string) (adb.Forward, error) {
	f.forwarded++
	return adb.Forward{LocalPort: 12345}, nil
}
func (f *fakeHost) RemoveForward(context.Context, string, int) error { f.removed++; return nil }
func (f *fakeHost) DeployDisplayd(context.Context, string, string, string, bool) (bool, error) {
	return false, nil
}
func (f *fakeHost) StartDisplayd(context.Context, string) error       { return nil }
func (f *fakeHost) RemoveDisplaydFiles(context.Context, string) error { return nil }

func fakeClient(t *testing.T, response []byte) (*Client, *fakeHost) {
	t.Helper()
	h := &fakeHost{state: adb.DisplaydState{APILevel: 35, PID: 42, Bound: true}}
	c := New(h)
	calls := 0
	c.dial = func(ctx context.Context, address string) (net.Conn, error) {
		peer, server := net.Pipe()
		calls++
		data := response
		if calls%2 == 1 {
			data = []byte(helloJSON)
		}
		go func() {
			defer server.Close()
			_, _ = bufio.NewReader(server).ReadString('\n')
			_, _ = server.Write(data)
			var one [1]byte
			_, _ = server.Read(one[:])
		}()
		return peer, nil
	}
	return c, h
}

func requireCode(t *testing.T, err error, code string) {
	t.Helper()
	var e *apperr.Error
	if !errors.As(err, &e) || e.Code != code {
		t.Fatalf("error=%v, want %s", err, code)
	}
}

func TestCaptureReadsExactBytesWithoutEOF(t *testing.T) {
	var body bytes.Buffer
	_ = png.Encode(&body, image.NewRGBA(image.Rect(0, 0, 128, 128)))
	head, _ := json.Marshal(map[string]any{"id": 1, "ok": true, "result": map[string]any{"displayId": 2, "width": 128, "height": 128, "format": "png", "bytes": body.Len()}})
	c, h := fakeClient(t, append(append(head, '\n'), body.Bytes()...))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	frame, err := c.Capture(ctx, "SERIAL", 2)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(frame.Bytes, body.Bytes()) {
		t.Fatal("frame mismatch")
	}
	if h.forwarded != 1 || h.removed != 1 {
		t.Fatalf("forward leak: %+v", h)
	}
}

func TestCaptureRejectsBadResponseAndCleansForward(t *testing.T) {
	for name, data := range map[string]string{
		"oversize header": strings.Repeat(" ", 16385),
		"oversize frame":  `{"id":1,"ok":true,"result":{"displayId":2,"width":128,"height":128,"format":"png","bytes":16777217}}` + "\n",
		"negative frame":  `{"id":1,"ok":true,"result":{"displayId":2,"width":128,"height":128,"format":"png","bytes":-1}}` + "\n",
		"wrong id":        `{"id":9,"ok":true,"result":{}}` + "\n",
		"wrong display":   `{"id":1,"ok":true,"result":{"displayId":0,"width":128,"height":128,"format":"png","bytes":4}}` + "\nBAD!",
		"malformed":       "{}\n",
	} {
		t.Run(name, func(t *testing.T) {
			c, h := fakeClient(t, []byte(data))
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			_, err := c.Capture(ctx, "SERIAL", 2)
			if err == nil {
				t.Fatal("bad response accepted")
			}
			if h.removed != 1 {
				t.Fatal("forward leaked")
			}
		})
	}
}

func TestHelperErrorsRemainDistinct(t *testing.T) {
	for _, code := range []string{"DISPLAY_NOT_FOUND", "DEVICE_LOCKED", "DISPLAY_ASLEEP", "UNSUPPORTED_API", "FRAME_NOT_READY", "LOCK_STATE_UNAVAILABLE"} {
		t.Run(code, func(t *testing.T) {
			c, h := fakeClient(t, []byte(`{"id":1,"ok":false,"error":{"code":"`+code+`","message":"test"}}`+"\n"))
			_, err := c.Capture(context.Background(), "SERIAL", 2)
			requireCode(t, err, code)
			if h.removed != 1 {
				t.Fatal("forward leaked")
			}
		})
	}
}

func TestIdentityFailureNeverForwardsOrDials(t *testing.T) {
	c, h := fakeClient(t, nil)
	h.inspectErr = apperr.New(apperr.CodeDisplaydIdentity, "untrusted", false, nil)
	_, err := c.Capture(context.Background(), "SERIAL", 2)
	requireCode(t, err, apperr.CodeDisplaydIdentity)
	if h.forwarded != 0 {
		t.Fatal("untrusted socket forwarded")
	}
}

func TestDialFailureStillRemovesForward(t *testing.T) {
	c, h := fakeClient(t, nil)
	c.dial = func(context.Context, string) (net.Conn, error) { return nil, errors.New("disconnected") }
	_, err := c.List(context.Background(), "SERIAL")
	requireCode(t, err, apperr.CodeDisplaydNotRunning)
	if h.removed != 1 {
		t.Fatal("forward leaked on dial failure")
	}
}

func TestMissingHelperProcessAndInvalidDisplayNeverConnect(t *testing.T) {
	c, h := fakeClient(t, nil)
	h.state.PID = 0
	_, err := c.Capture(context.Background(), "SERIAL", 2)
	requireCode(t, err, apperr.CodeDisplaydNotRunning)
	h.state.PID = 42
	_, err = c.Capture(context.Background(), "SERIAL", 0)
	requireCode(t, err, apperr.CodeDisplayNotFound)
	if h.forwarded != 0 {
		t.Fatal("unexpected forward")
	}
}

func TestVersionMismatchAndDisconnectRemoveForward(t *testing.T) {
	for _, data := range []string{strings.Replace(helloJSON, `"protocolVersion":2`, `"protocolVersion":1`, 1), ""} {
		t.Run(data, func(t *testing.T) {
			c, h := fakeClient(t, nil)
			c.dial = func(context.Context, string) (net.Conn, error) {
				a, b := net.Pipe()
				go func() { defer b.Close(); _, _ = bufio.NewReader(b).ReadString('\n'); _, _ = b.Write([]byte(data)) }()
				return a, nil
			}
			_, err := c.List(context.Background(), "SERIAL")
			if err == nil {
				t.Fatal("invalid handshake accepted")
			}
			if h.removed != 1 {
				t.Fatal("forward leaked")
			}
		})
	}
}
