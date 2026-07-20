package adb

// 本文件实现无需 App Bridge 的截图、UI 层级和白名单输入动作。

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"hash/crc32"
	"image/png"
	"io"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

const (
	maxCoordinate       = 100000
	maxSwipeDurationMS  = 60000
	maxTextLength       = 10000
	maxScreenshotOutput = 32 * 1024 * 1024
	maxHierarchyOutput  = 8 * 1024 * 1024
	hierarchyRemotePath = "/data/local/tmp/aactl-window.xml"
)

var (
	pngSignature   = []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}
	packagePattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$`)
	classPattern   = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$`)
	directText     = regexp.MustCompile(`^[A-Za-z0-9 @._+\-,:/]*$`)
)

var allowedKeys = map[string]string{
	"BACK":        "KEYCODE_BACK",
	"HOME":        "KEYCODE_HOME",
	"RECENTS":     "KEYCODE_APP_SWITCH",
	"ENTER":       "KEYCODE_ENTER",
	"TAB":         "KEYCODE_TAB",
	"ESCAPE":      "KEYCODE_ESCAPE",
	"SPACE":       "KEYCODE_SPACE",
	"DELETE":      "KEYCODE_DEL",
	"FORWARD_DEL": "KEYCODE_FORWARD_DEL",
	"DPAD_UP":     "KEYCODE_DPAD_UP",
	"DPAD_DOWN":   "KEYCODE_DPAD_DOWN",
	"DPAD_LEFT":   "KEYCODE_DPAD_LEFT",
	"DPAD_RIGHT":  "KEYCODE_DPAD_RIGHT",
	"DPAD_CENTER": "KEYCODE_DPAD_CENTER",
	"VOLUME_UP":   "KEYCODE_VOLUME_UP",
	"VOLUME_DOWN": "KEYCODE_VOLUME_DOWN",
	"VOLUME_MUTE": "KEYCODE_VOLUME_MUTE",
}

// Screenshot 保存已验证 PNG 的原始字节和内容摘要。
type Screenshot struct {
	Bytes  []byte
	SHA256 string
}

// Hierarchy 保存已验证 UIAutomator XML 及其大小和摘要。
type Hierarchy struct {
	XML    string `json:"xml"`
	Bytes  int    `json:"sizeBytes"`
	SHA256 string `json:"sha256"`
}

// ActionResult 标识已执行类型化动作的目标设备和动作名称。
type ActionResult struct {
	Device string `json:"device"`
	Action string `json:"action"`
}

// Screenshot 从明确在线设备读取并完整校验 PNG 截图。
func (c *Client) Screenshot(ctx context.Context, serial string) (Screenshot, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return Screenshot{}, err
	}
	result, err := c.run(ctx, []string{"-s", serial, "exec-out", "screencap", "-p"}, process.Options{
		Timeout:   20 * time.Second,
		MaxOutput: maxScreenshotOutput,
	})
	if err != nil {
		return Screenshot{}, err
	}
	if result.OutputTruncated || !validPNGStructure(result.Stdout) {
		return Screenshot{}, apperr.New(
			apperr.CodeActionFailed,
			"ADB returned an invalid or truncated PNG screenshot.",
			true,
			map[string]any{"device": serial},
		)
	}
	sum := sha256.Sum256(result.Stdout)
	return Screenshot{Bytes: result.Stdout, SHA256: hex.EncodeToString(sum[:])}, nil
}

// Hierarchy 使用固定远端路径读取 UIAutomator 层级，并始终尝试清理临时文件。
func (c *Client) Hierarchy(ctx context.Context, serial string) (hierarchy Hierarchy, returnErr error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return Hierarchy{}, err
	}
	_, err := c.run(ctx, []string{
		"-s", serial, "shell", "uiautomator", "dump", hierarchyRemotePath,
	}, process.Options{Timeout: 20 * time.Second})
	if err != nil {
		return Hierarchy{}, err
	}
	defer func() {
		// 路径和命令均为固定常量，调用方无法向远端 Shell 注入输入。
		_, _ = c.run(context.WithoutCancel(ctx), []string{
			"-s", serial, "shell", "rm", "-f", hierarchyRemotePath,
		}, process.Options{Timeout: 5 * time.Second})
	}()

	result, err := c.run(ctx, []string{
		"-s", serial, "exec-out", "cat", hierarchyRemotePath,
	}, process.Options{Timeout: 10 * time.Second, MaxOutput: maxHierarchyOutput})
	if err != nil {
		return Hierarchy{}, err
	}
	if result.OutputTruncated || !validHierarchyXML(result.Stdout) {
		return Hierarchy{}, apperr.New(
			apperr.CodeActionFailed,
			"ADB returned an invalid or truncated UI hierarchy.",
			true,
			map[string]any{"device": serial},
		)
	}
	sum := sha256.Sum256(result.Stdout)
	return Hierarchy{
		XML:    string(result.Stdout),
		Bytes:  len(result.Stdout),
		SHA256: hex.EncodeToString(sum[:]),
	}, nil
}

