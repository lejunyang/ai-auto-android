// Package adb 提供固定参数的设备访问，摄像头关联仅使用本工具保留的包名与标记地址。
package adb

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

const cameraPackage = "com.android.shell"
const cameraMAC = "00:00:00:00:00:10"
const cameraProfile = "android.app.role.COMPANION_DEVICE_APP_STREAMING"

var companionMaxIDLine = regexp.MustCompile(`^Max ID: [0-9]+$`)

// CameraAssociation 是当前前台用户的工具专用伴生关联；编号只从系统 list 读回。
type CameraAssociation struct{ UserID, ID int }

func cameraUnavailable(reason string) error {
	return apperr.New(apperr.CodeVirtualCameraUnavailable, reason, false, nil)
}

// parseCameraAssociation 兼容 AOSP 新旧 list 格式，拒绝未知行与重复标记，绝不按子串删除。
func parseCameraAssociation(output string) (int, error) {
	found := 0
	for _, line := range strings.Split(strings.TrimSpace(output), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || line == "Association ID | Package Name | Mac Address" || companionMaxIDLine.MatchString(line) {
			continue
		}
		var pkg, mac, raw string
		if strings.Contains(line, "|") {
			fields := strings.Split(line, "|")
			if len(fields) != 3 {
				return 0, cameraUnavailable("Unrecognized companiondevice list format")
			}
			raw, pkg, mac = strings.TrimSpace(fields[0]), strings.TrimSpace(fields[1]), strings.TrimSpace(fields[2])
		} else {
			fields := strings.Fields(line)
			if len(fields) != 3 {
				return 0, cameraUnavailable("Companiondevice list must include association IDs")
			}
			pkg, mac, raw = fields[0], fields[1], fields[2]
		}
		id, err := strconv.Atoi(raw)
		if err != nil || id <= 0 || id > 999999999 {
			return 0, cameraUnavailable("Invalid companion association ID")
		}
		if pkg == cameraPackage && strings.EqualFold(mac, cameraMAC) {
			if found != 0 {
				return 0, cameraUnavailable("Duplicate tool camera associations; operator inspection required")
			}
			found = id
		}
	}
	return found, nil
}

func (c *Client) cameraUser(ctx context.Context, serial string) (int, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return 0, err
	}
	r, err := c.run(ctx, []string{"-s", serial, "shell", "am", "get-current-user"}, process.Options{})
	if err != nil {
		return 0, err
	}
	user, err := strconv.Atoi(strings.TrimSpace(string(r.Stdout)))
	if err != nil || r.OutputTruncated || user < 0 || user > 2147483647 {
		return 0, cameraUnavailable("Cannot determine foreground user")
	}
	return user, nil
}
func (c *Client) cameraAssociation(ctx context.Context, serial string, user int) (int, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return 0, err
	}
	r, err := c.run(ctx, []string{"-s", serial, "shell", "cmd", "companiondevice", "list", strconv.Itoa(user)}, process.Options{})
	if err != nil {
		return 0, cameraUnavailable("Cannot list companion associations: " + err.Error())
	}
	if r.OutputTruncated {
		return 0, cameraUnavailable("Companiondevice list was truncated")
	}
	return parseCameraAssociation(string(r.Stdout))
}

// FindCameraAssociation 只读查询精确标记；user=-1读取当前用户，ID=0表示没有可保留的关联。
func (c *Client) FindCameraAssociation(ctx context.Context, serial string, user int) (CameraAssociation, error) {
	if user < 0 {
		var err error
		user, err = c.cameraUser(ctx, serial)
		if err != nil {
			return CameraAssociation{}, err
		}
	}
	id, err := c.cameraAssociation(ctx, serial, user)
	return CameraAssociation{UserID: user, ID: id}, err
}

