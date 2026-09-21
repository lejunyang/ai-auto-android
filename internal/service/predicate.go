package service

// 功能用途：本文件在共享服务层实现界面等待与断言的参数校验与路由，
// 使 CLI 与 MCP 通过同一套判据语义执行 ui.wait 与 ui.assert。
//
// wait 与 assert 共用同一个求值器，差别只在失败语义：
// wait 把"判据未成立"当作正常结果返回，assert 则视为错误。
// 这个区分是刻意的——自动化脚本里"等不到"通常要继续尝试其它路径，
// 而"断言不成立"必须让调用方知道当前状态与预期不符。

import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

// PredicateBackend 是等待与断言所需的后端能力。
//
// 与 ObserveExtraBackend 分开的理由相同：只有 adb 直连后端能提供该能力，
// 分开后可以在缺失时给出明确的"能力不可用"错误。
// defaultScrollUntilRounds 是 scrollUntil 未指定次数时的滚动上限。
// 取 20：足够多数长列表，同时远低于协议上限 50。滚动占比复用已有的
// defaultScrollFraction（见 service.go）。
const defaultScrollUntilRounds = 20

type PredicateBackend interface {
	AwaitPredicate(context.Context, string, adb.Predicate) (adb.PredicateResult, error)
	// ScrollUntil 沿一个方向反复滚动直到判据成立或达到 maxScrolls。
	ScrollUntil(
		ctx context.Context,
		serial string,
		direction adb.ScrollDirection,
		fraction float64,
		maxScrolls int,
		predicate adb.Predicate,
	) (adb.PredicateResult, error)
}

// PredicateRequest 描述一次等待或断言。
type PredicateRequest struct {
	Device   string `json:"device"`
	Kind     string `json:"kind"`
	Operator string `json:"operator"`
	// 选择器字段，仅 kind 为 node/text 时使用。
	ResourceID  string `json:"resourceId,omitempty"`
	Text        string `json:"text,omitempty"`
	Description string `json:"description,omitempty"`
	ClassName   string `json:"className,omitempty"`
	Package     string `json:"package,omitempty"`

	Expected         string `json:"expected,omitempty"`
	TimeoutMS        int    `json:"timeoutMs,omitempty"`
	StableDurationMS int    `json:"stableDurationMs,omitempty"`
}

// PredicateOutcome 是一次等待或断言的结果。
type PredicateOutcome struct {
	Device        string `json:"device"`
	Kind          string `json:"kind"`
	Operator      string `json:"operator"`
	Satisfied     bool   `json:"satisfied"`
	WaitedMS      int    `json:"waitedMs"`
	Polls         int    `json:"polls"`
	MatchCount    int    `json:"matchCount"`
	ActualText    string `json:"actualText,omitempty"`
	ActualPackage string `json:"actualPackage,omitempty"`
}

// predicateBackend 返回可用的判据后端，或说明其不可用。
func (s *Service) predicateBackend() (PredicateBackend, error) {
	backend, ok := s.direct.(PredicateBackend)
	if !ok || s.direct == nil {
		return nil, unavailable("interface predicates")
	}
	return backend, nil
}

// toPredicate 把请求转换为后端判据。
func (request PredicateRequest) toPredicate() adb.Predicate {
	return adb.Predicate{
		Kind:     request.Kind,
		Operator: request.Operator,
		Selector: adb.NodeSelector{
			ResourceID:  request.ResourceID,
			Text:        request.Text,
			Description: request.Description,
			ClassName:   request.ClassName,
			Package:     request.Package,
		},
		Expected:         request.Expected,
		TimeoutMS:        request.TimeoutMS,
		StableDurationMS: request.StableDurationMS,
	}
}

// evaluate 执行判据求值，供 Wait 与 Assert 共用。
func (s *Service) evaluate(
	ctx context.Context,
	request PredicateRequest,
) (PredicateOutcome, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return PredicateOutcome{}, err
	}
	backend, err := s.predicateBackend()
	if err != nil {
		return PredicateOutcome{}, err
	}
	result, err := backend.AwaitPredicate(ctx, request.Device, request.toPredicate())
	if err != nil {
		return PredicateOutcome{}, err
	}
	return PredicateOutcome{
		Device:        result.Device,
		Kind:          result.Kind,
		Operator:      request.Operator,
		Satisfied:     result.Satisfied,
		WaitedMS:      result.WaitedMS,
		Polls:         result.Polls,
		MatchCount:    result.MatchCount,
		ActualText:    result.ActualText,
		ActualPackage: result.ActualPackage,
	}, nil
}

