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
			"Usage: aactl observe screenshot|hierarchy|foreground|apps|recording|" +
				"screen-state|input-methods [options]")
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
			"Unknown observe command. Supported commands: screenshot, hierarchy, " +
				"foreground, apps, recording, screen-state, input-methods.")
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

	// screen-state 与 input-methods 的服务层实现早已存在（见
	// internal/service/device_state.go），但此前没有任何通道引用它们。
	// 这两个观察不走 ExecuteAction：返回值不是 adb.ActionResult，
	// 硬塞进那个签名会让调用方拿不到亮屏状态与输入法列表本身。
	case "screen-state":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		state, observeErr := automation.ScreenState(ctx, options["device"])
		if observeErr != nil {
			return nil, true, observeErr
		}
		return state, true, nil

	case "input-methods":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		methods, observeErr := automation.InputMethods(ctx, options["device"])
		if observeErr != nil {
			return nil, true, observeErr
		}
		return methods, true, nil
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
		return nil, usageError(
			"Usage: aactl action tap|swipe|long-press|scroll|text|key|launch|stop|" +
				"drag|multi-tap|path-gesture|rotate|wake|unlock|ime-hide|ime-select|" +
				"deeplink [options]")
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
		// 动作面扩展子命令单独成函数：它们各有自己的选项集，
		// 挤进本 switch 会让参数校验难以逐项核对。
		if result, handled, extraErr := a.executeActionExtra(ctx, automation, args); handled {
			return result, extraErr
		}
		return nil, usageError(
			"Unknown action. Supported actions: tap, swipe, long-press, scroll, text, key, " +
				"launch, stop, drag, multi-tap, path-gesture, rotate, wake, unlock, " +
				"ime-hide, ime-select, deeplink.")
	}
}

