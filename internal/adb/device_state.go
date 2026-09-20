package adb

// 功能用途：本文件实现直连通道的设备状态控制、IME 切换、deeplink 打开与文件传输。
//
// 通道归属的判据：这几类能力都不需要语义节点定位，且都有稳定的 ADB 入口，
// 因此放在 direct 通道而非 App Bridge。
//   - 屏幕方向走 `settings put system user_rotation`：`wm user-rotation` 在部分
//     版本上不存在，而 settings 键自 API 17 起稳定；
//   - 唤醒走 `input keyevent KEYCODE_WAKEUP`：它是幂等的，与 KEYCODE_POWER 的
//     「切换」语义不同——用 POWER 会在已亮屏时把屏幕关掉；
//   - 亮屏检测读 `dumpsys display` 的 mScreenState，比 `dumpsys power` 的
//     mWakefulness 更贴近「能否交互」（后者在 Doze 下语义含糊）；
//   - deeplink 走 `am start -a VIEW -d`；
//   - 文件传输走 `adb push`/`pull`，它们是 adb 协议原语而非 shell 命令。
//
// 剪贴板**不在**本文件：设备上不存在 `cmd clipboard` shell 命令
// （实测返回 "No shell command implementation"），只能经 App Bridge 的
// ClipboardManager，见 docs/action-surface-tasks.md 的 F6。

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"path"
	"regexp"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

// Orientation 是受支持的屏幕方向。
type Orientation string

// 屏幕方向取值。
//
// auto 表示交还给传感器，portrait/landscape 表示锁定。
// 不提供 180 度与反向横屏：那两个方向在多数应用上与正向等价，
// 但会让「锁定后 UI 到底是什么朝向」变得难以预期。
const (
	OrientationPortrait  Orientation = "portrait"
	OrientationLandscape Orientation = "landscape"
	OrientationAuto      Orientation = "auto"
)

const (
	// userRotationPortrait 与 userRotationLandscape 是 Surface.ROTATION_* 的值：
	// 0 表示竖屏，1 表示逆时针 90 度（即横屏）。
	userRotationPortrait  = "0"
	userRotationLandscape = "1"

	maxURILength        = 2048
	maxRemotePathLength = 4096

	// maxPullOutput 限制单次 pull 的体量。取 64MiB：足以覆盖日志与截图，
	// 又不至于让一次误操作把整个 /sdcard 拉下来耗尽内存。
	maxPullOutput = 64 * 1024 * 1024
)

var (
	// screenStatePattern 解析 `dumpsys display` 的 mScreenState 行。
	screenStatePattern = regexp.MustCompile(`mScreenState=(\w+)`)

	// imeIDPattern 校验输入法组件名，格式与 `ime list -s` 的输出一致。
	imeIDPattern = regexp.MustCompile(
		`^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+$`,
	)

	// remotePathPattern 限制设备端路径的字符集。
	//
	// 这不是样式偏好而是注入防线：远端路径会作为 shell 参数传给设备，
	// 允许引号、分号、反引号或 $ 会让调用方能拼出任意命令。
	remotePathPattern = regexp.MustCompile(`^[A-Za-z0-9 ._\-/]+$`)
)

// remoteExistsMarker 是远端存在性探测命中时打印的标记。
//
// 用显式标记而不是只看退出码：`adb shell` 的退出码在部分实现上反映的是 adb
// 自身而非远端命令，只看退出码会把「探测没跑成」误读成「文件不存在」，
// 而那个方向的误判会放行覆盖。
const remoteExistsMarker = "AACTL_REMOTE_EXISTS"

// ScreenState 描述屏幕当前的点亮状态。
type ScreenState struct {
	Device string `json:"device"`

	// State 是设备原样返回的状态名（ON / OFF / DOZE 等），不做归一化。
	// 归一化会丢失 DOZE 与 OFF 的区别，而那对「能否交互」是实质差异。
	State string `json:"state"`

	// On 只在 State 明确为 ON 时为真。DOZE 不算亮屏：那时屏幕虽有显示
	// 但不接受触摸。
	On bool `json:"on"`
}

