// Package vdisplay 管理 shell 身份 displayd 的部署、身份检查和有界类型化协议。
package vdisplay

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"image/png"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Host 限定设备操作范围，不向本包暴露任意 shell 方法。
type Host interface {
	InspectDisplayd(context.Context, string) (adb.DisplaydState, error)
	ForwardAbstract(context.Context, string, string) (adb.Forward, error)
	RemoveForward(context.Context, string, int) error
	DeployDisplayd(context.Context, string, string, string, bool) (bool, error)
	StartDisplayd(context.Context, string) error
	RemoveDisplaydFiles(context.Context, string) error
}

// Client 持有设备操作依赖，每次调用自行建立并清理 forward。
type Client struct {
	host Host
	dial func(context.Context, string) (net.Conn, error)
}

// New 创建无持久连接的客户端。
func New(host Host) *Client {
	return &Client{host: host, dial: func(ctx context.Context, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", address)
	}}
}

const maxMessage = 16384
const maxFrame = 16777216

// Hello 固定协议版本、能力和预算，客户端不会采信更大的远端上限。
type Hello struct {
	ProtocolVersion   int             `json:"protocolVersion"`
	APILevel          int             `json:"apiLevel"`
	Supported         bool            `json:"supported"`
	Capabilities      map[string]bool `json:"capabilities"`
	Limits            map[string]int  `json:"limits"`
	VirtualCameraUser int             `json:"virtualCameraUser,omitempty"`
}

// Screen 是辅助进程拥有的虚拟屏尺寸与标识。
type Screen struct {
	DisplayID     int         `json:"displayId"`
	Width         int         `json:"width"`
	Height        int         `json:"height"`
	DPI           int         `json:"dpi"`
	Focus         string      `json:"focus"`
	FocusIsolated bool        `json:"focusIsolated"`
	Camera        string      `json:"camera,omitempty"`
	CameraInfo    *CameraInfo `json:"cameraInfo,omitempty"`
	DisplayFlags  *int        `json:"displayFlags,omitempty"`
}

// NormalizeFocus 限定二值策略并提供安全默认值；不接受原始框架标志。
func NormalizeFocus(focus string) (string, error) {
	if focus == "" {
		return "isolated", nil
	}
	if focus != "isolated" && focus != "steal" {
		return "", apperr.New(apperr.CodeInvalidArgument, "focus must be isolated or steal.", false, nil)
	}
	return focus, nil
}

func validScreenFocus(screen Screen, api int) bool {
	if screen.Camera != "" {
		return screen.Camera == "back" && api >= 35 && screen.CameraInfo != nil && screen.CameraInfo.VirtualDeviceID > 0 && screen.CameraInfo.CameraID != "" && screen.DisplayFlags != nil && (*screen.DisplayFlags&androidDisplayFlagSecure) == 0 && screen.FocusIsolated == ((*screen.DisplayFlags&androidDisplayFlagOwnFocus) != 0 && (*screen.DisplayFlags&androidDisplayFlagStealTopFocusDisabled) != 0) && (screen.Focus == "isolated" || screen.Focus == "steal")
	}
	return (screen.Focus == "isolated" || screen.Focus == "steal") && screen.FocusIsolated == (api >= 34 && screen.Focus == "isolated")
}

// ListResult 列出本进程拥有的虚拟屏。
type ListResult struct {
	Displays []Screen `json:"displays"`
}

// ScreenStatus 包含独立显示组状态；API 33 的 GroupAwake 保留未知值。
type ScreenStatus struct {
	Screen
	State          int   `json:"state"`
	DisplayGroupID int   `json:"displayGroupId"`
	GroupAwake     *bool `json:"groupAwake"`
	RemoveMode     *int  `json:"removeMode,omitempty"`
}

// Status 透传锁定、保活与显示组状态，不把 API 33 的未知值当作唤醒。
type Status struct {
	CredentialLocked     bool           `json:"credentialLocked"`
	Displays             []ScreenStatus `json:"displays"`
	KeepAwakeIntervalMS  int            `json:"keepAwakeIntervalMs"`
	LastActivityUptimeMS int64          `json:"lastActivityUptimeMs"`
	KeepAwakeError       *string        `json:"keepAwakeError"`
}

// CloseResult 确认被销毁的显示目标。
type CloseResult struct {
	DisplayID int  `json:"displayId"`
	Closed    bool `json:"closed"`
}

