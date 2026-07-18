package cli

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/output"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

var Version = "dev"

type PathResolver interface {
	Resolve() (string, error)
}

type App struct {
	Locator      PathResolver
	Executor     process.Executor
	Stdin        io.Reader
	Stdout       io.Writer
	Stderr       io.Writer
	BridgeClient bridge.RPCClient
	BridgeStore  bridge.SessionStore
}

func DefaultApp(stdin io.Reader, stdout, stderr io.Writer) *App {
	locator := adb.DefaultLocator()
	return &App{
		Locator:      locator,
		Executor:     process.Runner{},
		Stdin:        stdin,
		Stdout:       stdout,
		Stderr:       stderr,
		BridgeClient: bridge.NewClient(),
	}
}

func (a *App) Run(ctx context.Context, args []string) int {
	startedAt := time.Now()
	requestID, err := output.RequestID()
	if err != nil {
		requestID = "00000000-0000-4000-8000-000000000000"
	}
	cleanArgs, compact := extractJSONFlag(args)

	data, runErr := a.execute(ctx, cleanArgs)
	envelope := output.Success(requestID, startedAt, data)
	exitCode := apperr.ExitSuccess
	if runErr != nil {
		envelope = output.Failure(requestID, startedAt, runErr)
		exitCode = apperr.ExitCode(runErr)
	}
	if writeErr := output.Write(a.Stdout, envelope, compact); writeErr != nil {
		_, _ = fmt.Fprintf(a.Stderr, "write output: %v\n", writeErr)
		return apperr.ExitInternal
	}
	return exitCode
}

func (a *App) execute(ctx context.Context, args []string) (any, error) {
	if len(args) == 0 {
		return nil, usageError("A command is required.")
	}
	if args[0] == "version" {
		if len(args) != 1 {
			return nil, usageError("Usage: aactl version [--json]")
		}
		return map[string]any{
			"name":          "aactl",
			"version":       Version,
			"schemaVersion": "1.0",
		}, nil
	}

	path, err := a.Locator.Resolve()
	if err != nil {
		return nil, err
	}
	client := adb.NewClient(path, a.Executor)

	switch args[0] {
	case "doctor":
		if len(args) != 1 {
			return nil, usageError("Usage: aactl doctor [--json]")
		}
		return client.Doctor(ctx)
	case "devices":
		return a.executeDevices(ctx, client, args[1:])
	case "device":
		return a.executeDevice(ctx, client, args[1:])
	case "observe":
		return a.executeObserve(ctx, client, args[1:])
	case "action":
		return a.executeAction(ctx, client, args[1:])
	case "bridge":
		return a.executeBridge(ctx, client, args[1:])
	default:
		return nil, usageError("Unknown command. Supported commands: version, doctor, devices, device, observe, action, bridge.")
	}
}

func (a *App) executeDevices(ctx context.Context, client *adb.Client, args []string) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl devices list|pair|connect")
	}
	switch args[0] {
	case "list":
		if len(args) != 1 {
			return nil, usageError("Usage: aactl devices list [--json]")
		}
		devices, err := client.Devices(ctx)
		if err != nil {
			return nil, err
		}
		return map[string]any{"devices": devices, "count": len(devices)}, nil
	case "pair":
		if len(args) != 2 {
			return nil, usageError("Usage: aactl devices pair HOST:PORT [--json]")
		}
		_, _ = fmt.Fprint(a.Stderr, "Pairing code: ")
		code, err := readPairingCode(a.Stdin)
		if err != nil {
			return nil, err
		}
		return client.Pair(ctx, args[1], code)
	case "connect":
		if len(args) != 2 {
			return nil, usageError("Usage: aactl devices connect HOST:PORT [--json]")
		}
		return client.Connect(ctx, args[1])
	default:
		return nil, usageError("Unknown devices command. Supported commands: list, pair, connect.")
	}
}

func (a *App) executeDevice(ctx context.Context, client *adb.Client, args []string) (any, error) {
	if len(args) == 0 || args[0] != "info" {
		return nil, usageError("Usage: aactl device info --device SERIAL [--json]")
	}
	serial, err := parseDeviceOption(args[1:])
	if err != nil {
		return nil, err
	}
	return client.DeviceInfo(ctx, serial)
}

func extractJSONFlag(args []string) ([]string, bool) {
	clean := make([]string, 0, len(args))
	compact := false
	for _, argument := range args {
		if argument == "--json" {
			compact = true
			continue
		}
		clean = append(clean, argument)
	}
	return clean, compact
}

func parseDeviceOption(args []string) (string, error) {
	if len(args) == 2 && args[0] == "--device" {
		if err := adb.ValidateSerial(args[1]); err != nil {
			return "", err
		}
		return args[1], nil
	}
	if len(args) == 1 && strings.HasPrefix(args[0], "--device=") {
		serial := strings.TrimPrefix(args[0], "--device=")
		if err := adb.ValidateSerial(serial); err != nil {
			return "", err
		}
		return serial, nil
	}
	return "", usageError("Usage: aactl device info --device SERIAL [--json]")
}

func readPairingCode(reader io.Reader) (string, error) {
	buffered := bufio.NewReader(io.LimitReader(reader, 128))
	code, err := buffered.ReadString('\n')
	if err != nil && err != io.EOF {
		return "", apperr.Wrap(apperr.CodeInvalidArgument, "Pairing code could not be read from stdin.", false, err)
	}
	code = strings.TrimSpace(code)
	if validationErr := adb.ValidatePairingCode(code); validationErr != nil {
		return "", validationErr
	}
	return code, nil
}

func usageError(message string) error {
	return apperr.New(apperr.CodeInvalidArgument, message, false, nil)
}
