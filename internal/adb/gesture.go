package adb

// 功能用途：本文件实现直连模式下的长按与滚动动作，按 ADB input swipe 语义合成手势。
//
// 之所以不新增 adb 原语：`adb shell input` 没有独立的长按或滚动命令。
// 长按等价于"起点与终点相同、持续指定时长的 swipe"；滚动等价于"按方向位移的 swipe"。
// 这两条合成规则写在这里而不是让调用方自己拼坐标，是为了让 CLI 与 MCP 只表达意图
// （按住多久、朝哪个方向滚），不必关心手势如何合成。
//
// 与协议层 ui.longClick / ui.scroll 的差别：协议层用 target 选择器定位元素，
// 这里只接受坐标。直连通道拿不到稳定的元素引用（那需要 App Bridge 的无障碍节点），
// 因此把"元素级定位"留给桥侧，直连侧只提供坐标级手势。

import (
	"context"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

// screenSizePattern 解析 `wm size` 的一行输出。
// 捕获组：1=Physical|Override，2=宽，3=高。
var screenSizePattern = regexp.MustCompile(`^(Physical|Override)\s+size:\s*(\d+)x(\d+)$`)

const (
	// longPressMinMS 与 longPressMaxMS 约束长按时长。
	// 下限取 300ms 与协议层 uiLongClick 的 minimum 一致：低于系统长按阈值
	// （ViewConfiguration 默认 500ms，各 ROM 有差异）会被识别成普通点击，
	// 那样动作看似成功却没有长按效果。
	longPressMinMS = 300
	longPressMaxMS = 10000

	// scrollMinFraction 与 scrollMaxFraction 约束单次滚动的屏幕占比。
	// 上限 0.9 而非 1.0：位移覆盖整屏时起点或终点会落在屏幕边缘，
	// 容易被系统手势（返回、通知栏、最近任务）截走。
	scrollMinFraction = 0.05
	scrollMaxFraction = 0.9

	// scrollDurationMS 是滚动手势的时长。
	// 取 300ms：过快会被识别为 fling 而产生不可预期的惯性滚动，
	// 使后续 uiStable 判据难以成立；过慢则整体耗时增加。
	scrollDurationMS = 300
)

// ScrollDirection 是受支持的滚动方向。
type ScrollDirection string

// 滚动方向取值。与协议层 uiScroll 的 direction 枚举保持一致的前四项；
// 协议还有 forward/backward，那两个依赖元素的可滚动语义，直连侧无法判定，故不提供。
const (
	ScrollUp    ScrollDirection = "up"
	ScrollDown  ScrollDirection = "down"
	ScrollLeft  ScrollDirection = "left"
	ScrollRight ScrollDirection = "right"
)

// screenSize 读取设备的物理分辨率。
//
// 解析 `wm size` 的输出，实测格式为 `Physical size: 320x640`（API 35 模拟器）。
// 设备被 `wm size WxH` 覆写过时该命令会额外输出一行 `Override size: WxH`，
// 此时应以 Override 为准——手势坐标必须落在实际生效的坐标空间里。
func (c *Client) screenSize(ctx context.Context, serial string) (int, int, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return 0, 0, err
	}
	result, err := c.run(ctx, []string{"-s", serial, "shell", "wm", "size"},
		process.Options{Timeout: 15 * time.Second, MaxOutput: 64 * 1024})
	if err != nil {
		return 0, 0, err
	}

	physicalWidth, physicalHeight := 0, 0
	overrideWidth, overrideHeight := 0, 0
	for _, line := range strings.Split(string(result.Stdout), "\n") {
		match := screenSizePattern.FindStringSubmatch(strings.TrimSpace(line))
		if match == nil {
			continue
		}
		width, widthErr := strconv.Atoi(match[2])
		height, heightErr := strconv.Atoi(match[3])
		if widthErr != nil || heightErr != nil || width <= 0 || height <= 0 {
			continue
		}
		if strings.EqualFold(match[1], "Override") {
			overrideWidth, overrideHeight = width, height
		} else {
			physicalWidth, physicalHeight = width, height
		}
	}

	if overrideWidth > 0 {
		return overrideWidth, overrideHeight, nil
	}
	if physicalWidth > 0 {
		return physicalWidth, physicalHeight, nil
	}
	return 0, 0, apperr.New(
		apperr.CodeActionFailed,
		"The device screen size could not be read from ADB output.",
		true,
		map[string]any{"device": serial},
	)
}

// LongPress 在指定坐标按住指定时长。
//
// 实现为起点与终点相同的 swipe：`input swipe x y x y duration` 是 ADB 表达
// "按住不动"的唯一方式，没有专门的长按命令。
func (c *Client) LongPress(
	ctx context.Context,
	serial string,
	x, y, durationMS int,
) (ActionResult, error) {
	if err := validatePoint(x, y); err != nil {
		return ActionResult{}, err
	}
	if durationMS < longPressMinMS || durationMS > longPressMaxMS {
		return ActionResult{}, invalidField(
			"durationMs",
			fmt.Sprintf("must be between %d and %d", longPressMinMS, longPressMaxMS),
		)
	}
	return c.inputAction(
		ctx, serial, "ui.longClick", "swipe",
		strconv.Itoa(x), strconv.Itoa(y),
		strconv.Itoa(x), strconv.Itoa(y),
		strconv.Itoa(durationMS),
	)
}

// Scroll 从屏幕中心按方向滚动指定屏幕占比。
//
// 方向语义按"内容移动方向"定义：direction=down 表示内容向下走，
// 即手指从上往下滑，看到的是上方内容。这与用户对"向下滚动列表"的直觉相反，
// 因此调用方文档必须写清；这里选择与 Android 手势的物理方向一致，
// 而不是与"滚动条位置"一致，因为后者在横向列表与 RTL 布局下会产生歧义。
//
// 需要屏幕尺寸才能把占比换算成坐标，因此内部会先查一次设备分辨率。
func (c *Client) Scroll(
	ctx context.Context,
	serial string,
	direction ScrollDirection,
	fraction float64,
) (ActionResult, error) {
	switch direction {
	case ScrollUp, ScrollDown, ScrollLeft, ScrollRight:
	default:
		return ActionResult{}, invalidField(
			"direction", "must be up, down, left, or right",
		)
	}
	if fraction < scrollMinFraction || fraction > scrollMaxFraction {
		return ActionResult{}, invalidField(
			"amount",
			fmt.Sprintf("must be between %.2f and %.2f", scrollMinFraction, scrollMaxFraction),
		)
	}

	width, height, err := c.screenSize(ctx, serial)
	if err != nil {
		return ActionResult{}, err
	}

	centerX, centerY := width/2, height/2
	// 位移取该轴长度的一半乘以占比：fraction=0.9 时位移为轴长的 45%，
	// 起点与终点各距中心 45%，两端都还留在屏幕内。
	deltaX, deltaY := 0, 0
	switch direction {
	case ScrollUp:
		deltaY = -int(float64(height) * fraction / 2)
	case ScrollDown:
		deltaY = int(float64(height) * fraction / 2)
	case ScrollLeft:
		deltaX = -int(float64(width) * fraction / 2)
	case ScrollRight:
		deltaX = int(float64(width) * fraction / 2)
	}

	startX, startY := centerX-deltaX, centerY-deltaY
	endX, endY := centerX+deltaX, centerY+deltaY

	return c.inputAction(
		ctx, serial, "ui.scroll", "swipe",
		strconv.Itoa(startX), strconv.Itoa(startY),
		strconv.Itoa(endX), strconv.Itoa(endY),
		strconv.Itoa(scrollDurationMS),
	)
}