// FileTransfer 描述一次文件推送或拉取的结果。
type FileTransfer struct {
	Device     string `json:"device"`
	Action     string `json:"action"`
	LocalPath  string `json:"localPath"`
	RemotePath string `json:"remotePath"`
}

// Rotate 锁定或解锁屏幕方向。
//
// 锁定方向必须先关掉 accelerometer_rotation：该值为 1 时系统会按传感器覆盖
// user_rotation，于是出现「设置成功但方向没变」。这个顺序依赖是平台行为，
// 不是可选优化。
func (c *Client) Rotate(
	ctx context.Context,
	serial string,
	orientation Orientation,
) (ActionResult, error) {
	var rotation string
	switch orientation {
	case OrientationPortrait:
		rotation = userRotationPortrait
	case OrientationLandscape:
		rotation = userRotationLandscape
	case OrientationAuto:
		rotation = ""
	default:
		return ActionResult{}, invalidField(
			"orientation", "must be portrait, landscape, or auto",
		)
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}

	if orientation == OrientationAuto {
		if err := c.putSetting(ctx, serial, "accelerometer_rotation", "1"); err != nil {
			return ActionResult{}, err
		}
		return ActionResult{Device: serial, Action: "device.rotate"}, nil
	}

	// 先禁用传感器旋转，再写死方向；顺序颠倒会让传感器立刻把方向改回去。
	if err := c.putSetting(ctx, serial, "accelerometer_rotation", "0"); err != nil {
		return ActionResult{}, err
	}
	if err := c.putSetting(ctx, serial, "user_rotation", rotation); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "device.rotate"}, nil
}

// Wake 点亮屏幕。
//
// 用 KEYCODE_WAKEUP 而不是 KEYCODE_POWER：POWER 是切换语义，
// 在屏幕已亮时会把它关掉，那与「唤醒」的意图相反。WAKEUP 是幂等的。
func (c *Client) Wake(ctx context.Context, serial string) (ActionResult, error) {
	return c.inputAction(ctx, serial, "device.wake", "keyevent", "KEYCODE_WAKEUP")
}

// Unlock 解除无凭据的锁屏。
//
// 只处理没有设置密码、图案或 PIN 的情况：先唤醒再发 MENU 键解锁。
// 有凭据的锁屏不在本方法职责内——那需要输入用户密码，属于用户必须亲自完成
// 的操作，自动化绕过它是安全边界问题而非功能缺失。
func (c *Client) Unlock(ctx context.Context, serial string) (ActionResult, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	for _, keyCode := range []string{"KEYCODE_WAKEUP", "KEYCODE_MENU"} {
		if err := c.runAction(ctx, serial, []string{
			"-s", serial, "shell", "input", "keyevent", keyCode,
		}, process.Options{}); err != nil {
			return ActionResult{}, err
		}
	}
	return ActionResult{Device: serial, Action: "device.unlock"}, nil
}

// ScreenState 读取屏幕点亮状态。
//
// 读 `dumpsys display` 的 mScreenState 而非 `dumpsys power` 的 mWakefulness：
// 后者在 Doze 下仍报 Asleep 或 Dozing，与「屏幕是否亮」不完全对应，
// 而调用方真正关心的是能不能看到并点到内容。
func (c *Client) ScreenState(ctx context.Context, serial string) (ScreenState, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ScreenState{}, err
	}
	result, err := c.run(ctx, []string{"-s", serial, "shell", "dumpsys", "display"},
		process.Options{Timeout: 15 * time.Second, MaxOutput: 8 * 1024 * 1024})
	if err != nil {
		return ScreenState{}, err
	}
	match := screenStatePattern.FindStringSubmatch(string(result.Stdout))
	if match == nil {
		return ScreenState{}, apperr.New(
			apperr.CodeActionFailed,
			"The device screen state could not be read from ADB output.",
			true,
			map[string]any{"device": serial},
		)
	}
	state := match[1]
	return ScreenState{
		Device: serial,
		State:  state,
		// 只有明确 ON 才算亮屏；DOZE 有显示但不接受触摸。
		On: strings.EqualFold(state, "ON"),
	}, nil
}

