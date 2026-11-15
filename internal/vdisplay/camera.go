// Package vdisplay 管理操作员启用的虚拟摄像头与有界图片传输，模型不接触主机文件路径。
package vdisplay

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg"
	"io"
	"os"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

// CameraHost 只对操作员路径开放固定关联与临时图片操作，不扩展任意 shell 能力。
type CameraHost interface {
	EnsureCameraAssociation(context.Context, string) (adb.CameraAssociation, error)
	CleanupCameraAssociation(context.Context, string, int) error
	StartDisplaydCamera(context.Context, string, adb.CameraAssociation) error
	PushCameraImage(context.Context, string, string, string) error
	RemoveCameraImage(context.Context, string, string) error
	CleanupCameraImages(context.Context, string) error
}

// CameraInfo 如实报告设备归属和逐流绘制状态；无流时 Streams 为空数组。
type CameraInfo struct {
	CameraID           string         `json:"cameraId"`
	VirtualDeviceID    int            `json:"virtualDeviceId"`
	Streams            []CameraStream `json:"streams"`
	Availability       string         `json:"availability,omitempty"`
	AvailabilityReason *string        `json:"availabilityReason,omitempty"`
}

// CameraStream 是实际流的渲染诊断，不将注册成功等同于画面读回成功。
type CameraStream struct {
	StreamID int     `json:"streamId"`
	Width    int     `json:"width"`
	Height   int     `json:"height"`
	Frames   int64   `json:"frames"`
	Error    *string `json:"error"`
}

// CameraImageResult 确认指定虚拟屏已接收新图片，不声称应用已消费该帧。
type CameraImageResult struct {
	DisplayID int  `json:"displayId"`
	ImageSet  bool `json:"imageSet"`
}

func cameraNotEnabled() error {
	return apperr.New(apperr.CodeVirtualCameraNotEnabled, "Helper does not expose virtual camera. Operator must stop and restart with aactl display start --device SERIAL --virtual-camera (rebuild an older helper).", false, nil)
}

// StartWithCamera 仅由操作员 CLI 显式调用；已有普通 helper 不会被悄悄改为摄像头模式。
func (c *Client) StartWithCamera(ctx context.Context, serial, helper string) (Hello, error) {
	return c.start(ctx, serial, helper, true)
}

// CreateWithCamera 仅增加 back 枚举；普通屏继续使用原来的 Create 与线协议字段。
func (c *Client) CreateWithCamera(ctx context.Context, serial string, width, height, dpi int, focus, camera string) (Screen, error) {
	if camera == "" {
		return c.Create(ctx, serial, width, height, dpi, focus)
	}
	if camera != "back" {
		return Screen{}, apperr.New(apperr.CodeInvalidArgument, "camera must be back", false, nil)
	}
	focus, err := NormalizeFocus(focus)
	if err != nil {
		return Screen{}, err
	}
	if !dimensions(width, height, dpi) {
		return Screen{}, apperr.New(apperr.CodeInvalidArgument, "width/height must be 128..2048, dpi 72..640", false, nil)
	}
	var r Screen
	_, err = c.call(ctx, serial, "display.create", map[string]any{"width": width, "height": height, "dpi": dpi, "focus": focus, "camera": camera}, &r)
	if err == nil && (r.DisplayID <= 0 || r.Width != width || r.Height != height || r.DPI != dpi || r.Focus != focus || r.Camera != camera) {
		err = protocolError("Invalid camera display result")
	}
	return r, err
}

const maxCameraImage = 8 * 1024 * 1024

// Android Display.getFlags()结果命名空间，不能用于VirtualDisplayConfig的请求标志。
const (
	androidDisplayFlagSecure                = 0x0002
	androidDisplayFlagOwnFocus              = 0x0800
	androidDisplayFlagStealTopFocusDisabled = 0x1000
)

func invalidImage(reason string) error {
	return apperr.New(apperr.CodeCameraImageInvalid, reason, false, nil)
}

// cameraImage 固定读取一次并检查解码预算；push 使用快照，避免校验与传输之间源文件变化。
func cameraImage(path string) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, invalidImage("Cannot open local camera image")
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > maxCameraImage {
		return nil, invalidImage("Camera image must be a regular PNG/JPEG file <= 8 MiB")
	}
	data, err := io.ReadAll(io.LimitReader(file, maxCameraImage+1))
	if err != nil || len(data) > maxCameraImage {
		return nil, invalidImage("Cannot read camera image within 8 MiB budget")
	}
	config, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || (format != "png" && format != "jpeg") || config.Width < 1 || config.Height < 1 || config.Width > 4096 || config.Height > 4096 {
		return nil, invalidImage("Expected PNG/JPEG with decoded dimensions 1..4096")
	}
	if _, _, err = image.Decode(bytes.NewReader(data)); err != nil {
		return nil, invalidImage("Camera image decoding failed")
	}
	return data, nil
}

// SetCameraImage 在已验证目标与能力后传输快照，任何失败都用同一随机路径清理，绝不回退主屏。
func (c *Client) SetCameraImage(ctx context.Context, serial string, id int, path string) (result CameraImageResult, returnErr error) {
	if err := displayID(id); err != nil {
		return result, err
	}
	if err := adb.ValidateSerial(serial); err != nil {
		return result, err
	}
	data, err := cameraImage(path)
	if err != nil {
		return result, err
	}
	var hello Hello
	if _, err = c.call(ctx, serial, "hello", nil, &hello); err != nil {
		return result, err
	}
	if !hello.Capabilities["virtualCamera"] {
		return result, cameraNotEnabled()
	}
	list, err := c.List(ctx, serial)
	if err != nil {
		return result, err
	}
	owned := false
	for _, screen := range list.Displays {
		if screen.DisplayID == id {
			owned = true
			if screen.Camera != "back" {
				return result, cameraNotEnabled()
			}
			break
		}
	}
	if !owned {
		return result, apperr.New(apperr.CodeDisplayNotFound, "Display is not owned by this helper", false, nil)
	}
	host, ok := c.host.(CameraHost)
	if !ok {
		return result, cameraNotEnabled()
	}
	var uuid [16]byte
	if _, err = rand.Read(uuid[:]); err != nil {
		return result, err
	}
	uuid[6] = (uuid[6] & 15) | 64
	uuid[8] = (uuid[8] & 63) | 128
	remote := fmt.Sprintf("/data/local/tmp/aactl-camera-%x-%x-%x-%x-%x.img", uuid[:4], uuid[4:6], uuid[6:8], uuid[8:10], uuid[10:])
	file, err := os.CreateTemp("", "aactl-camera-*.img")
	if err != nil {
		return result, err
	}
	defer func() { returnErr = errors.Join(returnErr, os.Remove(file.Name())) }()
	_, writeErr := file.Write(data)
	closeErr := file.Close()
	if err = errors.Join(writeErr, closeErr); err != nil {
		return result, err
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		returnErr = errors.Join(returnErr, host.RemoveCameraImage(cleanup, serial, remote))
	}()
	if err = host.PushCameraImage(ctx, serial, file.Name(), remote); err != nil {
		return result, err
	}
	_, err = c.call(ctx, serial, "camera.setImage", map[string]any{"displayId": id, "path": remote}, &result)
	if err == nil && (result.DisplayID != id || !result.ImageSet) {
		err = protocolError("Invalid camera image response")
	}
	return result, err
}