// Tap 在参数校验后向明确设备发送一次坐标点击。
func (c *Client) Tap(ctx context.Context, serial string, x, y int) (ActionResult, error) {
	if err := validatePoint(x, y); err != nil {
		return ActionResult{}, err
	}
	return c.inputAction(ctx, serial, "ui.tap", "tap", strconv.Itoa(x), strconv.Itoa(y))
}

// Swipe 在坐标和时长预算内向明确设备发送一次滑动。
func (c *Client) Swipe(
	ctx context.Context,
	serial string,
	startX, startY, endX, endY, durationMS int,
) (ActionResult, error) {
	if err := validatePoint(startX, startY); err != nil {
		return ActionResult{}, err
	}
	if err := validatePoint(endX, endY); err != nil {
		return ActionResult{}, err
	}
	if durationMS < 1 || durationMS > maxSwipeDurationMS {
		return ActionResult{}, invalidField("duration", fmt.Sprintf("must be between 1 and %d", maxSwipeDurationMS))
	}
	return c.inputAction(
		ctx,
		serial,
		"ui.swipe",
		"swipe",
		strconv.Itoa(startX),
		strconv.Itoa(startY),
		strconv.Itoa(endX),
		strconv.Itoa(endY),
		strconv.Itoa(durationMS),
	)
}

// Text 只接受受限 ASCII 文本并按 ADB input 规则编码空格。
func (c *Client) Text(ctx context.Context, serial, text string) (ActionResult, error) {
	if len(text) > maxTextLength {
		return ActionResult{}, invalidField("text", fmt.Sprintf("must be at most %d bytes", maxTextLength))
	}
	if !directText.MatchString(text) {
		return ActionResult{}, invalidField(
			"text",
			"contains unsupported characters; direct ADB text accepts ASCII letters, numbers, spaces, and @._+-,:/",
		)
	}
	encoded := strings.ReplaceAll(text, " ", "%s")
	return c.inputAction(ctx, serial, "ui.setText", "text", encoded)
}

// Key 只执行预定义键值白名单中的全局或导航按键。
func (c *Client) Key(ctx context.Context, serial, key string) (ActionResult, error) {
	keyCode, ok := allowedKeys[strings.ToUpper(key)]
	if !ok {
		return ActionResult{}, invalidField("key", "is not in the supported key allowlist")
	}
	return c.inputAction(ctx, serial, "ui.pressKey", "keyevent", keyCode)
}

