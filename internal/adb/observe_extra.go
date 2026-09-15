package adb

// 功能用途：本文件实现无需 App Bridge 的前台应用探测、已安装应用清单与屏幕录制。
//
// 这三项能力都只依赖官方 Platform-Tools 的 adb，不需要在设备上安装任何伴生 App，
// 也不需要无障碍服务。之所以单独成文件而不并入 direct.go，是因为它们的输出都需要
// 解析 dumpsys/pm 的文本格式，解析规则与既有的截图、层级、输入动作没有共用逻辑。
//
// 安全边界：所有远端命令都是固定字面量加已校验参数，调用方无法注入 Shell 片段；
// 录制文件使用固定远端路径，且在拉取后总是尝试清理。

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

const (
	// recordingRemotePath 是屏幕录制在设备上的固定落地路径。
	// 与层级快照一样使用 /data/local/tmp，避免依赖外部存储权限。
	recordingRemotePath = "/data/local/tmp/aactl-screen.mp4"

	// maxRecordingOutput 限制单次录制产物大小，防止长录制拖垮宿主内存。
	maxRecordingOutput = 256 * 1024 * 1024

	// maxPackageListOutput 限制 pm list packages 的输出规模。
	maxPackageListOutput = 4 * 1024 * 1024

	// maxForegroundOutput 限制 dumpsys activity 的输出规模。
	// dumpsys activity activities 在真机上可达数百 KB，模拟器上通常远小于此。
	maxForegroundOutput = 8 * 1024 * 1024

	// recordingMinSeconds 与 recordingMaxSeconds 约束录制时长。
	// 上限取 180 秒与设备端 screenrecord 默认上限一致；调用方需要更长录制时
	// 应分段，而不是依赖设备端的静默截断。
	recordingMinSeconds = 1
	recordingMaxSeconds = 180
)

var (
	// foregroundComponentPattern 从 dumpsys 输出里取出 包名/Activity 组件。
	// 同时兼容 topResumedActivity 与 mResumedActivity 两种字段名：
	// 前者是 Android 10 之后的字段，后者在旧版本与部分 OEM 上仍然出现。
	foregroundComponentPattern = regexp.MustCompile(
		`(?:topResumedActivity|mResumedActivity|mFocusedActivity)=ActivityRecord\{[^ ]+ [^ ]+ ([^ /]+)/([^ }]+)`)

	// foregroundFocusPattern 是退路：从 mCurrentFocus 取窗口所属组件。
	// 只有当 Activity 字段全部缺失时才使用，因为焦点窗口可能是对话框或输入法，
	// 未必等于当前 Activity。
	foregroundFocusPattern = regexp.MustCompile(
		`mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^ /}]+)/([^ }]+)\}`)

	// packageLinePattern 解析 pm list packages -f 的一行。
	// 格式为 package:<apk 路径>=<包名>，APK 路径本身可能含等号，因此取最后一个。
	packageLinePattern = regexp.MustCompile(`^package:(.*)=([A-Za-z][A-Za-z0-9_.]*)$`)

	// versionNamePattern 与 versionCodePattern 从 dumpsys package 提取版本。
	versionNamePattern = regexp.MustCompile(`versionName=([^\s]+)`)
	versionCodePattern = regexp.MustCompile(`versionCode=(\d+)`)
)

// Foreground 描述当前处于前台的应用与界面。
//
// UI 自动化在 App 变更后需要先知道"现在在哪"才能纠正，这是该判断的取值来源。
// Activity 可能为空：某些系统界面或过渡状态下 dumpsys 不给出完整组件名，
// 此时 Package 仍然有效，调用方不应把空 Activity 当作失败。
type Foreground struct {
	Device   string `json:"device"`
	Package  string `json:"package"`
	Activity string `json:"activity,omitempty"`
	// Source 说明该结果来自哪个 dumpsys 字段，便于诊断为何拿到意外的包名。
	Source string `json:"source"`
}

