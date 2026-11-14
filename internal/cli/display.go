package cli

// 功能用途：解析仅电脑侧的显示管理命令，拒绝不支持的选项及主屏回退。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/service"
)

func parseDisplayOption(options map[string]string) (*int, error) {
	if _, ok := options["display"]; !ok {
		return nil, nil
	}
	id, err := parseIntegerOption(options, "display")
	if err != nil {
		return nil, err
	}
	if id < 0 || id > 2147483647 {
		return nil, usageError("display must be a non-negative display ID; 0 explicitly selects the primary display.")
	}
	return &id, nil
}

func (a *App) executeDisplay(ctx context.Context, automation service.Automation, args []string) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl display start|status|stop|create|list|close --device SERIAL [options]")
	}
	allowed := optionSet("device")
	required := []string{"device"}
	switch args[0] {
	case "start":
		allowed["helper"] = true
	case "status", "stop", "list":
	case "create":
		for _, key := range []string{"width", "height", "dpi"} {
			allowed[key] = true
			required = append(required, key)
		}
	case "close":
		allowed["display"] = true
		required = append(required, "display")
	default:
		return nil, usageError("Unknown display command.")
	}
	options, err := parseNamedOptions(args[1:], optionSpec{allowed: allowed, required: required})
	if err != nil {
		return nil, err
	}
	r := service.DisplayRequest{Command: args[0], Device: options["device"], Helper: options["helper"]}
	if r.Command == "create" {
		for name, target := range map[string]*int{"width": &r.Width, "height": &r.Height, "dpi": &r.DPI} {
			value, err := parseIntegerOption(options, name)
			if err != nil {
				return nil, err
			}
			*target = value
		}
		if r.Width < 128 || r.Width > 2048 || r.Height < 128 || r.Height > 2048 || r.DPI < 72 || r.DPI > 640 {
			return nil, usageError("width/height must be 128..2048; dpi must be 72..640.")
		}
	}
	if r.Command == "close" {
		id, err := parseDisplayOption(options)
		if err != nil {
			return nil, err
		}
		r.DisplayID = *id
		if r.DisplayID == 0 {
			return nil, usageError("Only a positive helper-owned display ID can be closed.")
		}
	}
	display, ok := automation.(service.DisplayAutomation)
	if !ok {
		return nil, usageError("Virtual display management is unavailable in this service.")
	}
	return display.ManageDisplay(ctx, r)
}
