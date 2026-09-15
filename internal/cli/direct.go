package cli

// 功能用途：本文件解析直接观察与动作参数，并安全写入截图产物。

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
)

type optionSpec struct {
	allowed  map[string]bool
	required []string
}

func (a *App) executeObserve(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError(
			"Usage: aactl observe screenshot|hierarchy|foreground|apps|recording [options]")
	}
	// 新增的三个观察子命令先行处理；未命中时落回下面的既有分支。
	if result, handled, err := a.executeObserveExtra(ctx, automation, args); handled {
		return result, err
	}
	switch args[0] {
	case "screenshot":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  map[string]bool{"device": true, "output": true},
			required: []string{"device", "output"},
		})
		if err != nil {
			return nil, err
		}
		screenshot, err := automation.Observe(ctx, service.ObserveRequest{
			Device: options["device"],
			Kind:   service.ObserveScreenshot,
		})
		if err != nil {
			return nil, err
		}
		path, err := writeScreenshot(options["output"], screenshot.Image)
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"device":    options["device"],
			"path":      path,
			"format":    "png",
			"sizeBytes": screenshot.SizeBytes,
			"sha256":    screenshot.SHA256,
		}, nil
	case "hierarchy":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  map[string]bool{"device": true},
			required: []string{"device"},
		})
		if err != nil {
			return nil, err
		}
		hierarchy, err := automation.Observe(ctx, service.ObserveRequest{
			Device: options["device"],
			Kind:   service.ObserveHierarchy,
		})
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"device":    options["device"],
			"format":    "uiautomator-xml",
			"xml":       hierarchy.XML,
			"sizeBytes": hierarchy.SizeBytes,
			"sha256":    hierarchy.SHA256,
		}, nil
	default:
		return nil, usageError(
			"Unknown observe command. Supported commands: screenshot, hierarchy, foreground, apps, recording.")
	}
}