// InstalledApp 描述一个已安装应用的可观察信息。
type InstalledApp struct {
	Package     string `json:"package"`
	APKPath     string `json:"apkPath,omitempty"`
	VersionName string `json:"versionName,omitempty"`
	VersionCode int64  `json:"versionCode,omitempty"`
	System      bool   `json:"system"`
}

// InstalledApps 是一次应用清单查询的结果。
type InstalledApps struct {
	Device string         `json:"device"`
	Apps   []InstalledApp `json:"apps"`
	Count  int            `json:"count"`
	// DetailsIncluded 说明是否逐包查询了版本号。
	// 逐包查询需要对每个包各执行一次 dumpsys，包数多时代价明显，因此可关闭。
	DetailsIncluded bool `json:"detailsIncluded"`
}

// ScreenRecording 保存已拉取的录制产物及其摘要。
type ScreenRecording struct {
	Bytes       []byte `json:"-"`
	SHA256      string `json:"sha256"`
	SizeBytes   int    `json:"sizeBytes"`
	DurationSec int    `json:"durationSeconds"`
	// Width 与 Height 为 0 表示使用了设备默认分辨率。
	Width   int `json:"width,omitempty"`
	Height  int `json:"height,omitempty"`
	BitRate int `json:"bitRate,omitempty"`
}

// RecordingOptions 描述一次屏幕录制的参数。
type RecordingOptions struct {
	// Seconds 是录制时长，必填，范围 1..180。
	Seconds int
	// Width 与 Height 同时为 0 时使用设备默认分辨率。
	Width  int
	Height int
	// BitRate 为 0 时使用设备默认值（20Mbps）。
	BitRate int
}

// Foreground 读取当前前台应用与 Activity。
//
// 实现取舍：优先取 topResumedActivity 等 Activity 字段，只有全部缺失时才退回
// mCurrentFocus。因为焦点窗口可能是对话框、输入法或系统弹窗，用它代表"当前应用"
// 会在弹窗出现时给出误导性的包名，而这恰恰是自动化最需要准确判断的时刻。
func (c *Client) Foreground(ctx context.Context, serial string) (Foreground, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return Foreground{}, err
	}
	result, err := c.run(ctx, []string{
		"-s", serial, "shell", "dumpsys", "activity", "activities",
	}, process.Options{Timeout: 20 * time.Second, MaxOutput: maxForegroundOutput})
	if err != nil {
		return Foreground{}, err
	}
	text := string(result.Stdout)

	if match := foregroundComponentPattern.FindStringSubmatch(text); match != nil {
		return Foreground{
			Device:   serial,
			Package:  match[1],
			Activity: match[2],
			Source:   "resumedActivity",
		}, nil
	}

	// 退路：Activity 字段缺失时读焦点窗口，并如实标注来源。
	windowResult, windowErr := c.run(ctx, []string{
		"-s", serial, "shell", "dumpsys", "window", "windows",
	}, process.Options{Timeout: 20 * time.Second, MaxOutput: maxForegroundOutput})
	if windowErr == nil {
		if match := foregroundFocusPattern.FindStringSubmatch(string(windowResult.Stdout)); match != nil {
			return Foreground{
				Device:   serial,
				Package:  match[1],
				Activity: match[2],
				Source:   "currentFocus",
			}, nil
		}
	}

	return Foreground{}, apperr.New(
		apperr.CodeActionFailed,
		"No foreground activity could be determined from ADB output. "+
			"The device may be locked, booting, or showing a system surface.",
		true,
		map[string]any{"device": serial},
	)
}