// executeActionExtra 处理动作面扩展的九个子命令。
//
// 这些动作在 internal/service 早已分派，此前只缺 CLI 出口。返回 handled=false
// 表示不是这九个，交回原分派继续处理，避免两处都要维护子命令名单。
//
// 刻意不包含 file.push / file.pull：暴露远端路径读写前需要先定义路径白名单与
// 确认策略，属于独立的安全决策（见 change spec 的待决项 T4）。
func (a *App) executeActionExtra(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (result any, handled bool, err error) {
	switch args[0] {
	case "drag":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "x1", "y1", "x2", "y2", "duration"),
			required: []string{"device", "x1", "y1", "x2", "y2"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		values := make(map[string]int, 4)
		for _, name := range []string{"x1", "y1", "x2", "y2"} {
			value, intErr := parseIntegerOption(options, name)
			if intErr != nil {
				return nil, true, intErr
			}
			values[name] = value
		}
		request := service.ActionRequest{
			Device: options["device"],
			Action: service.ActionDrag,
			StartX: values["x1"],
			StartY: values["y1"],
			EndX:   values["x2"],
			EndY:   values["y2"],
		}
		if _, present := options["duration"]; present {
			duration, intErr := parseIntegerOption(options, "duration")
			if intErr != nil {
				return nil, true, intErr
			}
			request.DurationMS = duration
		}
		actionResult, actionErr := automation.ExecuteAction(ctx, request)
		return actionResult, true, actionErr

	case "multi-tap":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "x", "y", "taps"),
			required: []string{"device", "x", "y"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		x, intErr := parseIntegerOption(options, "x")
		if intErr != nil {
			return nil, true, intErr
		}
		y, intErr := parseIntegerOption(options, "y")
		if intErr != nil {
			return nil, true, intErr
		}
		request := service.ActionRequest{
			Device: options["device"],
			Action: service.ActionMultiTap,
			X:      x,
			Y:      y,
		}
		if _, present := options["taps"]; present {
			taps, tapErr := parseIntegerOption(options, "taps")
			if tapErr != nil {
				return nil, true, tapErr
			}
			request.Taps = taps
		}
		actionResult, actionErr := automation.ExecuteAction(ctx, request)
		return actionResult, true, actionErr

	case "path-gesture":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "points", "duration"),
			required: []string{"device", "points"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		points, pointsErr := parsePointList(options["points"])
		if pointsErr != nil {
			return nil, true, pointsErr
		}
		request := service.ActionRequest{
			Device: options["device"],
			Action: service.ActionPathGesture,
			Points: points,
		}
		if _, present := options["duration"]; present {
			duration, intErr := parseIntegerOption(options, "duration")
			if intErr != nil {
				return nil, true, intErr
			}
			request.DurationMS = duration
		}
		actionResult, actionErr := automation.ExecuteAction(ctx, request)
		return actionResult, true, actionErr

	case "rotate":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "orientation"),
			required: []string{"device", "orientation"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		orientation := options["orientation"]
		// 在这里就校验取值而不是交给服务层：错误的方向名落到 ADB 会变成一条
		// 静默无效的 settings 写入，调用方看不出哪一步出了问题。
		switch orientation {
		case "portrait", "landscape", "auto":
		default:
			return nil, true, apperr.New(
				apperr.CodeInvalidArgument,
				"orientation must be portrait, landscape, or auto.",
				false,
				map[string]any{"field": "orientation"},
			)
		}
		actionResult, actionErr := automation.ExecuteAction(ctx, service.ActionRequest{
			Device:      options["device"],
			Action:      service.ActionDeviceRotate,
			Orientation: orientation,
		})
		return actionResult, true, actionErr

	case "wake", "unlock", "ime-hide":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		action := map[string]string{
			"wake":     service.ActionDeviceWake,
			"unlock":   service.ActionDeviceUnlock,
			"ime-hide": service.ActionImeHide,
		}[args[0]]
		actionResult, actionErr := automation.ExecuteAction(ctx, service.ActionRequest{
			Device: options["device"],
			Action: action,
		})
		return actionResult, true, actionErr

	case "ime-select":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "ime"),
			required: []string{"device", "ime"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		actionResult, actionErr := automation.ExecuteAction(ctx, service.ActionRequest{
			Device:      options["device"],
			Action:      service.ActionImeSelect,
			InputMethod: options["ime"],
		})
		return actionResult, true, actionErr

	case "deeplink":
		options, parseErr := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "uri", "package"),
			required: []string{"device", "uri"},
		})
		if parseErr != nil {
			return nil, true, parseErr
		}
		targetPackage := options["package"]
		if targetPackage != "" {
			if validationErr := adb.ValidatePackageName(targetPackage); validationErr != nil {
				return nil, true, validationErr
			}
		}
		actionResult, actionErr := automation.ExecuteAction(ctx, service.ActionRequest{
			Device:  options["device"],
			Action:  service.ActionDeeplink,
			URI:     options["uri"],
			Package: targetPackage,
		})
		return actionResult, true, actionErr
	}
	return nil, false, nil
}

// parsePointList 解析 `x,y x,y ...` 形式的折线点列。
//
// 用空格分隔点、逗号分隔坐标，而不是让调用方重复传 --point：
// parseNamedOptions 对同名选项会报「provided more than once」，
// 改造它会影响所有其它命令的既有约定。
//
// 至少两个点：单点不构成手势。服务层同样会拒绝，但在这里失败能给出准确字段名。
func parsePointList(raw string) ([]adb.Point, error) {
	fields := strings.Fields(raw)
	if len(fields) < 2 {
		return nil, apperr.New(
			apperr.CodeInvalidArgument,
			"points must contain at least two `x,y` pairs separated by spaces.",
			false,
			map[string]any{"field": "points"},
		)
	}
	points := make([]adb.Point, 0, len(fields))
	for _, field := range fields {
		rawX, rawY, found := strings.Cut(field, ",")
		if !found {
			return nil, apperr.New(
				apperr.CodeInvalidArgument,
				"points must use the `x,y` form for every pair.",
				false,
				map[string]any{"field": "points"},
			)
		}
		x, convErr := strconv.Atoi(strings.TrimSpace(rawX))
		if convErr != nil {
			return nil, apperr.New(
				apperr.CodeInvalidArgument,
				"points must contain integer coordinates.",
				false,
				map[string]any{"field": "points"},
			)
		}
		y, convErr := strconv.Atoi(strings.TrimSpace(rawY))
		if convErr != nil {
			return nil, apperr.New(
				apperr.CodeInvalidArgument,
				"points must contain integer coordinates.",
				false,
				map[string]any{"field": "points"},
			)
		}
		points = append(points, adb.Point{X: x, Y: y})
	}
	return points, nil
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
