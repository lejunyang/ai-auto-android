package cli

// 功能用途：本文件解析界面等待与断言的命令行参数，并把结果路由到共享服务层。
//
// 独立成 `aactl check wait|assert` 而不并入 observe 或 action：
// 它既不是纯观察（会阻塞轮询直到超时），也不是动作（不改变设备状态）。
// 混进任一侧都会让那一侧的超时预期与副作用语义变得不一致。

import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/service"
)

// executeCheck 处理 wait 与 assert 两个子命令。
func (a *App) executeCheck(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError("Usage: aactl check wait|assert [options]")
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
	default:
		return nil, usageError("Unknown check command. Supported commands: wait, assert.")
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