// Launch 启动已校验包的 Launcher 入口或同包内明确 Activity。
func (c *Client) Launch(ctx context.Context, serial, packageName, activity string) (ActionResult, error) {
	if err := ValidatePackageName(packageName); err != nil {
		return ActionResult{}, err
	}
	component := ""
	if activity != "" {
		var err error
		component, err = validateActivity(packageName, activity)
		if err != nil {
			return ActionResult{}, err
		}
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	var args []string
	if activity == "" {
		args = []string{
			"-s", serial, "shell", "monkey",
			"-p", packageName, "-c", "android.intent.category.LAUNCHER", "1",
		}
	} else {
		args = []string{"-s", serial, "shell", "am", "start", "-W", "-n", component}
	}
	if err := c.runAction(ctx, serial, args, process.Options{Timeout: 20 * time.Second}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "app.launch"}, nil
}

// Stop 对已校验包执行 force-stop，不接受任意 Shell 片段。
func (c *Client) Stop(ctx context.Context, serial, packageName string) (ActionResult, error) {
	if err := ValidatePackageName(packageName); err != nil {
		return ActionResult{}, err
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	if err := c.runAction(ctx, serial, []string{
		"-s", serial, "shell", "am", "force-stop", packageName,
	}, process.Options{}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "app.stop"}, nil
}

func (c *Client) inputAction(
	ctx context.Context,
	serial string,
	action string,
	inputArgs ...string,
) (ActionResult, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	args := append([]string{"-s", serial, "shell", "input"}, inputArgs...)
	if err := c.runAction(ctx, serial, args, process.Options{}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: action}, nil
}

func (c *Client) runAction(
	ctx context.Context,
	serial string,
	args []string,
	options process.Options,
) error {
	result, err := c.run(ctx, args, options)
	if err == nil {
		return nil
	}
	if result.ExitCode <= 0 {
		return err
	}
	return apperr.New(
		apperr.CodeActionFailed,
		"ADB action command failed.",
		false,
		map[string]any{"device": serial, "exitCode": result.ExitCode},
	)
}

func (c *Client) requireOnlineDevice(ctx context.Context, serial string) error {
	if err := ValidateSerial(serial); err != nil {
		return err
	}
	devices, err := c.Devices(ctx)
	if err != nil {
		return err
	}
	device, err := selectDevice(devices, serial)
	if err != nil {
		return err
	}
	return ensureOnline(device)
}

// ValidatePackageName 拒绝不符合 Android 包名语法或长度预算的输入。
func ValidatePackageName(packageName string) error {
	if len(packageName) > 255 || !packagePattern.MatchString(packageName) {
		return invalidField("package", "has an invalid Android package name")
	}
	return nil
}

func validateActivity(packageName, activity string) (string, error) {
	if len(activity) > 512 {
		return "", invalidField("activity", "must be at most 512 bytes")
	}

	componentPackage, className, hasPackage := strings.Cut(activity, "/")
	if hasPackage {
		if componentPackage != packageName {
			return "", invalidField("activity", "must belong to the requested package")
		}
	} else {
		componentPackage = packageName
		className = activity
	}

	validClass := false
	if strings.HasPrefix(className, ".") {
		validClass = classPattern.MatchString(strings.TrimPrefix(className, "."))
	} else {
		validClass = strings.Contains(className, ".") && classPattern.MatchString(className)
	}
	if !validClass {
		return "", invalidField("activity", "has an invalid Android component format")
	}
	return componentPackage + "/" + className, nil
}

func validatePoint(x, y int) error {
	if x < 0 || x > maxCoordinate {
		return invalidField("x", fmt.Sprintf("must be between 0 and %d", maxCoordinate))
	}
	if y < 0 || y > maxCoordinate {
		return invalidField("y", fmt.Sprintf("must be between 0 and %d", maxCoordinate))
	}
	return nil
}

func validPNGStructure(data []byte) bool {
	if len(data) < len(pngSignature) || !bytes.Equal(data[:len(pngSignature)], pngSignature) {
		return false
	}
	if _, err := png.DecodeConfig(bytes.NewReader(data)); err != nil {
		return false
	}

	offset := len(pngSignature)
	sawIHDR := false
	sawPLTE := false
	sawIDAT := false
	idatClosed := false
	for {
		if len(data)-offset < 12 {
			return false
		}
		length := binary.BigEndian.Uint32(data[offset : offset+4])
		chunkEnd64 := uint64(offset) + 12 + uint64(length)
		if chunkEnd64 > uint64(len(data)) {
			return false
		}
		chunkEnd := int(chunkEnd64)
		dataEnd := chunkEnd - 4
		chunkType := data[offset+4 : offset+8]
		if !validPNGChunkType(chunkType) ||
			binary.BigEndian.Uint32(data[dataEnd:chunkEnd]) != crc32.ChecksumIEEE(data[offset+4:dataEnd]) {
			return false
		}

		switch string(chunkType) {
		case "IHDR":
			if sawIHDR || offset != len(pngSignature) || length != 13 {
				return false
			}
			sawIHDR = true
		case "PLTE":
			if !sawIHDR || sawPLTE || sawIDAT {
				return false
			}
			sawPLTE = true
		case "IDAT":
			if !sawIHDR || idatClosed {
				return false
			}
			sawIDAT = true
		case "IEND":
			return sawIHDR && sawIDAT && length == 0 && chunkEnd == len(data)
		default:
			if !sawIHDR || chunkType[0] >= 'A' && chunkType[0] <= 'Z' {
				return false
			}
		}
		if sawIDAT && string(chunkType) != "IDAT" {
			idatClosed = true
		}
		offset = chunkEnd
	}
}

func validPNGChunkType(chunkType []byte) bool {
	for index, character := range chunkType {
		if character < 'A' || character > 'Z' && character < 'a' || character > 'z' {
			return false
		}
		if index == 2 && character >= 'a' && character <= 'z' {
			return false
		}
	}
	return true
}

func validHierarchyXML(data []byte) bool {
	decoder := xml.NewDecoder(bytes.NewReader(data))
	foundRoot := false
	depth := 0
	for {
		token, err := decoder.Token()
		if err != nil {
			if err == io.EOF {
				return foundRoot && depth == 0
			}
			return false
		}
		switch value := token.(type) {
		case xml.StartElement:
			if depth == 0 {
				if foundRoot {
					return false
				}
				start := value
				if start.Name.Local != "hierarchy" {
					return false
				}
				foundRoot = true
			}
			depth++
		case xml.EndElement:
			if depth == 0 {
				return false
			}
			depth--
		case xml.CharData:
			if depth == 0 && len(bytes.TrimSpace(value)) != 0 {
				return false
			}
		}
	}
}

func invalidField(field, reason string) error {
	return apperr.New(
		apperr.CodeInvalidArgument,
		fmt.Sprintf("%s %s.", field, reason),
		false,
		map[string]any{"field": field},
	)
}