// InstalledApps 列出设备上的已安装应用。
//
// includeSystem 为 false 时只列第三方应用（pm list packages -3）。
// withDetails 为 true 时逐包查询版本号，代价是每个包一次 dumpsys 调用；
// 包数较多时应关闭，并在需要时对单个包再查。
func (c *Client) InstalledApps(
	ctx context.Context,
	serial string,
	includeSystem bool,
	withDetails bool,
) (InstalledApps, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return InstalledApps{}, err
	}

	args := []string{"-s", serial, "shell", "pm", "list", "packages", "-f"}
	if !includeSystem {
		args = append(args, "-3")
	}
	result, err := c.run(ctx, args, process.Options{
		Timeout:   30 * time.Second,
		MaxOutput: maxPackageListOutput,
	})
	if err != nil {
		return InstalledApps{}, err
	}
	if result.OutputTruncated {
		return InstalledApps{}, apperr.New(
			apperr.CodeActionFailed,
			"The installed package list was truncated by the output limit.",
			true,
			map[string]any{"device": serial},
		)
	}

	systemPackages := map[string]struct{}{}
	if !includeSystem {
		// 已用 -3 过滤，剩下的都是第三方包。
	} else {
		// 需要区分系统与第三方时，额外查一次第三方清单作为判据，
		// 而不是靠 APK 路径前缀猜测——路径规则在不同 Android 版本上并不一致。
		thirdParty, thirdErr := c.run(ctx, []string{
			"-s", serial, "shell", "pm", "list", "packages", "-3",
		}, process.Options{Timeout: 30 * time.Second, MaxOutput: maxPackageListOutput})
		if thirdErr == nil {
			for _, line := range strings.Split(string(thirdParty.Stdout), "\n") {
				name := strings.TrimPrefix(strings.TrimSpace(line), "package:")
				if name != "" {
					systemPackages[name] = struct{}{}
				}
			}
		}
	}

	apps := make([]InstalledApp, 0, 64)
	for _, rawLine := range strings.Split(string(result.Stdout), "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" {
			continue
		}
		match := packageLinePattern.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		app := InstalledApp{APKPath: match[1], Package: match[2]}
		if includeSystem {
			_, isThirdParty := systemPackages[app.Package]
			app.System = !isThirdParty
		}
		apps = append(apps, app)
	}

	sort.Slice(apps, func(i, j int) bool { return apps[i].Package < apps[j].Package })

	if withDetails {
		for index := range apps {
			name, code, detailErr := c.packageVersion(ctx, serial, apps[index].Package)
			if detailErr != nil {
				// 单个包查不到版本不应让整份清单失败：应用可能在查询间隙被卸载，
				// 或该包对 dumpsys 输出格式特殊。缺失的字段留空，其余照常返回。
				continue
			}
			apps[index].VersionName = name
			apps[index].VersionCode = code
		}
	}

	return InstalledApps{
		Device:          serial,
		Apps:            apps,
		Count:           len(apps),
		DetailsIncluded: withDetails,
	}, nil
}

// packageVersion 查询单个包的版本名与版本号。
func (c *Client) packageVersion(
	ctx context.Context,
	serial, packageName string,
) (string, int64, error) {
	if err := ValidatePackageName(packageName); err != nil {
		return "", 0, err
	}
	result, err := c.run(ctx, []string{
		"-s", serial, "shell", "dumpsys", "package", packageName,
	}, process.Options{Timeout: 15 * time.Second, MaxOutput: maxPackageListOutput})
	if err != nil {
		return "", 0, err
	}
	text := string(result.Stdout)

	versionName := ""
	if match := versionNamePattern.FindStringSubmatch(text); match != nil {
		versionName = match[1]
	}
	var versionCode int64
	if match := versionCodePattern.FindStringSubmatch(text); match != nil {
		parsed, parseErr := strconv.ParseInt(match[1], 10, 64)
		if parseErr == nil {
			versionCode = parsed
		}
	}
	if versionName == "" && versionCode == 0 {
		return "", 0, apperr.New(
			apperr.CodeActionFailed,
			"No version information was found for the requested package.",
			false,
			map[string]any{"device": serial, "package": packageName},
		)
	}
	return versionName, versionCode, nil
}

