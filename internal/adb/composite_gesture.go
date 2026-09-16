package adb

// 功能用途：本文件在直连通道实现拖拽、连击与单指路径手势。
//
// 本文件的形态由一次实测结论决定，而不是文档推断：
//
// 1. `input motionevent <DOWN|UP|MOVE|CANCEL> <x> <y>` 只接受**一个**坐标对。
//    实测（API 35 模拟器 emulator-5554）传入第三个参数时命令**静默忽略**它并
//    返回 exit 0——连 `input motionevent DOWN 100 300 NOTANUMBER` 都「成功」。
//    因此**不存在**通过 `input` 合成多指手势的途径：任何这样写的代码都会
//    退化成单指，且不会报错。多指手势（pinch / zoom / 旋转 / 双指滚动）
//    只能走 App Bridge 的无障碍 dispatchGesture 多 stroke 通道，
//    见 AndroidAccessibilityBackend.dispatchMultiStroke。
//
// 2. `input draganddrop <x1> <y1> <x2> <y2> [duration]` 是平台自带的拖拽原语。
//    它在 DOWN 之后 sleep(ViewConfiguration.getLongPressTimeout()) 再开始移动，
//    正是「按住—停顿—移动」的语义。用它比自己拼 motionevent 更可靠：
//    按住时长取的是运行设备的真实长按阈值，而不是我们猜的常量；
//    整个手势在设备侧一次完成，不受 adb 往返抖动影响。
//
// 3. 单指多点折线路径必须自己用 motionevent 拼：`input swipe` 只支持两点。
//
// 与桥侧的分工不变：这里只接受坐标，元素级定位仍然只在 App Bridge 侧。

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

const (
	dragMoveMinMS = 1
	dragMoveMaxMS = 10000

	multiTapMin = 2
	multiTapMax = 5
	// 连击间隔取 80ms。系统多击超时默认 300ms，贴着上限会在慢设备上被拆成
	// 两次独立单击；80ms 留出充足余量。
	multiTapIntervalMS = 80
	multiTapDurationMS = 60

	pathPointsMin = 2
	pathPointsMax = 32
	pathDurMinMS  = 1
	pathDurMaxMS  = 10000
	// motionSegmentSteps 是每段位移离散化成的 MOVE 事件数。
	// 取 8 而非更多：每个 motionevent 都是一次独立的 adb 往返（实测约
	// 30-60ms），步数越多手势总时长越不可控。8 步已足以让手势识别器
	// 看到连续轨迹。
	motionSegmentSteps = 8
)

// Point 是直连手势使用的屏幕像素坐标。
type Point struct {
	X int
	Y int
}

// Drag 在起点按住后拖动到终点。
//
// 走平台自带的 `input draganddrop`，它在 DOWN 之后按运行设备的真实长按阈值
// 停顿再移动。因此本方法**不接受**自定义按住时长：那个值由设备的
// ViewConfiguration 决定，我们给一个猜测值只会更差。
//
// durationMS 是移动阶段的时长，不含按住阶段。
func (c *Client) Drag(
	ctx context.Context,
	serial string,
	start, end Point,
	durationMS int,
) (ActionResult, error) {
	if err := validatePoint(start.X, start.Y); err != nil {
		return ActionResult{}, err
	}
	if err := validatePoint(end.X, end.Y); err != nil {
		return ActionResult{}, err
	}
	if durationMS < dragMoveMinMS || durationMS > dragMoveMaxMS {
		return ActionResult{}, invalidField(
			"durationMs",
			fmt.Sprintf("must be between %d and %d", dragMoveMinMS, dragMoveMaxMS),
		)
	}
	return c.inputAction(
		ctx, serial, "ui.drag", "draganddrop",
		strconv.Itoa(start.X), strconv.Itoa(start.Y),
		strconv.Itoa(end.X), strconv.Itoa(end.Y),
		strconv.Itoa(durationMS),
	)
}