// Wait 等待判据成立，超时后返回 Satisfied=false 而不报错。
func (s *Service) Wait(
	ctx context.Context,
	request PredicateRequest,
) (PredicateOutcome, error) {
	return s.evaluate(ctx, request)
}

// Assert 要求判据成立，不成立时返回错误。
//
// 错误载荷带上观察到的实际值（匹配数、文本、包名），
// 否则调用方只知道"断言失败"却不知道当前界面是什么状态，无法据此纠正。
func (s *Service) Assert(
	ctx context.Context,
	request PredicateRequest,
) (PredicateOutcome, error) {
	outcome, err := s.evaluate(ctx, request)
	if err != nil {
		return PredicateOutcome{}, err
	}
	if outcome.Satisfied {
		return outcome, nil
	}
	details := map[string]any{
		"device":     outcome.Device,
		"kind":       outcome.Kind,
		"operator":   outcome.Operator,
		"waitedMs":   outcome.WaitedMS,
		"polls":      outcome.Polls,
		"matchCount": outcome.MatchCount,
	}
	if outcome.ActualText != "" {
		details["actualText"] = outcome.ActualText
	}
	if outcome.ActualPackage != "" {
		details["actualPackage"] = outcome.ActualPackage
	}
	// retryable 为 true：界面可能仍在变化，调用方重试或改走其它路径都是合理反应。
	//
	// 复用既有 ACTION_FAILED 而不新增错误码：错误码是协议层枚举
	// （protocol/schema/v1/error.schema.json），新增会扩大稳定协议面，
	// 而断言不成立本质就是"动作未达到预期效果"，既有语义已覆盖。
	// 调用方需要区分时看 details 里的 kind 与 operator。
	return outcome, apperr.New(
		apperr.CodeActionFailed,
		"The interface assertion did not hold before the timeout.",
		true,
		details,
	)
}

// ScrollUntilRequest 描述一次「滚动直到判据成立」。
//
// 内嵌 PredicateRequest：until 判据复用与 Wait/Assert 完全相同的选择器与运算符
// 语义，不另立一套。Direction/Amount/MaxScrolls 是滚动侧参数。
type ScrollUntilRequest struct {
	Until      PredicateRequest `json:"until"`
	Direction  string           `json:"direction"`
	Amount     float64          `json:"amount,omitempty"`
	MaxScrolls int              `json:"maxScrolls,omitempty"`
}

// ScrollUntil 沿一个方向滚动直到 until 判据成立或滚够 maxScrolls。
//
// 与 Wait 一样，判据未成立返回 Satisfied=false 而非错误——调用方需要区分
// 「滚完还没出现」与「无法观察界面」。
func (s *Service) ScrollUntil(
	ctx context.Context,
	request ScrollUntilRequest,
) (PredicateOutcome, error) {
	if err := adb.ValidateSerial(request.Until.Device); err != nil {
		return PredicateOutcome{}, err
	}
	backend, err := s.predicateBackend()
	if err != nil {
		return PredicateOutcome{}, err
	}
	fraction := request.Amount
	if fraction == 0 {
		fraction = defaultScrollFraction
	}
	maxScrolls := request.MaxScrolls
	if maxScrolls == 0 {
		maxScrolls = defaultScrollUntilRounds
	}
	result, err := backend.ScrollUntil(
		ctx,
		request.Until.Device,
		adb.ScrollDirection(request.Direction),
		fraction,
		maxScrolls,
		request.Until.toPredicate(),
	)
	if err != nil {
		return PredicateOutcome{}, err
	}
	return PredicateOutcome{
		Device:        result.Device,
		Kind:          result.Kind,
		Operator:      request.Until.Operator,
		Satisfied:     result.Satisfied,
		WaitedMS:      result.WaitedMS,
		Polls:         result.Polls,
		MatchCount:    result.MatchCount,
		ActualText:    result.ActualText,
		ActualPackage: result.ActualPackage,
	}, nil
}
