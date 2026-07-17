package cli

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

type optionSpec struct {
	allowed  map[string]bool
	required []string
}

func (a *App) executeObserve(ctx context.Context, client *adb.Client, args []string) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl observe screenshot|hierarchy [options]")
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
		screenshot, err := client.Screenshot(ctx, options["device"])
		if err != nil {
			return nil, err
		}
		path, err := writeScreenshot(options["output"], screenshot.Bytes)
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"device":    options["device"],
			"path":      path,
			"format":    "png",
			"sizeBytes": len(screenshot.Bytes),
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
		hierarchy, err := client.Hierarchy(ctx, options["device"])
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"device":    options["device"],
			"format":    "uiautomator-xml",
			"xml":       hierarchy.XML,
			"sizeBytes": hierarchy.Bytes,
			"sha256":    hierarchy.SHA256,
		}, nil
	default:
		return nil, usageError("Unknown observe command. Supported commands: screenshot, hierarchy.")
	}
}

func (a *App) executeAction(ctx context.Context, client *adb.Client, args []string) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl action tap|swipe|text|key|launch|stop [options]")
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
		return client.Tap(ctx, options["device"], x, y)
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
		return client.Swipe(
			ctx,
			options["device"],
			values["x1"],
			values["y1"],
			values["x2"],
			values["y2"],
			duration,
		)
	case "text":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "text"),
			required: []string{"device", "text"},
		})
		if err != nil {
			return nil, err
		}
		return client.Text(ctx, options["device"], options["text"])
	case "key":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "key"),
			required: []string{"device", "key"},
		})
		if err != nil {
			return nil, err
		}
		return client.Key(ctx, options["device"], options["key"])
	case "launch":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "package", "activity"),
			required: []string{"device", "package"},
		})
		if err != nil {
			return nil, err
		}
		return client.Launch(ctx, options["device"], options["package"], options["activity"])
	case "stop":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "package"),
			required: []string{"device", "package"},
		})
		if err != nil {
			return nil, err
		}
		return client.Stop(ctx, options["device"], options["package"])
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