// MultiTap 在同一坐标连续点击指定次数。
//
// 每次点击是完整的 DOWN/UP 对：连击的语义正是「抬手再落下」，
// 用一次长按代替会得到完全不同的行为。间隔用可中断的等待控制在系统
// 多击窗口内。
func (c *Client) MultiTap(
	ctx context.Context,
	serial string,
	point Point,
	taps int,
) (ActionResult, error) {
	if err := validatePoint(point.X, point.Y); err != nil {
		return ActionResult{}, err
	}
	if taps < multiTapMin || taps > multiTapMax {
		return ActionResult{}, invalidField(
			"taps",
			fmt.Sprintf("must be between %d and %d", multiTapMin, multiTapMax),
		)
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}
	for index := 0; index < taps; index++ {
		if index > 0 {
			if err := sleepContext(
				ctx, time.Duration(multiTapIntervalMS)*time.Millisecond,
			); err != nil {
				return ActionResult{}, err
			}
		}
		if err := c.motionEvent(ctx, serial, "DOWN", point); err != nil {
			return ActionResult{}, err
		}
		if err := sleepContext(
			ctx, time.Duration(multiTapDurationMS)*time.Millisecond,
		); err != nil {
			// 已经 DOWN 但还没 UP：必须补一次 UP，否则触摸序列悬空，
			// 后续任何手势都会被系统当成同一次触摸的延续。
			_ = c.motionEvent(context.WithoutCancel(ctx), serial, "UP", point)
			return ActionResult{}, err
		}
		if err := c.motionEvent(ctx, serial, "UP", point); err != nil {
			return ActionResult{}, err
		}
	}
	return ActionResult{Device: serial, Action: "ui.multiTap"}, nil
}

// PathGesture 单指沿多点折线连续滑动，中途不抬手（图案解锁等）。
//
// 必须逐点注入 motionevent：`input swipe` 只支持起点与终点两个点，
// 无法表达图案解锁那种需要精确经过若干中间点的轨迹。
func (c *Client) PathGesture(
	ctx context.Context,
	serial string,
	points []Point,
	durationMS int,
) (ActionResult, error) {
	if len(points) < pathPointsMin || len(points) > pathPointsMax {
		return ActionResult{}, invalidField(
			"points",
			fmt.Sprintf("must contain between %d and %d points", pathPointsMin, pathPointsMax),
		)
	}
	for _, point := range points {
		if err := validatePoint(point.X, point.Y); err != nil {
			return ActionResult{}, err
		}
	}
	if durationMS < pathDurMinMS || durationMS > pathDurMaxMS {
		return ActionResult{}, invalidField(
			"durationMs",
			fmt.Sprintf("must be between %d and %d", pathDurMinMS, pathDurMaxMS),
		)
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return ActionResult{}, err
	}

	if err := c.motionEvent(ctx, serial, "DOWN", points[0]); err != nil {
		return ActionResult{}, err
	}
	// 从这里起触摸序列已经打开：任何提前返回都必须补 UP。
	segments := len(points) - 1
	perStep := time.Duration(durationMS/(segments*motionSegmentSteps)) * time.Millisecond
	for index := 0; index < segments; index++ {
		from, to := points[index], points[index+1]
		for step := 1; step <= motionSegmentSteps; step++ {
			moveTo := interpolate(from, to, step, motionSegmentSteps)
			if err := c.motionEvent(ctx, serial, "MOVE", moveTo); err != nil {
				_ = c.motionEvent(context.WithoutCancel(ctx), serial, "UP", moveTo)
				return ActionResult{}, err
			}
			if err := sleepContext(ctx, perStep); err != nil {
				_ = c.motionEvent(context.WithoutCancel(ctx), serial, "UP", moveTo)
				return ActionResult{}, err
			}
		}
	}
	if err := c.motionEvent(ctx, serial, "UP", points[len(points)-1]); err != nil {
		return ActionResult{}, err
	}
	return ActionResult{Device: serial, Action: "ui.pathGesture"}, nil
}

// motionEvent 注入一个单指 MotionEvent。
//
// 注意 `input motionevent` 只接受一个坐标对，多余参数会被静默忽略。
// 因此这里刻意不提供「多坐标」的变体——那种写法看起来能编译、运行不报错，
// 实际只有第一个坐标生效，是最难发现的一类缺陷。
func (c *Client) motionEvent(
	ctx context.Context,
	serial, action string,
	point Point,
) error {
	return c.runAction(ctx, serial, []string{
		"-s", serial, "shell", "input", "motionevent",
		action, strconv.Itoa(point.X), strconv.Itoa(point.Y),
	}, process.Options{Timeout: 10 * time.Second})
}

// interpolate 在两点之间取第 step/steps 个插值点。
func interpolate(from, to Point, step, steps int) Point {
	return Point{
		X: from.X + (to.X-from.X)*step/steps,
		Y: from.Y + (to.Y-from.Y)*step/steps,
	}
}

// sleepContext 按上下文可中断地等待。
//
// 不能直接用 time.Sleep：手势分段可能累计数秒，调用方取消后应立即停止
// 而不是等到整个手势跑完。
func sleepContext(ctx context.Context, duration time.Duration) error {
	if duration <= 0 {
		return nil
	}
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return apperr.Wrap(
			apperr.CodeActionFailed,
			"The gesture was cancelled before it completed.",
			false,
			ctx.Err(),
		)
	case <-timer.C:
		return nil
	}
}