// ListInputMethods 列出设备上已启用的输入法标识。
func (c *Client) ListInputMethods(ctx context.Context, serial string) ([]string, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return nil, err
	}
	result, err := c.run(ctx, []string{"-s", serial, "shell", "ime", "list", "-s"},
		process.Options{Timeout: 15 * time.Second, MaxOutput: 256 * 1024})
	if err != nil {
		return nil, err
	}
	identifiers := make([]string, 0, 4)
	normalized := strings.ReplaceAll(string(result.Stdout), "\r\n", "\n")
	for _, line := range strings.Split(normalized, "\n") {
		trimmed := strings.TrimSpace(line)
		if imeIDPattern.MatchString(trimmed) {
			identifiers = append(identifiers, trimmed)
		}
	}
	return identifiers, nil
}

// SelectInputMethod 切换默认输入法。
//
// 只接受已启用列表里的标识：`ime set` 对未启用的输入法会静默失败，
// 先校验能把「切换没生效」变成明确的参数错误。
func (c *Client) SelectInputMethod(
	ctx context.Context,
	serial, identifier string,
) (ActionResult, error) {
	if !imeIDPattern.MatchString(identifier) {
		return ActionResult{}, invalidField(
			"id", "must be a package/component input method identifier",
		)
	}
	available, err := c.ListInputMethods(ctx, serial)
	if err != nil {
		return ActionResult{}, err
	}
	enabled := false
	for _, candidate := range available {
		if candidate == identifier {
			enabled = true
			break
		}
	}
	if !enabled {
		return ActionResult{}, invalidField(
			"id", "is not an enabled input method on this device",
		)
	}
	if err := c.runAction(ctx, serial, []string{
		"-s", serial, "shell", "ime", "set", identifier,
	}, process.Options{Timeout: 15 * time.Second}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "ime.select"}, nil
}

// HideKeyboard 收起软键盘。
//
// 用 BACK 键：Android 没有公开的「隐藏输入法」shell 命令，而 BACK 在键盘
// 展开时的第一优先行为就是收起它。副作用是键盘未展开时会触发返回导航，
// 因此调用方应先确认键盘可见——本方法不代为判断，那需要读窗口列表，
// 属于桥侧能力。
func (c *Client) HideKeyboard(ctx context.Context, serial string) (ActionResult, error) {
	return c.inputAction(ctx, serial, "ime.hide", "keyevent", "KEYCODE_BACK")
}

// OpenDeeplink 用 VIEW intent 打开一个 URI。
//
// 先校验 URI 可解析且 scheme 非空：`am start -d` 对畸形 URI 会打印
// 「Error: Bad URI」但退出码仍可能为 0，先校验能把静默失败变成明确错误。
// packageName 非空时限定目标包，避免弹出应用选择器让自动化卡住。
func (c *Client) OpenDeeplink(
	ctx context.Context,
	serial, uri, packageName string,
) (ActionResult, error) {
	if len(uri) == 0 || len(uri) > maxURILength {
		return ActionResult{}, invalidField(
			"uri", fmt.Sprintf("must be between 1 and %d bytes", maxURILength),
		)
	}
	parsed, err := url.Parse(uri)
	if err != nil || parsed.Scheme == "" {
		return ActionResult{}, invalidField("uri", "must be an absolute URI with a scheme")
	}
	// URI 会作为 shell 参数传递，含空格或 shell 元字符时可能被拆开或改变语义。
	if strings.ContainsAny(uri, " \t\n\"'`;|&$()<>") {
		return ActionResult{}, invalidField(
			"uri", "must not contain shell metacharacters or whitespace",
		)
	}
	if packageName != "" {
		if err := ValidatePackageName(packageName); err != nil {
			return ActionResult{}, err
		}
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	args := []string{
		"-s", serial, "shell", "am", "start",
		"-a", "android.intent.action.VIEW", "-d", uri,
	}
	if packageName != "" {
		args = append(args, "-p", packageName)
	}
	if err := c.runAction(ctx, serial, args,
		process.Options{Timeout: 20 * time.Second}); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "app.deeplink"}, nil
}