// RecordScreen 录制屏幕并拉回 mp4 字节。
//
// 关键实现取舍：设备端 screenrecord 是阻塞式的，本方法用 --time-limit 让它自行结束，
// 而不是另起进程再发信号。原因是通过 adb shell 发 SIGINT 依赖会话保持与 pkill，
// 在不同 adb 版本上行为不一致；固定时长虽然不支持"提前停止"，但结果可复现。
// 需要不定长录制时应由上层分段调用。
func (c *Client) RecordScreen(
	ctx context.Context,
	serial string,
	options RecordingOptions,
) (recording ScreenRecording, returnErr error) {
	if options.Seconds < recordingMinSeconds || options.Seconds > recordingMaxSeconds {
		return ScreenRecording{}, invalidField(
			"seconds",
			fmt.Sprintf("must be between %d and %d", recordingMinSeconds, recordingMaxSeconds),
		)
	}
	if (options.Width == 0) != (options.Height == 0) {
		return ScreenRecording{}, invalidField(
			"size",
			"width and height must be provided together",
		)
	}
	if options.Width < 0 || options.Height < 0 ||
		options.Width > maxCoordinate || options.Height > maxCoordinate {
		return ScreenRecording{}, invalidField("size", "must be a positive resolution")
	}
	if options.BitRate < 0 {
		return ScreenRecording{}, invalidField("bitRate", "must not be negative")
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ScreenRecording{}, err
	}

	args := []string{
		"-s", serial, "shell", "screenrecord",
		"--time-limit", strconv.Itoa(options.Seconds),
	}
	if options.Width > 0 {
		args = append(args, "--size", fmt.Sprintf("%dx%d", options.Width, options.Height))
	}
	if options.BitRate > 0 {
		args = append(args, "--bit-rate", strconv.Itoa(options.BitRate))
	}
	args = append(args, recordingRemotePath)

	// 超时留出余量：编码收尾与文件写入会晚于 --time-limit 结束。
	recordTimeout := time.Duration(options.Seconds+30) * time.Second
	if _, err := c.run(ctx, args, process.Options{Timeout: recordTimeout}); err != nil {
		return ScreenRecording{}, err
	}

	defer func() {
		// 路径为固定常量，调用方无法注入。使用 WithoutCancel 保证即使上层已取消
		// 也仍然清理设备上的录制文件，避免下次录制读到上一次的残留。
		_, _ = c.run(context.WithoutCancel(ctx), []string{
			"-s", serial, "shell", "rm", "-f", recordingRemotePath,
		}, process.Options{Timeout: 10 * time.Second})
	}()

	pulled, err := c.run(ctx, []string{
		"-s", serial, "exec-out", "cat", recordingRemotePath,
	}, process.Options{Timeout: 120 * time.Second, MaxOutput: maxRecordingOutput})
	if err != nil {
		return ScreenRecording{}, err
	}
	if pulled.OutputTruncated || len(pulled.Stdout) == 0 || !validMP4Structure(pulled.Stdout) {
		return ScreenRecording{}, apperr.New(
			apperr.CodeActionFailed,
			"ADB returned an empty, truncated, or invalid MP4 recording.",
			true,
			map[string]any{"device": serial},
		)
	}

	sum := sha256.Sum256(pulled.Stdout)
	return ScreenRecording{
		Bytes:       pulled.Stdout,
		SHA256:      hex.EncodeToString(sum[:]),
		SizeBytes:   len(pulled.Stdout),
		DurationSec: options.Seconds,
		Width:       options.Width,
		Height:      options.Height,
		BitRate:     options.BitRate,
	}, nil
}

// validMP4Structure 检查字节流是否具备 MP4 的 ftyp box 结构。
//
// 与截图的 PNG 校验同理：不做完整解码，只确认容器头存在，
// 用于区分"真的拿到录制产物"和"拿到错误信息或空文件"。
func validMP4Structure(data []byte) bool {
	// 最小结构：4 字节 box 长度 + 4 字节 "ftyp" + 4 字节 major brand。
	if len(data) < 12 {
		return false
	}
	return string(data[4:8]) == "ftyp"
}
