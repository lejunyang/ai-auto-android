// Package cli 解析 aactl 命令，并通过共享服务层输出稳定 JSON 信封。
package cli

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/bridge/lan"
	"github.com/lejunyang/ai-auto-android/internal/mcpserver"
	"github.com/lejunyang/ai-auto-android/internal/output"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/lejunyang/ai-auto-android/internal/visual"
	sdkmcp "github.com/modelcontextprotocol/go-sdk/mcp"
)

// Version 在构建时注入 aactl 版本，开发构建默认为 dev。
var Version = "dev"

// PathResolver 抽象 ADB 路径发现，确保 version 等无设备命令无需解析 ADB。
type PathResolver interface {
	Resolve() (string, error)
}

// App 聚合 CLI 的可替换依赖、输入输出流和 MCP 传输。
type App struct {
	Locator      PathResolver
	Executor     process.Executor
	Stdin        io.Reader
	Stdout       io.Writer
	Stderr       io.Writer
	BridgeClient bridge.RPCClient
	BridgeStore  bridge.SessionStore
	// TrustedDevices 允许测试替换可信无线设备档案存储；生产环境默认使用用户配置目录。
	TrustedDevices adb.TrustedDeviceStore
	Automation     service.Automation
	DesktopVisual  *visual.DesktopProposalService
	// DesktopAttestedVisual 允许测试显式注入原子视觉执行服务；生产由同一 ADB/Bridge 构造。
	DesktopAttestedVisual *visual.DesktopAttestedActionService
	MCPTransport          sdkmcp.Transport
	// LANInterfaces 等窄端口允许测试替换网络、listener、随机源和二维码 provider。
	LANInterfaces lan.InterfaceSource
	LANBinder     lan.ListenerBinder
	LANRandom     io.Reader
	LANQR         lan.QRProvider
	LANNow        func() time.Time
	LANListeners  lanListenerStarter
}

// DefaultApp 创建使用真实 ADB、Bridge 和进程执行器的命令行应用。
func DefaultApp(stdin io.Reader, stdout, stderr io.Writer) *App {
	locator := adb.DefaultLocator()
	return &App{
		Locator:      locator,
		Executor:     process.Runner{},
		Stdin:        stdin,
		Stdout:       stdout,
		Stderr:       stderr,
		BridgeClient: bridge.NewClient(),
		LANQR:        lan.NewTerminalQRProvider(),
	}
}

// Run 执行一条命令并确保 stdout 只包含协议输出。
func (a *App) Run(ctx context.Context, args []string) int {
	if len(args) == 2 && args[0] == "mcp" && args[1] == "serve" {
		if err := a.serveMCP(ctx); err != nil {
			_, _ = fmt.Fprintf(a.Stderr, "MCP server failed: %v\n", err)
			return apperr.ExitCode(err)
		}
		return apperr.ExitSuccess
	}

	startedAt := time.Now()
	requestID, err := output.RequestID()
	if err != nil {
		requestID = "00000000-0000-4000-8000-000000000000"
	}
	cleanArgs, compact := extractJSONFlag(args)
	if len(cleanArgs) >= 2 && cleanArgs[0] == "bridge" && cleanArgs[1] == "lan" {
		return a.runLANCommand(
			ctx,
			requestID,
			startedAt,
			cleanArgs[2:],
			compact,
		)
	}

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
		return a.executeDevices(ctx, client, a.directAutomation(client), args[1:])
	case "device":
		return a.executeDevice(ctx, a.directAutomation(client), args[1:])
	case "observe":
		return a.executeObserve(ctx, a.directAutomation(client), args[1:])
	case "action":
		return a.executeAction(ctx, a.directAutomation(client), args[1:])
	case "recording":
		automation, err := a.automationService(client)
		if err != nil {
			return nil, err
		}
		return a.executeRecording(ctx, automation, args[1:])
	case "bridge":
		automation, err := a.automationService(client)
		if err != nil {
			return nil, err
		}
		return a.executeBridge(ctx, client, automation, args[1:])
	case "mcp":
		return nil, usageError("Usage: aactl mcp serve")
	default:
		return nil, usageError("Unknown command. Supported commands: version, doctor, devices, device, observe, action, recording, bridge, mcp.")
	}
}