// PushFile 把本地文件推送到设备。
func (c *Client) PushFile(
	ctx context.Context,
	serial, localPath, remotePath string,
) (FileTransfer, error) {
	if err := validateRemotePath(remotePath); err != nil {
		return FileTransfer{}, err
	}
	if err := validateLocalPath(localPath); err != nil {
		return FileTransfer{}, err
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return FileTransfer{}, err
	}
	// 推送前必须确认远端为空位：adb push 会直接覆盖，一旦发出就无法挽回。
	exists, err := c.remotePathExists(ctx, serial, remotePath)
	if err != nil {
		return FileTransfer{}, err
	}
	if exists {
		return FileTransfer{}, apperr.New(
			apperr.CodeActionNotAllowed,
			fmt.Sprintf(
				"The device path %s already exists; file push only creates new files "+
					"and never overwrites or deletes. Choose another remote path.",
				remotePath,
			),
			false,
			map[string]any{"remotePath": remotePath},
		)
	}
	if err := c.runAction(ctx, serial, []string{
		"-s", serial, "push", localPath, remotePath,
	}, process.Options{Timeout: 120 * time.Second}); err != nil {
		return FileTransfer{}, err
	}
	return FileTransfer{
		Device:     serial,
		Action:     "file.push",
		LocalPath:  localPath,
		RemotePath: remotePath,
	}, nil
}

// PullFile 把设备文件拉取到本地。
func (c *Client) PullFile(
	ctx context.Context,
	serial, remotePath, localPath string,
) (FileTransfer, error) {
	if err := validateRemotePath(remotePath); err != nil {
		return FileTransfer{}, err
	}
	if err := validateLocalPath(localPath); err != nil {
		return FileTransfer{}, err
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return FileTransfer{}, err
	}
	// 拉取前确认本地为空位。adb pull 由外部进程写文件，Go 无法用 O_EXCL 原子创建，
	// 只能先检查后执行；这个窗口无法消除，见 remotePathExists 的竞态说明。
	if _, statErr := os.Stat(localPath); statErr == nil {
		return FileTransfer{}, apperr.New(
			apperr.CodeActionNotAllowed,
			fmt.Sprintf(
				"The local path %s already exists; file pull only creates new files "+
					"and never overwrites or deletes. Choose another local path.",
				localPath,
			),
			false,
			map[string]any{"localPath": localPath},
		)
	} else if !errors.Is(statErr, fs.ErrNotExist) {
		// 既不是「存在」也不是「不存在」：权限或路径异常。此时不能假定为空位。
		return FileTransfer{}, apperr.Wrap(
			apperr.CodeActionNotAllowed,
			fmt.Sprintf("Cannot determine whether the local path %s is free.", localPath),
			false,
			statErr,
		)
	}
	if err := c.runAction(ctx, serial, []string{
		"-s", serial, "pull", remotePath, localPath,
	}, process.Options{Timeout: 120 * time.Second, MaxOutput: maxPullOutput}); err != nil {
		return FileTransfer{}, err
	}
	return FileTransfer{
		Device:     serial,
		Action:     "file.pull",
		LocalPath:  localPath,
		RemotePath: remotePath,
	}, nil
}

// putSetting 写入一个 system 命名空间的 settings 键。
//
// 键名由调用方以常量给出，不接受外部输入——settings 能改动系统行为，
// 开放任意键会让这条通道成为提权入口。
func (c *Client) putSetting(ctx context.Context, serial, key, value string) error {
	return c.runAction(ctx, serial, []string{
		"-s", serial, "shell", "settings", "put", "system", key, value,
	}, process.Options{Timeout: 15 * time.Second})
}

