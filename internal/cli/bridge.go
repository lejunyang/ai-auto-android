package cli

// 功能用途：本文件解析 Bridge 会话、语义观察和动作命令，并在 RPC 前完成参数校验。

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
		device, inlineAction, useStdin, err := parseBridgeActionOptions(args[1:])
		if err != nil {
			return nil, err
		}
		var action json.RawMessage
		if useStdin {
			action, err = readStrictJSONObject(a.Stdin, bridge.MaxMessageBytes-1)
			if err != nil {
				return nil, err
			}
		} else {
			action = json.RawMessage(strings.TrimSpace(inlineAction))
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
		}
		defer clear(action)
		return automation.ExecuteBridgeAction(ctx, device, action)
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

func parseBridgeActionOptions(args []string) (
	device string,
	inlineAction string,
	useStdin bool,
	returnErr error,
) {
	seen := make(map[string]struct{}, 3)
	for index := 0; index < len(args); index++ {
		argument := args[index]
		if !strings.HasPrefix(argument, "--") || argument == "--" {
			return "", "", false, usageError(
				"Usage: aactl bridge action --device SERIAL (--action JSON | --stdin) [--json]",
			)
		}
		nameValue := strings.TrimPrefix(argument, "--")
		name, value, inline := strings.Cut(nameValue, "=")
		if name != "device" && name != "action" && name != "stdin" {
			return "", "", false, usageError("Unknown bridge action option.")
		}
		if _, duplicate := seen[name]; duplicate {
			return "", "", false, usageError("Bridge action option was provided more than once.")
		}
		seen[name] = struct{}{}
		if name == "stdin" {
			if inline {
				return "", "", false, usageError("--stdin does not accept a value.")
			}
			useStdin = true
			continue
		}
		if !inline {
			index++
			if index >= len(args) {
				return "", "", false, usageError("Bridge action option requires a value.")
			}
			value = args[index]
		}
		if value == "" {
			return "", "", false, usageError("Bridge action option requires a non-empty value.")
		}
		if name == "device" {
			device = value
		} else {
			inlineAction = value
		}
	}
	if device == "" || (inlineAction == "") == !useStdin {
		return "", "", false, usageError(
			"Usage: aactl bridge action --device SERIAL (--action JSON | --stdin) [--json]",
		)
	}
	if err := adb.ValidateSerial(device); err != nil {
		return "", "", false, err
	}
	return device, inlineAction, useStdin, nil
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