func (a *App) executeDevices(
	ctx context.Context,
	client *adb.Client,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl devices list|watch|pair|connect|trust|trusted|reconnect|forget")
	}
	switch args[0] {
	case "list":
		if len(args) != 1 {
			return nil, usageError("Usage: aactl devices list [--json]")
		}
		return automation.ListDevices(ctx)
	case "watch":
		options, err := parseWatchOptions(args[1:])
		if err != nil {
			return nil, err
		}
		return client.WatchDevices(ctx, options)
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
	case "trust":
		serial, err := parseDeviceOption(args[1:])
		if err != nil {
			return nil, usageError("Usage: aactl devices trust --device SERIAL [--json]")
		}
		store, err := a.trustedDeviceStore()
		if err != nil {
			return nil, err
		}
		return client.TrustWirelessDevice(ctx, serial, store)
	case "trusted":
		if len(args) != 1 {
			return nil, usageError("Usage: aactl devices trusted [--json]")
		}
		store, err := a.trustedDeviceStore()
		if err != nil {
			return nil, err
		}
		profiles, err := store.List()
		if err != nil {
			return nil, err
		}
		return map[string]any{"devices": profiles, "count": len(profiles)}, nil
	case "reconnect":
		identity, err := parseIdentityOption(args[1:])
		if err != nil {
			return nil, err
		}
		store, err := a.trustedDeviceStore()
		if err != nil {
			return nil, err
		}
		return client.ReconnectTrustedDevice(ctx, identity, store)
	case "forget":
		identity, err := parseIdentityOption(args[1:])
		if err != nil {
			return nil, err
		}
		store, err := a.trustedDeviceStore()
		if err != nil {
			return nil, err
		}
		if err := store.Delete(identity); err != nil {
			return nil, err
		}
		return map[string]any{"identity": identity, "forgotten": true}, nil
	default:
		return nil, usageError("Unknown devices command. Supported commands: list, watch, pair, connect, trust, trusted, reconnect, forget.")
	}
}

func (a *App) executeDevice(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 || args[0] != "info" {
		return nil, usageError("Usage: aactl device info --device SERIAL [--json]")
	}
	serial, err := parseDeviceOption(args[1:])
	if err != nil {
		return nil, err
	}
	return automation.GetDevice(ctx, serial)
}

func (a *App) directAutomation(client *adb.Client) service.Automation {
	if a.Automation != nil {
		return a.Automation
	}
	return service.New(client, nil)
}

func (a *App) automationService(client *adb.Client) (service.Automation, error) {
	if a.Automation != nil {
		return a.Automation, nil
	}
	bridgeService, err := a.bridgeService(client)
	if err != nil {
		return nil, err
	}
	return service.New(client, bridgeService), nil
}

func (a *App) serveMCP(ctx context.Context) error {
	var automation service.Automation
	desktopVisual := a.DesktopVisual
	desktopAttestedVisual := a.DesktopAttestedVisual
	if a.Automation != nil {
		automation = a.Automation
	} else {
		path, err := a.Locator.Resolve()
		if err != nil {
			return err
		}
		client := adb.NewClient(path, a.Executor)
		bridgeService, err := a.bridgeService(client)
		if err != nil {
			return err
		}
		automation = service.New(client, bridgeService)
		desktopVisual = visual.NewDesktopProposalService(client, visual.DesktopOptions{})
		desktopAttestedVisual = visual.NewDesktopAttestedActionService(
			client,
			bridgeService,
			visual.DesktopAttestedActionOptions{},
		)
	}
	transport := a.MCPTransport
	if transport == nil {
		transport = &sdkmcp.StdioTransport{}
	}
	return mcpserver.Run(
		ctx,
		automation,
		Version,
		transport,
		desktopVisual,
		desktopAttestedVisual,
	)
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

func (a *App) trustedDeviceStore() (adb.TrustedDeviceStore, error) {
	if a.TrustedDevices != nil {
		return a.TrustedDevices, nil
	}
	return adb.DefaultTrustedDeviceStore()
}

func parseWatchOptions(args []string) (adb.WatchOptions, error) {
	options := adb.WatchOptions{}
	for len(args) > 0 {
		if len(args) < 2 {
			return adb.WatchOptions{}, usageError(
				"Usage: aactl devices watch [--duration DURATION] [--interval DURATION] [--max-events COUNT] [--json]",
			)
		}
		name, value := args[0], args[1]
		args = args[2:]
		switch name {
		case "--duration":
			duration, err := time.ParseDuration(value)
			if err != nil {
				return adb.WatchOptions{}, usageError("Watch duration is invalid.")
			}
			options.Duration = duration
		case "--interval":
			interval, err := time.ParseDuration(value)
			if err != nil {
				return adb.WatchOptions{}, usageError("Watch interval is invalid.")
			}
			options.Interval = interval
		case "--max-events":
			maxEvents, err := strconv.Atoi(value)
			if err != nil {
				return adb.WatchOptions{}, usageError("Watch max-events is invalid.")
			}
			options.MaxEvents = maxEvents
		default:
			return adb.WatchOptions{}, usageError(
				"Usage: aactl devices watch [--duration DURATION] [--interval DURATION] [--max-events COUNT] [--json]",
			)
		}
	}
	return options, nil
}

func parseIdentityOption(args []string) (string, error) {
	identity := ""
	if len(args) == 2 && args[0] == "--identity" && args[1] != "" {
		identity = args[1]
	}
	if len(args) == 1 && strings.HasPrefix(args[0], "--identity=") {
		identity = strings.TrimPrefix(args[0], "--identity=")
	}
	if identity != "" {
		if err := adb.ValidateTrustedIdentity(identity); err != nil {
			return "", err
		}
		return identity, nil
	}
	return "", usageError("Usage: aactl devices reconnect|forget --identity IDENTITY [--json]")
}

func usageError(message string) error {
	return apperr.New(apperr.CodeInvalidArgument, message, false, nil)
}