// remotePathExists 判断设备上是否已存在该路径。
//
// 为什么要单引号包裹：validateRemotePath 的字符集允许空格（`/sdcard/a b.txt`
// 是合法路径），裸拼进 shell 会被拆成两个参数，`test -e` 只看前半段，对已存在的
// 文件误判为不存在——那不是注入漏洞，而是覆盖保护静默失效。单引号在这里是完备的：
// 白名单已排除单引号本身，路径中不可能出现能闭合引号的字符。因此白名单与本函数
// 必须一起维护，放宽字符集前要先回来看这里。
//
// 竞态（TOCTOU）：本函数返回「不存在」之后、adb push 落盘之前，设备上可能有其它
// 进程创建同名文件，此时仍会被覆盖。adb 协议层没有 O_EXCL 语义，这个窗口无法消除。
// 因此调用方的错误信息只表述为「已检查该路径当时不存在」，不表述为「保证不覆盖」。
func (c *Client) remotePathExists(
	ctx context.Context,
	serial, remotePath string,
) (bool, error) {
	result, err := c.run(ctx, []string{
		"-s", serial, "shell",
		fmt.Sprintf("test -e '%s' && echo %s", remotePath, remoteExistsMarker),
	}, process.Options{Timeout: 20 * time.Second, MaxOutput: 4 * 1024})
	if err != nil {
		// 探测没跑成时必须拒绝传输，不能「测不出来就当作不存在」——
		// 那会让设备临时不可达时保护退化成允许覆盖。
		return false, apperr.Wrap(
			apperr.CodeActionNotAllowed,
			fmt.Sprintf(
				"Cannot determine whether the device path %s is free; "+
					"refusing the transfer instead of risking an overwrite.",
				remotePath,
			),
			false,
			err,
		)
	}
	return strings.Contains(string(result.Stdout), remoteExistsMarker), nil
}

// validateRemotePath 校验设备端路径。
//
// 除字符集限制外还要求绝对路径并拒绝父目录段：相对路径的解析基准取决于
// adbd 的工作目录，不可预期；`..` 能逃出调用方以为的目录范围。
func validateRemotePath(remotePath string) error {
	if len(remotePath) == 0 || len(remotePath) > maxRemotePathLength {
		return invalidField(
			"remotePath",
			fmt.Sprintf("must be between 1 and %d bytes", maxRemotePathLength),
		)
	}
	if !strings.HasPrefix(remotePath, "/") {
		return invalidField("remotePath", "must be an absolute device path")
	}
	if !remotePathPattern.MatchString(remotePath) {
		return invalidField(
			"remotePath",
			"must contain only letters, digits, spaces, dot, underscore, hyphen and slash",
		)
	}
	if hasParentTraversal(remotePath) {
		return invalidField("remotePath", "must not contain parent directory segments")
	}
	return nil
}

// validateLocalPath 只做最小校验：拒绝空值与空字节。
//
// 不限制本地路径的字符集：它由使用者提供并由 Go 直接交给 adb 进程，
// 不经 shell 解释，因此没有注入面；过度限制反而会拒绝合法的中文或空格路径。
func validateLocalPath(localPath string) error {
	if localPath == "" {
		return invalidField("localPath", "must not be empty")
	}
	if strings.ContainsRune(localPath, '\x00') {
		return invalidField("localPath", "must not contain a null byte")
	}
	return nil
}

// hasParentTraversal 判断路径是否含父目录段。
//
// 同时检查清理后的路径与原始字符串：path.Clean 会把 `/a/../b` 归约成 `/b`，
// 单看清理结果会漏掉调用方的真实意图，而单看原始串会漏掉多层嵌套写法。
func hasParentTraversal(remotePath string) bool {
	for _, segment := range strings.Split(path.Clean(remotePath), "/") {
		if segment == ".." {
			return true
		}
	}
	return strings.Contains(remotePath, "..")
}