// executeObserveExtra 处理前台应用、应用清单与录屏三个观察子命令。
//
// 单独成函数而不并入 executeObserve 的 switch：这三者都需要各自的选项集，
// 挤在一个函数里会让参数校验难以逐项核对。返回 handled=false 表示不是这三者，
// 交回原分派继续处理，避免两处都要维护子命令名单。
func (a *App) executeObserveExtra(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (result any, handled bool, err error) {
	switch args[0] {
	case "foreground":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		foreground, observeErr := automation.Foreground(ctx, service.ForegroundRequest{
			Device: options["device"],
		})
		if observeErr != nil {
			return nil, true, observeErr
		}
		return foreground, true, nil

	case "apps":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "include-system", "with-details"),
			required: []string{"device"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		includeSystem, flagErr := parseBooleanOption(options, "include-system")
		if flagErr != nil {
			return nil, true, flagErr
		}
		withDetails, flagErr := parseBooleanOption(options, "with-details")
		if flagErr != nil {
			return nil, true, flagErr
		}
		apps, observeErr := automation.Apps(ctx, service.AppsRequest{
			Device:        options["device"],
			IncludeSystem: includeSystem,
			WithDetails:   withDetails,
		})
		if observeErr != nil {
			return nil, true, observeErr
		}
		return apps, true, nil

	case "recording":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed: optionSet(
				"device", "output", "seconds", "width", "height", "bit-rate",
			),
			required: []string{"device", "output"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		request := service.RecordingRequest{Device: options["device"]}
		for name, target := range map[string]*int{
			"seconds":  &request.Seconds,
			"width":    &request.Width,
			"height":   &request.Height,
			"bit-rate": &request.BitRate,
		} {
			if _, present := options[name]; !present {
				continue
			}
			value, intErr := parseIntegerOption(options, name)
			if intErr != nil {
				return nil, true, intErr
			}
			*target = value
		}
		recording, observeErr := automation.Record(ctx, request)
		if observeErr != nil {
			return nil, true, observeErr
		}
		// 复用截图的写文件逻辑：同样要拒绝空字节、目录路径并使用 0600 权限。
		path, writeErr := writeScreenshot(options["output"], recording.Video)
		if writeErr != nil {
			return nil, true, writeErr
		}
		return map[string]any{
			"device":          recording.Device,
			"path":            path,
			"format":          recording.Format,
			"sizeBytes":       recording.SizeBytes,
			"sha256":          recording.SHA256,
			"durationSeconds": recording.DurationSec,
			"width":           recording.Width,
			"height":          recording.Height,
			"bitRate":         recording.BitRate,
		}, true, nil
	}
	return nil, false, nil
}

// parseBooleanOption 把可选开关解析为布尔值。
//
// 必须显式写成 --with-details=true 或 --with-details true：
// 既有的 parseNamedOptions 对所有选项一律要求非空值（见其 "requires a value" 分支），
// 因此这里不支持裸开关写法，也不去改动那条既有约定——它同时约束了所有其他命令。
func parseBooleanOption(options map[string]string, name string) (bool, error) {
	value, present := options[name]
	if !present {
		return false, nil
	}
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "true", "1", "yes":
		return true, nil
	case "false", "0", "no":
		return false, nil
	default:
		return false, apperr.New(
			apperr.CodeInvalidArgument,
			fmt.Sprintf("%s must be true or false.", name),
			false,
			map[string]any{"field": name},
		)
	}
}

func (a *App) executeAction(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl action tap|swipe|long-press|scroll|text|key|launch|stop [options]")
	}
	switch args[0] {
	case "tap":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "x", "y"),
			required: []string{"device", "x", "y"},
		})
		if err != nil {
			return nil, err
		}
		x, err := parseIntegerOption(options, "x")
		if err != nil {
			return nil, err
		}
		y, err := parseIntegerOption(options, "y")
		if err != nil {
			return nil, err
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device: options["device"],
			Action: service.ActionTap,
			X:      x,
			Y:      y,
		})
	case "swipe":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "x1", "y1", "x2", "y2", "duration"),
			required: []string{"device", "x1", "y1", "x2", "y2"},
		})
		if err != nil {
			return nil, err
		}
		values := make(map[string]int, 5)
		for _, name := range []string{"x1", "y1", "x2", "y2"} {
			value, parseErr := parseIntegerOption(options, name)
			if parseErr != nil {
				return nil, parseErr
			}
			values[name] = value
		}
		duration := 300
		if _, exists := options["duration"]; exists {
			duration, err = parseIntegerOption(options, "duration")
			if err != nil {
				return nil, err
			}
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device:     options["device"],
			Action:     service.ActionSwipe,
			StartX:     values["x1"],
			StartY:     values["y1"],
			EndX:       values["x2"],
			EndY:       values["y2"],
			DurationMS: duration,
		})
	case "long-press":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "x", "y", "duration"),
			required: []string{"device", "x", "y"},
		})
		if err != nil {
			return nil, err
		}
		x, err := parseIntegerOption(options, "x")
		if err != nil {
			return nil, err
		}
		y, err := parseIntegerOption(options, "y")
		if err != nil {
			return nil, err
		}
		duration := 0
		if _, exists := options["duration"]; exists {
			duration, err = parseIntegerOption(options, "duration")
			if err != nil {
				return nil, err
			}
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device:     options["device"],
			Action:     service.ActionLongClick,
			X:          x,
			Y:          y,
			DurationMS: duration,
		})
	case "scroll":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "direction", "amount"),
			required: []string{"device", "direction"},
		})
		if err != nil {
			return nil, err
		}
		amount := 0.0
		if raw, exists := options["amount"]; exists {
			parsed, parseErr := strconv.ParseFloat(raw, 64)
			if parseErr != nil {
				return nil, apperr.New(
					apperr.CodeInvalidArgument,
					"amount must be a fraction of the screen between 0 and 1.",
					false,
					map[string]any{"field": "amount"},
				)
			}
			amount = parsed
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device:    options["device"],
			Action:    service.ActionScroll,
			Direction: options["direction"],
			Amount:    amount,
		})
	case "text":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "text"),
			required: []string{"device", "text"},
		})
		if err != nil {
			return nil, err
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device: options["device"],
			Action: service.ActionSetText,
			Text:   options["text"],
		})
	case "key":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "key"),
			required: []string{"device", "key"},
		})
		if err != nil {
			return nil, err
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device: options["device"],
			Action: service.ActionPressKey,
			Key:    options["key"],
		})
	case "launch":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "package", "activity"),
			required: []string{"device", "package"},
		})
		if err != nil {
			return nil, err
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device:   options["device"],
			Action:   service.ActionLaunch,
			Package:  options["package"],
			Activity: options["activity"],
		})
	case "stop":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "package"),
			required: []string{"device", "package"},
		})
		if err != nil {
			return nil, err
		}
		return automation.ExecuteAction(ctx, service.ActionRequest{
			Device:  options["device"],
			Action:  service.ActionStop,
			Package: options["package"],
		})
	default:
		return nil, usageError("Unknown action. Supported actions: tap, swipe, text, key, launch, stop.")
	}
}

