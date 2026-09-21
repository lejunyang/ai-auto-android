package cli

// 功能用途：本文件解析界面等待与断言的命令行参数，并把结果路由到共享服务层。
//
// 独立成 `aactl check wait|assert` 而不并入 observe 或 action：
// 它既不是纯观察（会阻塞轮询直到超时），也不是动作（不改变设备状态）。
// 混进任一侧都会让那一侧的超时预期与副作用语义变得不一致。

import (
	"context"
	"strconv"

	"github.com/lejunyang/ai-auto-android/internal/service"
)

// executeCheck 处理 wait 与 assert 两个子命令。
func (a *App) executeCheck(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl check wait|assert|scroll-until [options]")
	}

	switch args[0] {
	case "wait", "assert":
		request, err := parsePredicateOptions(args[1:])
		if err != nil {
			return nil, err
		}
		if args[0] == "wait" {
			return automation.Wait(ctx, request)
		}
		return automation.Assert(ctx, request)
	case "scroll-until":
		// 沿一个方向滚动直到判据成立。until 判据复用 wait/assert 的同一套选择器
		// 与运算符语义，额外接受 direction/amount/max-scrolls 三个滚动侧参数。
		return a.executeScrollUntil(ctx, automation, args[1:])
	default:
		return nil, usageError("Unknown check command. Supported commands: wait, assert, scroll-until.")
	}
}

// parsePredicateOptions 把命令行选项解析为判据请求。
//
// 选择器字段（resource-id / text / description / class-name / package）都是可选的，
// 但服务层会拒绝全空的选择器——空选择器匹配任意元素，会让 exists 恒真、
// notExists 恒假，断言失去意义。这里不重复该校验，避免两处判据不一致。
func parsePredicateOptions(args []string) (service.PredicateRequest, error) {
	options, err := parseNamedOptions(args, optionSpec{
		allowed: optionSet(
			"device", "kind", "operator",
			"resource-id", "text", "description", "class-name", "package",
			"expected", "timeout-ms", "stable-duration-ms",
		),
		required: []string{"device", "kind", "operator"},
	})
	if err != nil {
		return service.PredicateRequest{}, err
	}

	request := service.PredicateRequest{
		Device:      options["device"],
		Kind:        options["kind"],
		Operator:    options["operator"],
		ResourceID:  options["resource-id"],
		Text:        options["text"],
		Description: options["description"],
		ClassName:   options["class-name"],
		Package:     options["package"],
		Expected:    options["expected"],
	}
	for name, target := range map[string]*int{
		"timeout-ms":         &request.TimeoutMS,
		"stable-duration-ms": &request.StableDurationMS,
	} {
		if _, present := options[name]; !present {
			continue
		}
		value, intErr := parseIntegerOption(options, name)
		if intErr != nil {
			return service.PredicateRequest{}, intErr
		}
		*target = value
	}
	return request, nil
}

// executeScrollUntil 解析滚动侧参数与 until 判据，映射到服务层 ScrollUntil。
//
// until 判据的选择器与运算符字段与 parsePredicateOptions 一致；这里额外接受
// direction（必填）、amount、max-scrolls 三个滚动侧参数。
func (a *App) executeScrollUntil(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	options, err := parseNamedOptions(args, optionSpec{
		allowed: optionSet(
			"device", "kind", "operator",
			"resource-id", "text", "description", "class-name", "package",
			"expected", "timeout-ms", "stable-duration-ms",
			"direction", "amount", "max-scrolls",
		),
		required: []string{"device", "kind", "operator", "direction"},
	})
	if err != nil {
		return nil, err
	}
	until := service.PredicateRequest{
		Device:      options["device"],
		Kind:        options["kind"],
		Operator:    options["operator"],
		ResourceID:  options["resource-id"],
		Text:        options["text"],
		Description: options["description"],
		ClassName:   options["class-name"],
		Package:     options["package"],
		Expected:    options["expected"],
	}
	for name, target := range map[string]*int{
		"timeout-ms":         &until.TimeoutMS,
		"stable-duration-ms": &until.StableDurationMS,
	} {
		if _, present := options[name]; !present {
			continue
		}
		value, intErr := parseIntegerOption(options, name)
		if intErr != nil {
			return nil, intErr
		}
		*target = value
	}
	request := service.ScrollUntilRequest{
		Until:     until,
		Direction: options["direction"],
	}
	if _, present := options["max-scrolls"]; present {
		value, intErr := parseIntegerOption(options, "max-scrolls")
		if intErr != nil {
			return nil, intErr
		}
		request.MaxScrolls = value
	}
	if raw, present := options["amount"]; present {
		amount, convErr := strconv.ParseFloat(raw, 64)
		if convErr != nil {
			return nil, usageError("Option --amount must be a number.")
		}
		request.Amount = amount
	}
	return automation.ScrollUntil(ctx, request)
}
