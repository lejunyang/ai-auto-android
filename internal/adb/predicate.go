package adb

// 功能用途：本文件实现直连模式下的界面等待与断言求值，判据取自 UIAutomator 层级快照。
//
// 与 App Bridge 侧 ReplayEngine.await 保持同一套语义（kind / operator 取值与含义一致），
// 但数据源不同：桥侧读无障碍节点树，这里解析 uiautomator dump 的 XML。
// 因此两侧能力对齐而不互相依赖——直连模式无需安装任何设备端组件即可做自愈判断。
//
// 刻意不支持协议层 target 的坐标类字段（recordedBounds / relativePoint /
// normalizedScreenPoint）：这里只按可描述属性匹配。坐标匹配在页面重排后会静默命中
// 错误元素，而断言的价值恰恰在于捕获这种偏离。

import (
	"context"
	"encoding/xml"
	"regexp"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

const (
	// 判据种类。与协议 predicate.kind 枚举一致。
	PredicateNode     = "node"
	PredicateText     = "text"
	PredicatePackage  = "package"
	PredicateWindow   = "window"
	PredicateUIStable = "uiStable"

	// 判据运算符。与协议 predicate.operator 枚举一致。
	OperatorExists    = "exists"
	OperatorNotExists = "notExists"
	OperatorEquals    = "equals"
	OperatorContains  = "contains"
	OperatorMatches   = "matches"
	OperatorStable    = "stable"

	// defaultPredicateTimeoutMS 是未指定超时时的等待上限。
	defaultPredicateTimeoutMS = 5000
	// maxPredicateTimeoutMS 限制单次等待时长，避免调用方无意中长时间阻塞。
	maxPredicateTimeoutMS = 60000
	// defaultStableDurationMS 与桥侧 DEFAULT_STABLE_DURATION_MS 取值一致。
	defaultStableDurationMS = 300
	// predicatePollIntervalMS 是两次采样之间的间隔。
	// uiautomator dump 单次实测数百毫秒，间隔取 400ms 避免采样比 dump 还快。
	predicatePollIntervalMS = 400
)

// Predicate 描述一个界面判据。
//
// Selector 只接受可描述属性；不提供坐标字段，理由见文件头注释。
type Predicate struct {
	Kind     string
	Operator string
	// Selector 在 kind 为 node/text 时用于定位元素。
	Selector NodeSelector
	// Expected 是 equals/contains/matches 的比较值。
	Expected string
	// TimeoutMS 为 0 时使用 defaultPredicateTimeoutMS。
	TimeoutMS int
	// StableDurationMS 仅 kind=uiStable 时有效，为 0 时用默认值。
	StableDurationMS int
}

// NodeSelector 按可描述属性定位元素，字段之间是「与」关系。
//
// 全部字段为空时视为非法：那会匹配到界面上的任意元素，
// 让 exists 永远为真、notExists 永远为假，断言失去意义。
type NodeSelector struct {
	ResourceID  string
	Text        string
	Description string
	ClassName   string
	Package     string
}

// isEmpty 报告选择器是否没有任何约束。
func (s NodeSelector) isEmpty() bool {
	return s.ResourceID == "" && s.Text == "" && s.Description == "" &&
		s.ClassName == "" && s.Package == ""
}

// PredicateResult 是一次等待或断言的结果。
type PredicateResult struct {
	Device string `json:"device"`
	Kind   string `json:"kind"`
	// Satisfied 说明判据在超时前是否成立。
	Satisfied bool `json:"satisfied"`
	// WaitedMS 是实际等待时长，便于判断是"立刻成立"还是"等到最后一刻才成立"。
	WaitedMS int `json:"waitedMs"`
	// Polls 是采样次数，用于诊断超时是采样太慢还是判据真的不成立。
	Polls int `json:"polls"`
	// MatchCount 是 kind=node/text 时匹配到的元素数量。
	// 大于 1 表示选择器不唯一——此时 exists 成立但动作会有歧义风险。
	MatchCount int `json:"matchCount"`
	// ActualText 是首个匹配元素的文本，仅在文本类比较失败时用于解释原因。
	ActualText string `json:"actualText,omitempty"`
	// ActualPackage 是 kind=package/window 时观察到的实际包名。
	ActualPackage string `json:"actualPackage,omitempty"`
}

// uiNode 是 uiautomator dump XML 的节点结构。
type uiNode struct {
	ResourceID  string   `xml:"resource-id,attr"`
	Text        string   `xml:"text,attr"`
	Description string   `xml:"content-desc,attr"`
	Class       string   `xml:"class,attr"`
	Package     string   `xml:"package,attr"`
	Bounds      string   `xml:"bounds,attr"`
	Children    []uiNode `xml:"node"`
}

// uiHierarchy 是 XML 根元素。
type uiHierarchy struct {
	Nodes []uiNode `xml:"node"`
}

// AwaitPredicate 轮询界面直到判据成立或超时。
//
// 返回值的 Satisfied 为 false 不是错误：调用方需要区分"判据不成立"与
// "无法观察界面"，前者是正常的自愈信号，后者才是故障。因此只有 dump 失败
// 才返回 error。
func (c *Client) AwaitPredicate(
	ctx context.Context,
	serial string,
	predicate Predicate,
) (PredicateResult, error) {
	if err := validatePredicate(predicate); err != nil {
		return PredicateResult{}, err
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return PredicateResult{}, err
	}

	timeout := predicate.TimeoutMS
	if timeout == 0 {
		timeout = defaultPredicateTimeoutMS
	}
	stableDuration := predicate.StableDurationMS
	if stableDuration == 0 {
		stableDuration = defaultStableDurationMS
	}

	started := time.Now()
	deadline := started.Add(time.Duration(timeout) * time.Millisecond)
	result := PredicateResult{Device: serial, Kind: predicate.Kind}

	var stableSince time.Time
	previousFingerprint := ""

	for {
		hierarchy, err := c.Hierarchy(ctx, serial)
		if err != nil {
			// 观察失败与判据不成立是两件事，如实上报前者。
			return PredicateResult{}, err
		}
		result.Polls++

		root, parseErr := parseHierarchy(hierarchy.XML)
		if parseErr != nil {
			return PredicateResult{}, parseErr
		}

		satisfied := false
		switch predicate.Kind {
		case PredicateNode, PredicateText:
			matches := matchNodes(root, predicate.Selector)
			result.MatchCount = len(matches)
			if len(matches) > 0 {
				result.ActualText = matches[0].Text
			}
			satisfied = evaluateNodePredicate(matches, predicate)

		case PredicatePackage, PredicateWindow:
			actual := rootPackage(root)
			result.ActualPackage = actual
			satisfied = evaluateTextComparison(actual, predicate.Operator, predicate.Expected)

		case PredicateUIStable:
			current := fingerprint(root)
			if current == previousFingerprint {
				if stableSince.IsZero() {
					stableSince = time.Now()
				}
			} else {
				stableSince = time.Time{}
			}
			previousFingerprint = current
			if !stableSince.IsZero() {
				elapsed := time.Since(stableSince).Milliseconds()
				satisfied = elapsed >= int64(stableDuration)
			}
		}

		if satisfied {
			result.Satisfied = true
			result.WaitedMS = int(time.Since(started).Milliseconds())
			return result, nil
		}
		if time.Now().After(deadline) {
			result.WaitedMS = int(time.Since(started).Milliseconds())
			return result, nil
		}

		remaining := time.Until(deadline)
		interval := time.Duration(predicatePollIntervalMS) * time.Millisecond
		if remaining < interval {
			interval = remaining
		}
		if interval > 0 {
			select {
			case <-ctx.Done():
				return PredicateResult{}, apperr.Wrap(
					apperr.CodeActionFailed,
					"Waiting for the predicate was cancelled.",
					true,
					ctx.Err(),
				)
			case <-time.After(interval):
			}
		}
	}
}

// validatePredicate 校验判据的种类、运算符与必填组合。
func validatePredicate(predicate Predicate) error {
	switch predicate.Kind {
	case PredicateNode, PredicateText, PredicatePackage, PredicateWindow, PredicateUIStable:
	default:
		return invalidField("kind",
			"must be node, text, package, window, or uiStable")
	}
	switch predicate.Operator {
	case OperatorExists, OperatorNotExists, OperatorEquals,
		OperatorContains, OperatorMatches, OperatorStable:
	default:
		return invalidField("operator",
			"must be exists, notExists, equals, contains, matches, or stable")
	}
	if predicate.TimeoutMS < 0 || predicate.TimeoutMS > maxPredicateTimeoutMS {
		return invalidField("timeoutMs", "must be between 0 and 60000")
	}
	if predicate.StableDurationMS < 0 || predicate.StableDurationMS > 30000 {
		return invalidField("stableDurationMs", "must be between 0 and 30000")
	}

	switch predicate.Kind {
	case PredicateNode, PredicateText:
		// 空选择器会匹配任意元素，使 exists 恒真、notExists 恒假，断言失去意义。
		if predicate.Selector.isEmpty() {
			return invalidField("selector",
				"must constrain at least one of resourceId, text, description, className, package")
		}
		if predicate.Operator == OperatorStable {
			return invalidField("operator",
				"stable applies to uiStable predicates, not node or text")
		}
	case PredicatePackage, PredicateWindow:
		if predicate.Operator == OperatorExists ||
			predicate.Operator == OperatorNotExists ||
			predicate.Operator == OperatorStable {
			return invalidField("operator",
				"package and window predicates compare text with equals, contains, or matches")
		}
		if predicate.Expected == "" {
			return invalidField("expected",
				"is required when comparing the foreground package")
		}
	case PredicateUIStable:
		if predicate.Operator != OperatorStable {
			return invalidField("operator", "uiStable predicates require the stable operator")
		}
	}

	if predicate.Operator == OperatorMatches {
		if _, err := regexp.Compile(predicate.Expected); err != nil {
			return invalidField("expected", "must be a valid regular expression")
		}
	}
	return nil
}

// parseHierarchy 解析 uiautomator dump 的 XML。
func parseHierarchy(document string) (uiHierarchy, error) {
	var hierarchy uiHierarchy
	if err := xml.Unmarshal([]byte(document), &hierarchy); err != nil {
		return uiHierarchy{}, apperr.Wrap(
			apperr.CodeActionFailed,
			"The UI hierarchy returned by ADB could not be parsed.",
			true,
			err,
		)
	}
	return hierarchy, nil
}

// matchNodes 返回所有满足选择器的节点，按文档顺序。
func matchNodes(hierarchy uiHierarchy, selector NodeSelector) []uiNode {
	matched := make([]uiNode, 0, 4)
	var walk func(nodes []uiNode)
	walk = func(nodes []uiNode) {
		for _, node := range nodes {
			if selectorMatches(node, selector) {
				matched = append(matched, node)
			}
			walk(node.Children)
		}
	}
	walk(hierarchy.Nodes)
	return matched
}

// selectorMatches 判断单个节点是否满足选择器的全部非空约束。
//
// 文本与描述用「包含」而非「相等」：uiautomator 的 text 常带前后空白或省略号，
// 要求精确相等会让大量本该命中的元素落空。resourceId 与 className 用相等，
// 它们是结构性标识，不应模糊匹配。
func selectorMatches(node uiNode, selector NodeSelector) bool {
	if selector.ResourceID != "" && node.ResourceID != selector.ResourceID {
		return false
	}
	if selector.ClassName != "" && node.Class != selector.ClassName {
		return false
	}
	if selector.Package != "" && node.Package != selector.Package {
		return false
	}
	if selector.Text != "" && !strings.Contains(node.Text, selector.Text) {
		return false
	}
	if selector.Description != "" &&
		!strings.Contains(node.Description, selector.Description) {
		return false
	}
	return true
}

// evaluateNodePredicate 按运算符判断节点类判据。
func evaluateNodePredicate(matches []uiNode, predicate Predicate) bool {
	found := len(matches) > 0
	switch predicate.Operator {
	case OperatorExists:
		return found
	case OperatorNotExists:
		return !found
	case OperatorEquals, OperatorContains, OperatorMatches:
		if !found {
			return false
		}
		return evaluateTextComparison(matches[0].Text, predicate.Operator, predicate.Expected)
	}
	return false
}

// evaluateTextComparison 按运算符比较实际值与期望值。
func evaluateTextComparison(actual, operator, expected string) bool {
	switch operator {
	case OperatorEquals:
		return actual == expected
	case OperatorContains:
		return strings.Contains(actual, expected)
	case OperatorMatches:
		pattern, err := regexp.Compile(expected)
		if err != nil {
			// 校验阶段已编译过一次；这里再失败只可能是并发修改，保守判为不成立。
			return false
		}
		return pattern.MatchString(actual)
	}
	return false
}

// rootPackage 返回层级中最外层节点的包名。
func rootPackage(hierarchy uiHierarchy) string {
	for _, node := range hierarchy.Nodes {
		if node.Package != "" {
			return node.Package
		}
		if inner := rootPackage(uiHierarchy{Nodes: node.Children}); inner != "" {
			return inner
		}
	}
	return ""
}

// fingerprint 生成界面结构指纹，用于 uiStable 判据。
//
// 与桥侧 snapshotFingerprint 取同样的字段组合（包名、类名、资源 id、文本），
// 刻意不含 bounds：滚动惯性结束前 bounds 会持续微动，纳入后 uiStable 几乎不可能成立。
func fingerprint(hierarchy uiHierarchy) string {
	var builder strings.Builder
	var appendNode func(node uiNode)
	appendNode = func(node uiNode) {
		builder.WriteString(node.Package)
		builder.WriteByte('|')
		builder.WriteString(node.Class)
		builder.WriteByte('|')
		builder.WriteString(node.ResourceID)
		builder.WriteByte('|')
		builder.WriteString(node.Text)
		builder.WriteByte(';')
		for _, child := range node.Children {
			appendNode(child)
		}
	}
	for _, node := range hierarchy.Nodes {
		appendNode(node)
	}
	return builder.String()
}
