package adb

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"fmt"
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
	pngSignature    = []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}
	packagePattern  = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$`)
	activityPattern = regexp.MustCompile(`^(?:\.[A-Za-z][A-Za-z0-9_.$]*|[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+(?:/[A-Za-z0-9_.$]+)?)$`)
	directText      = regexp.MustCompile(`^[A-Za-z0-9 @._+\-,:/]*$`)
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

type Screenshot struct {
	Bytes  []byte
	SHA256 string
}

type Hierarchy struct {
	XML    string `json:"xml"`
	Bytes  int    `json:"sizeBytes"`
	SHA256 string `json:"sha256"`
}

type ActionResult struct {
	Device string `json:"device"`
	Action string `json:"action"`
}

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
	if result.OutputTruncated || !bytes.HasPrefix(result.Stdout, pngSignature) {
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
		// The path and command are fixed constants; callers cannot inject remote shell input.
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

func (c *Client) Tap(ctx context.Context, serial string, x, y int) (ActionResult, error) {
	if err := validatePoint(x, y); err != nil {
		return ActionResult{}, err
	}
	return c.inputAction(ctx, serial, "ui.tap", "tap", strconv.Itoa(x), strconv.Itoa(y))
}

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

func (c *Client) Key(ctx context.Context, serial, key string) (ActionResult, error) {
	keyCode, ok := allowedKeys[strings.ToUpper(key)]
	if !ok {
		return ActionResult{}, invalidField("key", "is not in the supported key allowlist")
	}
	return c.inputAction(ctx, serial, "ui.pressKey", "keyevent", keyCode)
}

func (c *Client) Launch(ctx context.Context, serial, packageName, activity string) (ActionResult, error) {
	if err := ValidatePackageName(packageName); err != nil {
		return ActionResult{}, err
	}
	if activity != "" {
		if !activityPattern.MatchString(activity) {
			return ActionResult{}, invalidField("activity", "has an invalid Android component format")
		}
		if strings.Contains(activity, "/") && !strings.HasPrefix(activity, packageName+"/") {
			return ActionResult{}, invalidField("activity", "must belong to the requested package")
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
		component := activity
		if !strings.Contains(activity, "/") {
			component = packageName + "/" + activity
		}
		args = []string{"-s", serial, "shell", "am", "start", "-W", "-n", component}
	}
	if _, err := c.run(ctx, args, process.Options{Timeout: 20 * time.Second}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "app.launch"}, nil
}

func (c *Client) Stop(ctx context.Context, serial, packageName string) (ActionResult, error) {
	if err := ValidatePackageName(packageName); err != nil {
		return ActionResult{}, err
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	if _, err := c.run(ctx, []string{
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
	if _, err := c.run(ctx, args, process.Options{}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: action}, nil
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

func ValidatePackageName(packageName string) error {
	if len(packageName) > 255 || !packagePattern.MatchString(packageName) {
		return invalidField("package", "has an invalid Android package name")
	}
	return nil
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

func validHierarchyXML(data []byte) bool {
	decoder := xml.NewDecoder(bytes.NewReader(data))
	foundRoot := false
	for {
		token, err := decoder.Token()
		if err != nil {
			if err == io.EOF {
				return foundRoot
			}
			return false
		}
		if start, ok := token.(xml.StartElement); ok {
			if !foundRoot {
				if start.Name.Local != "hierarchy" {
					return false
				}
				foundRoot = true
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