// EnsureCameraAssociation 复用精确遗留标记；新建失败也按同一包名和地址清理，不涉及其它关联。
func (c *Client) EnsureCameraAssociation(ctx context.Context, serial string) (association CameraAssociation, returnErr error) {
	user, err := c.cameraUser(ctx, serial)
	if err != nil {
		return association, err
	}
	association.UserID = user
	id, err := c.cameraAssociation(ctx, serial, user)
	if err != nil {
		return association, err
	}
	if id != 0 {
		association.ID = id
		return association, nil
	}
	defer func() {
		if returnErr != nil {
			cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
			defer cancel()
			returnErr = errors.Join(returnErr, c.CleanupCameraAssociation(cleanup, serial, user))
		}
	}()
	if err = c.requireOnlineDevice(ctx, serial); err != nil {
		return association, err
	}
	_, err = c.run(ctx, []string{"-s", serial, "shell", "cmd", "companiondevice", "associate", strconv.Itoa(user), cameraPackage, cameraMAC, cameraProfile, "true"}, process.Options{})
	if err != nil {
		return association, cameraUnavailable("Cannot create camera companion association: " + err.Error())
	}
	association.ID, err = c.cameraAssociation(ctx, serial, user)
	if err != nil {
		return association, err
	}
	if association.ID == 0 {
		return association, cameraUnavailable("Camera association missing after associate")
	}
	return association, nil
}

// CleanupCameraAssociation 仅精确匹配工具保留标记，user=-1 时读取当前用户，用于崩溃后的幂等清理。
func (c *Client) CleanupCameraAssociation(ctx context.Context, serial string, user int) error {
	if user < 0 {
		var err error
		user, err = c.cameraUser(ctx, serial)
		if err != nil {
			return err
		}
	}
	id, err := c.cameraAssociation(ctx, serial, user)
	if err != nil || id == 0 {
		return err
	}
	if err = c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	_, err = c.run(ctx, []string{"-s", serial, "shell", "cmd", "companiondevice", "disassociate", strconv.Itoa(user), cameraPackage, cameraMAC}, process.Options{})
	if err != nil {
		return err
	}
	id, err = c.cameraAssociation(ctx, serial, user)
	if err == nil && id != 0 {
		return cameraUnavailable("Tool camera association survived disassociate")
	}
	return err
}

// StartDisplaydCamera 仅把已验证的十进制用户与关联编号附加到固定 shell 入口。
func (c *Client) StartDisplaydCamera(ctx context.Context, serial string, association CameraAssociation) error {
	if association.ID < 1 || association.ID > 999999999 || association.UserID < 0 {
		return cameraUnavailable("Invalid camera association")
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	_, err := c.run(ctx, []string{"-s", serial, "shell", "CLASSPATH=" + displaydAPK + " nohup app_process / --nice-name=aactl-displayd dev.aiauto.displayd.Main --virtual-camera-association " + strconv.Itoa(association.ID) + " " + strconv.Itoa(association.UserID) + " </dev/null >" + displaydLog + " 2>&1 &"}, process.Options{})
	return err
}

var cameraImagePath = regexp.MustCompile(`^/data/local/tmp/aactl-camera-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.img$`)

// PushCameraImage 不复用面向模型的文件传输能力，目标只能是工具生成的随机图片路径。
func (c *Client) PushCameraImage(ctx context.Context, serial, local, remote string) error {
	if !cameraImagePath.MatchString(remote) {
		return apperr.New(apperr.CodeCameraImageInvalid, "Invalid camera transfer path", false, nil)
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	_, err := c.run(ctx, []string{"-s", serial, "push", local, remote}, process.Options{})
	return err
}

// RemoveCameraImage 只删除经过模式验证的单一路径；不接受通配符或目录。
func (c *Client) RemoveCameraImage(ctx context.Context, serial, remote string) error {
	if !cameraImagePath.MatchString(remote) {
		return fmt.Errorf("invalid camera transfer cleanup path")
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	_, err := c.run(ctx, []string{"-s", serial, "shell", "rm", "-f", remote}, process.Options{})
	return err
}

// CleanupCameraImages 回收崩溃遗留的精确 UUID 图片；不使用远端通配符，不删除其它临时文件。
func (c *Client) CleanupCameraImages(ctx context.Context, serial string) error {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	r, err := c.run(ctx, []string{"-s", serial, "shell", "ls", "-1", "/data/local/tmp"}, process.Options{})
	if err != nil {
		return err
	}
	if r.OutputTruncated {
		return cameraUnavailable("Temporary directory listing was truncated; refusing partial cleanup")
	}
	for _, name := range strings.Split(string(r.Stdout), "\n") {
		remote := "/data/local/tmp/" + strings.TrimSpace(name)
		if cameraImagePath.MatchString(remote) {
			if err = c.RemoveCameraImage(ctx, serial, remote); err != nil {
				return err
			}
		}
	}
	return nil
}