func parseNamedOptions(args []string, spec optionSpec) (map[string]string, error) {
	options := make(map[string]string, len(args)/2)
	for index := 0; index < len(args); index++ {
		argument := args[index]
		if !strings.HasPrefix(argument, "--") || argument == "--" {
			return nil, usageError(fmt.Sprintf("Unexpected positional argument %q.", argument))
		}
		nameValue := strings.TrimPrefix(argument, "--")
		name, value, inline := strings.Cut(nameValue, "=")
		if !spec.allowed[name] {
			return nil, usageError(fmt.Sprintf("Unknown option --%s.", name))
		}
		if _, exists := options[name]; exists {
			return nil, usageError(fmt.Sprintf("Option --%s was provided more than once.", name))
		}
		if !inline {
			index++
			if index >= len(args) {
				return nil, usageError(fmt.Sprintf("Option --%s requires a value.", name))
			}
			value = args[index]
		}
		if value == "" {
			return nil, usageError(fmt.Sprintf("Option --%s requires a non-empty value.", name))
		}
		options[name] = value
	}
	for _, name := range spec.required {
		if _, exists := options[name]; !exists {
			return nil, usageError(fmt.Sprintf("Required option --%s is missing.", name))
		}
	}
	if device, exists := options["device"]; exists {
		if err := adb.ValidateSerial(device); err != nil {
			return nil, err
		}
	}
	return options, nil
}

func optionSet(names ...string) map[string]bool {
	options := make(map[string]bool, len(names))
	for _, name := range names {
		options[name] = true
	}
	return options
}

func parseIntegerOption(options map[string]string, name string) (int, error) {
	value, err := strconv.Atoi(options[name])
	if err != nil {
		return 0, apperr.New(
			apperr.CodeInvalidArgument,
			fmt.Sprintf("%s must be an integer.", name),
			false,
			map[string]any{"field": name},
		)
	}
	return value, nil
}

func writeScreenshot(path string, data []byte) (string, error) {
	if strings.ContainsRune(path, '\x00') {
		return "", usageError("Output path contains a null byte.")
	}
	absolutePath, err := filepath.Abs(path)
	if err != nil {
		return "", apperr.Wrap(apperr.CodeInvalidArgument, "Output path could not be resolved.", false, err)
	}
	info, statErr := os.Stat(absolutePath)
	if statErr == nil && info.IsDir() {
		return "", usageError("Output path refers to a directory.")
	}
	if statErr != nil && !os.IsNotExist(statErr) {
		return "", apperr.Wrap(apperr.CodeInvalidArgument, "Output path could not be inspected.", false, statErr)
	}
	if err := os.WriteFile(absolutePath, data, 0o600); err != nil {
		return "", apperr.Wrap(apperr.CodeInvalidArgument, "Screenshot could not be written.", false, err)
	}
	return absolutePath, nil
}