// StopResult 确认进程及其固定临时文件已经释放。
type StopResult struct {
	Stopped bool `json:"stopped"`
}

type captureHeader struct {
	DisplayID int    `json:"displayId"`
	Width     int    `json:"width"`
	Height    int    `json:"height"`
	Format    string `json:"format"`
	Bytes     int    `json:"bytes"`
}

func protocolError(message string) error { return apperr.New(apperr.CodeProtocol, message, false, nil) }
func displayID(id int) error {
	if id <= 0 || id > 2147483647 {
		return apperr.New(apperr.CodeDisplayNotFound, "Expected a positive helper-owned display ID; refusing primary-display fallback.", false, nil)
	}
	return nil
}
func dimensions(w, h, dpi int) bool {
	return w >= 128 && w <= 2048 && h >= 128 && h <= 2048 && dpi >= 72 && dpi <= 640
}

func (c *Client) wire(ctx context.Context, port int, method string, fields map[string]any, out any) ([]byte, error) {
	conn, err := c.dial(ctx, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, apperr.Wrap(apperr.CodeDisplaydNotRunning, "Cannot connect to verified displayd.", true, err)
	}
	defer conn.Close()
	deadline := time.Now().Add(10 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	if err = conn.SetDeadline(deadline); err != nil {
		return nil, err
	}
	stop := context.AfterFunc(ctx, func() { _ = conn.SetDeadline(time.Now()) })
	defer stop()
	request := map[string]any{"id": 1, "method": method}
	for k, v := range fields {
		request[k] = v
	}
	data, _ := json.Marshal(request)
	if _, err = conn.Write(append(data, '\n')); err != nil {
		return nil, protocolError("Could not send displayd request.")
	}
	reader := bufio.NewReaderSize(conn, maxMessage+1)
	line, err := reader.ReadSlice('\n')
	if err != nil || len(line) > maxMessage+1 {
		return nil, protocolError("Displayd response header is truncated, timed out, or exceeds 16384 bytes.")
	}
	var response struct {
		ID     *int            `json:"id"`
		OK     *bool           `json:"ok"`
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(line, &response) != nil || response.OK == nil || response.ID == nil || *response.ID != 1 {
		return nil, protocolError("Malformed displayd response envelope.")
	}
	if !*response.OK {
		if response.Error == nil {
			return nil, protocolError("Missing displayd error.")
		}
		// 仅接受设备端 README 的固定错误集合，未知协议错误不能伪造电脑侧错误语义。
		allowed := map[string]bool{"INVALID_REQUEST": true, "UNKNOWN_METHOD": true, "INVALID_ARGUMENT": true, "MESSAGE_TOO_LARGE": true, "FRAME_TOO_LARGE": true, "DISPLAY_LIMIT": true, "UNSUPPORTED_API": true, "DISPLAY_NOT_FOUND": true, "FRAME_NOT_READY": true, "DEVICE_LOCKED": true, "DISPLAY_ASLEEP": true, "LOCK_STATE_UNAVAILABLE": true, "CAPTURE_FAILED": true, "FRAMEWORK_ERROR": true, "SHUTTING_DOWN": true}
		allowed[apperr.CodeVirtualCameraNotEnabled] = true
		allowed[apperr.CodeVirtualCameraUnavailable] = true
		allowed[apperr.CodeCameraImageInvalid] = true
		if !allowed[response.Error.Code] {
			return nil, protocolError("Unknown displayd error code.")
		}
		return nil, apperr.New(response.Error.Code, response.Error.Message, response.Error.Code == "FRAME_NOT_READY", nil)
	}
	if response.Error != nil || len(response.Result) == 0 || bytes.Equal(response.Result, []byte("null")) || json.Unmarshal(response.Result, out) != nil {
		return nil, protocolError("Invalid displayd result.")
	}
	if method != "display.capture" {
		return nil, nil
	}
	h := out.(*captureHeader)
	if h.DisplayID != fields["displayId"] || h.Format != "png" || !dimensions(h.Width, h.Height, 72) || h.Bytes < 1 || h.Bytes > maxFrame {
		return nil, protocolError("Invalid displayd frame metadata or byte budget.")
	}
	// 验证固定上限后才分配；按 bytes 精确读取，不等待服务端 EOF。
	body := make([]byte, h.Bytes)
	if _, err = io.ReadFull(reader, body); err != nil {
		return nil, protocolError("Truncated displayd PNG body.")
	}
	return body, nil
}

func (c *Client) call(ctx context.Context, serial, method string, fields map[string]any, out any) (body []byte, returnErr error) {
	if err := adb.ValidateSerial(serial); err != nil {
		return nil, err
	}
	state, err := c.host.InspectDisplayd(ctx, serial)
	if err != nil {
		return nil, err
	}
	if state.PID <= 0 || !state.Bound {
		return nil, apperr.New(apperr.CodeDisplaydNotRunning, "displayd is not running; use aactl display start --device SERIAL --helper PATH.", true, nil)
	}
	fwd, err := c.host.ForwardAbstract(ctx, serial, adb.DisplaydSocket)
	if err != nil {
		return nil, err
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		returnErr = errors.Join(returnErr, c.host.RemoveForward(cleanup, serial, fwd.LocalPort))
	}()
	// forward 建立后再检查同一 PID 与 inode，避免启动/退出竞态接入抢占者。
	again, err := c.host.InspectDisplayd(ctx, serial)
	if err != nil {
		return nil, err
	}
	if again.PID != state.PID || !again.Bound {
		return nil, apperr.New(apperr.CodeDisplaydIdentity, "Helper identity changed while forwarding.", false, nil)
	}
	var hello Hello
	if _, err = c.wire(ctx, fwd.LocalPort, "hello", nil, &hello); err != nil {
		return nil, err
	}
	if hello.ProtocolVersion != 2 {
		return nil, apperr.New(apperr.CodeVersionMismatch, "displayd protocol v2 is required; rebuild and restart the helper.", false, nil)
	}
	if !hello.Supported || hello.APILevel < 33 {
		return nil, apperr.New(apperr.CodeUnsupportedAPI, "Virtual displays require API 33+.", false, nil)
	}
	limits := map[string]int{"maxMessageBytes": maxMessage, "maxFrameBytes": maxFrame, "maxDisplays": 2, "minDimension": 128, "maxDimension": 2048, "minDpi": 72, "maxDpi": 640}
	if hello.APILevel != state.APILevel || !hello.Capabilities["binaryPng"] || !hello.Capabilities["credentialLockGuard"] || !hello.Capabilities["virtualDisplay"] || hello.Capabilities["groupAwakeQuery"] != (state.APILevel >= 34) || hello.Capabilities["focusIsolation"] != (state.APILevel >= 34) {
		return nil, protocolError("Unexpected helper API or capabilities.")
	}
	for key, value := range limits {
		if hello.Limits[key] != value {
			return nil, protocolError("Unexpected helper protocol limits.")
		}
	}
	if hello.Capabilities["virtualCamera"] && (hello.VirtualCameraUser < 0 || hello.VirtualCameraUser > 2147483647) {
		return nil, protocolError("Invalid virtual camera startup user")
	}
	if method == "hello" {
		*out.(*Hello) = hello
		return nil, nil
	}
	if method == "camera.setImage" || fields["camera"] == "back" {
		if !hello.Capabilities["virtualCamera"] {
			return nil, cameraNotEnabled()
		}
	}
	body, err = c.wire(ctx, fwd.LocalPort, method, fields, out)
	if err != nil {
		return nil, err
	}
	// 版本握手后仍逐屏核对策略与实际能力，不能把API33请求isolated误报为隔离成功。
	var screens []Screen
	switch r := out.(type) {
	case *Screen:
		screens = []Screen{*r}
	case *ListResult:
		screens = r.Displays
	case *Status:
		for _, entry := range r.Displays {
			screens = append(screens, entry.Screen)
		}
	}
	for _, screen := range screens {
		if !validScreenFocus(screen, hello.APILevel) {
			return nil, protocolError("Invalid display focus policy or isolation capability.")
		}
	}
	return body, nil
}

// Capture 读取指定虚拟屏的 PNG。
func (c *Client) Capture(ctx context.Context, serial string, id int) (adb.Screenshot, error) {
	if err := displayID(id); err != nil {
		return adb.Screenshot{}, err
	}
	var head captureHeader
	body, err := c.call(ctx, serial, "display.capture", map[string]any{"displayId": id}, &head)
	if err != nil {
		return adb.Screenshot{}, err
	}
	config, err := png.DecodeConfig(bytes.NewReader(body))
	if err != nil || config.Width != head.Width || config.Height != head.Height {
		return adb.Screenshot{}, protocolError("Invalid PNG dimensions.")
	}
	if _, err = png.Decode(bytes.NewReader(body)); err != nil {
		return adb.Screenshot{}, protocolError("Invalid PNG body.")
	}
	sum := sha256.Sum256(body)
	return adb.Screenshot{Bytes: body, SHA256: hex.EncodeToString(sum[:])}, nil
}

// List 列出辅助进程拥有的屏。
func (c *Client) List(ctx context.Context, serial string) (ListResult, error) {
	var result ListResult
	_, err := c.call(ctx, serial, "display.list", nil, &result)
	if err == nil {
		if result.Displays == nil || len(result.Displays) > 2 {
			return result, protocolError("Invalid display list.")
		}
		seen := map[int]bool{}
		for _, screen := range result.Displays {
			if screen.DisplayID <= 0 || seen[screen.DisplayID] || !dimensions(screen.Width, screen.Height, screen.DPI) {
				return result, protocolError("Invalid owned display.")
			}
			seen[screen.DisplayID] = true
		}
	}
	return result, err
}

// RequireDisplay 确认目标属于辅助进程，任何失败都不能继续向主屏发送动作。
func (c *Client) RequireDisplay(ctx context.Context, serial string, id int) error {
	if err := displayID(id); err != nil {
		return err
	}
	list, err := c.List(ctx, serial)
	if err != nil {
		return err
	}
	for _, screen := range list.Displays {
		if screen.DisplayID == id {
			return nil
		}
	}
	return apperr.New(apperr.CodeDisplayNotFound, "Display is not owned by this helper.", false, nil)
}

// Create 校验尺寸后创建新屏，不允许调用方指定框架标志。
func (c *Client) Create(ctx context.Context, serial string, width, height, dpi int, focus string) (Screen, error) {
	focus, err := NormalizeFocus(focus)
	if err != nil {
		return Screen{}, err
	}
	if !dimensions(width, height, dpi) {
		return Screen{}, apperr.New(apperr.CodeInvalidArgument, "width/height must be 128..2048, dpi 72..640.", false, nil)
	}
	var r Screen
	_, err = c.call(ctx, serial, "display.create", map[string]any{"width": width, "height": height, "dpi": dpi, "focus": focus}, &r)
	if err == nil && (r.DisplayID <= 0 || r.Width != width || r.Height != height || r.DPI != dpi || r.Focus != focus) {
		err = protocolError("Invalid created display.")
	}
	return r, err
}

// Close 销毁目标虚拟屏及其上的应用内容。
func (c *Client) Close(ctx context.Context, serial string, id int) (CloseResult, error) {
	if err := displayID(id); err != nil {
		return CloseResult{}, err
	}
	var r CloseResult
	_, err := c.call(ctx, serial, "display.close", map[string]any{"displayId": id}, &r)
	if err == nil && (r.DisplayID != id || !r.Closed) {
		err = protocolError("Invalid close result.")
	}
	return r, err
}

// Status 读取显示组、凭据锁和保活状态。
func (c *Client) Status(ctx context.Context, serial string) (Status, error) {
	var r Status
	_, err := c.call(ctx, serial, "status", nil, &r)
	if err == nil && (r.Displays == nil || len(r.Displays) > 2 || r.KeepAwakeIntervalMS != 2000 || r.LastActivityUptimeMS < 0) {
		err = protocolError("Invalid helper status.")
	}
	return r, err
}

// ResolveHelper 按显式路径、环境变量、可执行文件同目录的顺序寻找 APK。
func ResolveHelper(explicit string) (string, error) {
	path := explicit
	if path == "" {
		path = os.Getenv("AACTL_DISPLAYD_APK")
	}
	if path == "" {
		exe, err := os.Executable()
		if err != nil {
			return "", err
		}
		path = filepath.Join(filepath.Dir(exe), "aactl-displayd.apk")
	}
	absolute, err := filepath.Abs(path)
	if err == nil {
		info, e := os.Stat(absolute)
		if e == nil && info.Mode().IsRegular() && info.Size() > 0 && info.Size() <= 64*1024*1024 {
			return absolute, nil
		}
	}
	return "", apperr.New(apperr.CodeDisplaydHelperMissing, "Cannot locate helper APK; build android/displayd and pass --helper PATH, set AACTL_DISPLAYD_APK, or place aactl-displayd.apk beside aactl.", false, nil)
}

// Start 比对 SHA-256、部署并启动；已有实例只在产物一致且身份可验证时复用。
func (c *Client) Start(ctx context.Context, serial, helper string) (Hello, error) {
	return c.start(ctx, serial, helper, false)
}

func (c *Client) start(ctx context.Context, serial, helper string, camera bool) (result Hello, returnErr error) {
	path, err := ResolveHelper(helper)
	if err != nil {
		return Hello{}, err
	}
	state, err := c.host.InspectDisplayd(ctx, serial)
	if err != nil {
		return Hello{}, err
	}
	if state.PID > 0 && !state.Bound {
		return Hello{}, apperr.New(apperr.CodeDisplaydIdentity, "Named helper has no verified listening socket.", false, nil)
	}
	file, err := os.Open(path)
	if err != nil {
		return Hello{}, err
	}
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(file, 64*1024*1024+1))
	_ = file.Close()
	if err != nil || n > 64*1024*1024 {
		return Hello{}, protocolError("Cannot hash helper within size budget.")
	}
	if _, err = c.host.DeployDisplayd(ctx, serial, path, hex.EncodeToString(hash.Sum(nil)), state.PID == 0); err != nil {
		return Hello{}, err
	}
	if state.PID == 0 {
		if camera {
			host, ok := c.host.(CameraHost)
			if !ok {
				return Hello{}, cameraNotEnabled()
			}
			association, e := host.EnsureCameraAssociation(ctx, serial)
			if e != nil {
				return Hello{}, e
			}
			defer func() {
				if returnErr != nil {
					cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
					defer cancel()
					_, stopErr := c.Stop(cleanup, serial)
					returnErr = errors.Join(returnErr, stopErr, host.CleanupCameraAssociation(cleanup, serial, association.UserID))
				}
			}()
			err = host.StartDisplaydCamera(ctx, serial, association)
		} else {
			err = c.host.StartDisplayd(ctx, serial)
		}
		if err != nil {
			return Hello{}, err
		}
	}
	for attempt := 0; attempt < 30; attempt++ {
		state, err = c.host.InspectDisplayd(ctx, serial)
		if err != nil {
			return Hello{}, err
		}
		if state.PID > 0 && state.Bound {
			var r Hello
			_, err = c.call(ctx, serial, "hello", nil, &r)
			if err == nil && camera && !r.Capabilities["virtualCamera"] {
				err = cameraNotEnabled()
			}
			return r, err
		}
		select {
		case <-ctx.Done():
			return Hello{}, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	return Hello{}, apperr.New(apperr.CodeDisplaydNotRunning, "displayd did not bind its socket; inspect /data/local/tmp/aactl-displayd.log (socket may be occupied).", true, nil)
}

// Stop 等待辅助进程退出后移除固定临时文件；不会杀死身份未知的进程。
func (c *Client) Stop(ctx context.Context, serial string) (StopResult, error) {
	user := -1
	state, err := c.host.InspectDisplayd(ctx, serial)
	if err != nil {
		return StopResult{}, err
	}
	if state.PID > 0 {
		if _, ok := c.host.(CameraHost); ok {
			var hello Hello
			if _, err = c.call(ctx, serial, "hello", nil, &hello); err != nil {
				return StopResult{}, err
			}
			if hello.Capabilities["virtualCamera"] {
				user = hello.VirtualCameraUser
			} else {
				user = -2
			}
		}
		var r StopResult
		if _, err = c.call(ctx, serial, "shutdown", nil, &r); err != nil {
			return r, err
		}
		if !r.Stopped {
			return r, protocolError("Helper did not acknowledge shutdown.")
		}
	}
	for attempt := 0; attempt < 30; attempt++ {
		state, err = c.host.InspectDisplayd(ctx, serial)
		if err != nil {
			return StopResult{}, err
		}
		if state.PID == 0 && !state.Bound {
			if host, ok := c.host.(CameraHost); ok && user != -2 {
				if err = host.CleanupCameraAssociation(ctx, serial, user); err != nil {
					return StopResult{}, err
				}
				if err = host.CleanupCameraImages(ctx, serial); err != nil {
					return StopResult{}, err
				}
			}
			if err = c.host.RemoveDisplaydFiles(ctx, serial); err != nil {
				return StopResult{}, err
			}
			return StopResult{Stopped: true}, nil
		}
		select {
		case <-ctx.Done():
			return StopResult{}, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	return StopResult{}, apperr.New(apperr.CodeDeadlineExceeded, "displayd did not exit after shutdown", true, nil)
}
