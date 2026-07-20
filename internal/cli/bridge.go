package cli

// 本文件解析 Bridge 会话、语义观察和动作命令，并在 RPC 前完成参数校验。

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/service"
)

func (a *App) executeBridge(
	ctx context.Context,
	client *adb.Client,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError(
			"Usage: aactl bridge open|info|snapshot|action|close [options]",
		)
	}
	service, err := a.bridgeService(client)
	if err != nil {
		return nil, err
	}
	switch args[0] {
	case "open":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "code"),
			required: []string{"device"},
		})
		if err != nil {
			return nil, err
		}
		code := options["code"]
		if code == "" {
			_, _ = fmt.Fprint(a.Stderr, "Desktop bridge code: ")
			code, err = readPairingCode(a.Stdin)
			if err != nil {
				return nil, err
			}
		}
		if err := adb.ValidatePairingCode(code); err != nil {
			return nil, err
		}
		return service.Open(ctx, options["device"], code)
	case "info":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if err != nil {
			return nil, err
		}
		return service.Info(ctx, options["device"])
	case "snapshot":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "package", "max-depth"),
			required: []string{"device"},
		})
		if err != nil {
			return nil, err
		}
		targetPackage := options["package"]
		if targetPackage != "" {
			if err := adb.ValidatePackageName(targetPackage); err != nil {
				return nil, err
			}
		}
		maxDepth := 0
		if options["max-depth"] != "" {
			maxDepth, err = parseIntegerOption(options, "max-depth")
			if err != nil {
				return nil, err
			}
			if maxDepth < 1 || maxDepth > 100 {
				return nil, apperr.New(
					apperr.CodeInvalidArgument,
					"max-depth must be between 1 and 100.",
					false,
					map[string]any{"field": "max-depth"},
				)
			}
		}
		return service.Snapshot(ctx, options["device"], targetPackage, maxDepth)
	case "action":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "action"),
			required: []string{"device", "action"},
		})
		if err != nil {
			return nil, err
		}
		action := json.RawMessage(strings.TrimSpace(options["action"]))
		if len(action) == 0 || action[0] != '{' {
			return nil, usageError("Action must be one JSON object.")
		}
		if len(action)+1 > bridge.MaxMessageBytes {
			return nil, apperr.New(
				apperr.CodeMessageTooLarge,
				"Action exceeds the 1048576 byte bridge message limit.",
				false,
				nil,
			)
		}
		return automation.ExecuteBridgeAction(ctx, options["device"], action)
	case "close":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if err != nil {
			return nil, err
		}
		return service.Close(ctx, options["device"])
	default:
		return nil, usageError(
			"Unknown bridge command. Supported commands: open, info, snapshot, action, close.",
		)
	}
}

func (a *App) bridgeService(client *adb.Client) (*bridge.Service, error) {
	rpcClient := a.BridgeClient
	if rpcClient == nil {
		rpcClient = bridge.NewClient()
	}
	store := a.BridgeStore
	if store == nil {
		var err error
		store, err = bridge.DefaultSessionStore()
		if err != nil {
			return nil, err
		}
	}
	return bridge.NewService(client, rpcClient, store), nil
}
