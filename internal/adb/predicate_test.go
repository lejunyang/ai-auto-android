package adb

// 测试用途：本文件验证界面判据的参数校验、选择器匹配与各运算符语义，
// 覆盖空选择器被拒、kind 与 operator 的组合约束以及 uiStable 指纹判据。

import (
	"context"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/process"
)

// realHierarchyXML 取自真实模拟器 uiautomator dump 的结构（属性顺序与嵌套保持一致）。
const realHierarchyXML = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.camera2" content-desc="" bounds="[0,0][320,640]">
    <node index="0" text="Remember photo locations?" resource-id="android:id/alertTitle" class="android.widget.TextView" package="com.android.camera2" content-desc="" bounds="[20,100][300,140]" />
    <node index="1" text="NEXT" resource-id="android:id/button1" class="android.widget.Button" package="com.android.camera2" content-desc="Next step" bounds="[120,580][200,620]" />
  </node>
</hierarchy>`

// predicateClient 造一个每轮都返回同一份层级的 client。
//
// 每轮消耗 4 次 ADB 调用：Hierarchy 内部先 requireOnlineDevice（查 devices），
// 再 uiautomator dump、exec-out cat，最后 rm -f 清理。
// AwaitPredicate 自己开头还会查一次 devices，因此首个结果单独给它。
// 少备一个就会在结果耗尽后拿到空的 devices 列表，报"设备不存在"而非判据失败。
func predicateClient(rounds int) (*Client, *fakeADB) {
	results := []process.Result{{Stdout: []byte(twoOnlineDevices)}}
	errors := []error{nil}
	for index := 0; index < rounds; index++ {
		results = append(results,
			process.Result{Stdout: []byte(twoOnlineDevices)}, // Hierarchy 的 devices 查询
			process.Result{Stdout: []byte("")},               // uiautomator dump
			process.Result{Stdout: []byte(realHierarchyXML)}, // exec-out cat
			process.Result{Stdout: []byte("")},               // rm -f 清理
		)
		errors = append(errors, nil, nil, nil, nil)
	}
	fake := &fakeADB{results: results, errors: errors}
	return NewClient("/fake/adb", fake), fake
}

func TestAwaitPredicateFindsExistingNode(t *testing.T) {
	client, _ := predicateClient(12)
	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:      PredicateNode,
		Operator:  OperatorExists,
		Selector:  NodeSelector{ResourceID: "android:id/button1"},
		TimeoutMS: 1000,
	})
	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("satisfied = false, want true; result = %#v", result)
	}
	if result.MatchCount != 1 {
		t.Fatalf("matchCount = %d, want 1", result.MatchCount)
	}
	if result.ActualText != "NEXT" {
		t.Fatalf("actualText = %q, want NEXT", result.ActualText)
	}
}

// notExists 在元素确实不存在时应成立，且不应等到超时才返回。
func TestAwaitPredicateNotExistsHoldsImmediately(t *testing.T) {
	client, _ := predicateClient(12)
	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:      PredicateNode,
		Operator:  OperatorNotExists,
		Selector:  NodeSelector{ResourceID: "no.such.id"},
		TimeoutMS: 1000,
	})
	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("satisfied = false, want true")
	}
	if result.Polls != 1 {
		t.Fatalf("polls = %d, want 1; notExists should hold on the first sample", result.Polls)
	}
}

// 文本比较用首个匹配元素的 text。contains 语义是"实际值包含期望值"。
func TestAwaitPredicateComparesText(t *testing.T) {
	cases := map[string]struct {
		operator string
		expected string
		want     bool
	}{
		"equals hit":    {OperatorEquals, "NEXT", true},
		"equals miss":   {OperatorEquals, "PREV", false},
		"contains hit":  {OperatorContains, "EX", true},
		"contains miss": {OperatorContains, "ZZ", false},
		"matches hit":   {OperatorMatches, "^NE.T$", true},
		"matches miss":  {OperatorMatches, "^XY", false},
	}
	for name, test := range cases {
		client, _ := predicateClient(12)
		result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
			Kind:      PredicateText,
			Operator:  test.operator,
			Selector:  NodeSelector{ResourceID: "android:id/button1"},
			Expected:  test.expected,
			TimeoutMS: 600,
		})
		if err != nil {
			t.Fatalf("%s: AwaitPredicate() error = %v", name, err)
		}
		if result.Satisfied != test.want {
			t.Fatalf("%s: satisfied = %v, want %v", name, result.Satisfied, test.want)
		}
	}
}

// 前台包名判据读最外层节点的 package。
func TestAwaitPredicateComparesForegroundPackage(t *testing.T) {
	client, _ := predicateClient(12)
	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:      PredicatePackage,
		Operator:  OperatorEquals,
		Expected:  "com.android.camera2",
		TimeoutMS: 600,
	})
	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("satisfied = false, want true")
	}
	if result.ActualPackage != "com.android.camera2" {
		t.Fatalf("actualPackage = %q", result.ActualPackage)
	}
}

// uiStable 需要连续两次采样指纹相同，因此至少两轮才可能成立。
func TestAwaitPredicateUIStableNeedsTwoIdenticalSamples(t *testing.T) {
	client, _ := predicateClient(24)
	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:             PredicateUIStable,
		Operator:         OperatorStable,
		StableDurationMS: 100,
		TimeoutMS:        4000,
	})
	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("satisfied = false, want true; result = %#v", result)
	}
	if result.Polls < 2 {
		t.Fatalf("polls = %d, want at least 2", result.Polls)
	}
}

// 超时未成立不是错误：调用方需要区分"判据不成立"与"无法观察界面"。
func TestAwaitPredicateReportsTimeoutWithoutError(t *testing.T) {
	client, _ := predicateClient(24)
	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:      PredicateNode,
		Operator:  OperatorExists,
		Selector:  NodeSelector{ResourceID: "never.appears"},
		TimeoutMS: 500,
	})
	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v, want nil on timeout", err)
	}
	if result.Satisfied {
		t.Fatal("satisfied = true, want false")
	}
	if result.Polls == 0 {
		t.Fatal("polls = 0, want at least one sample")
	}
}

func TestValidatePredicateRejectsInvalidCombinations(t *testing.T) {
	cases := map[string]Predicate{
		"unknown kind": {
			Kind: "whatever", Operator: OperatorExists,
			Selector: NodeSelector{Text: "x"},
		},
		"unknown operator": {
			Kind: PredicateNode, Operator: "gt",
			Selector: NodeSelector{Text: "x"},
		},
		// 空选择器会匹配任意元素，让 exists 恒真、notExists 恒假。
		"empty selector": {Kind: PredicateNode, Operator: OperatorExists},
		"stable on node": {
			Kind: PredicateNode, Operator: OperatorStable,
			Selector: NodeSelector{Text: "x"},
		},
		"exists on package": {Kind: PredicatePackage, Operator: OperatorExists},
		"package without expected": {
			Kind: PredicatePackage, Operator: OperatorEquals,
		},
		"uiStable with equals": {Kind: PredicateUIStable, Operator: OperatorEquals},
		"bad regex": {
			Kind: PredicateNode, Operator: OperatorMatches,
			Selector: NodeSelector{Text: "x"}, Expected: "([",
		},
		"timeout too large": {
			Kind: PredicateNode, Operator: OperatorExists,
			Selector: NodeSelector{Text: "x"}, TimeoutMS: maxPredicateTimeoutMS + 1,
		},
	}
	for name, predicate := range cases {
		if err := validatePredicate(predicate); err == nil {
			t.Fatalf("%s: validatePredicate() error = nil, want rejection", name)
		}
	}
}

// 选择器所有维度都精确匹配（normalize 后），与动作侧 SelectorMatcher 一致。
// 曾经 text/description 用包含匹配，与动作侧精确相等分叉，已统一到精确。
// normalize（trim+lowercase）让首尾空白与大小写不影响命中，但不做部分匹配。
func TestSelectorMatchesUsesNormalizedEqualityForAllDimensions(t *testing.T) {
	node := uiNode{
		ResourceID:  "android:id/button1",
		Text:        "  NEXT  ",
		Description: "Next step",
		Class:       "android.widget.Button",
		Package:     "com.android.camera2",
	}
	// normalize 后精确命中：首尾空白被 trim，大小写被折叠。
	if !selectorMatches(node, NodeSelector{Text: "next"}) {
		t.Fatal("text should match after trim+lowercase normalization")
	}
	if !selectorMatches(node, NodeSelector{Description: "Next step"}) {
		t.Fatal("description should match its full normalized value")
	}
	// 部分匹配必须不再命中——这正是统一到精确要消除的行为。
	if selectorMatches(node, NodeSelector{Text: "NEX"}) {
		t.Fatal("text must no longer match by prefix/containment")
	}
	if selectorMatches(node, NodeSelector{Description: "Next"}) {
		t.Fatal("description must no longer match by containment")
	}
	if selectorMatches(node, NodeSelector{ResourceID: "android:id/button"}) {
		t.Fatal("resourceId must match exactly, not by prefix")
	}
	if selectorMatches(node, NodeSelector{ClassName: "android.widget"}) {
		t.Fatal("className must match exactly, not by prefix")
	}
	// 多字段之间是「与」关系。
	if selectorMatches(node, NodeSelector{Text: "NEXT", ClassName: "android.widget.TextView"}) {
		t.Fatal("all non-empty fields must match")
	}
}

// 指纹刻意不含 bounds：滚动惯性结束前 bounds 会持续微动，
// 纳入后 uiStable 几乎不可能成立。
func TestFingerprintIgnoresBounds(t *testing.T) {
	first, err := parseHierarchy(realHierarchyXML)
	if err != nil {
		t.Fatalf("parseHierarchy() error = %v", err)
	}
	moved, err := parseHierarchy(
		// 同一份层级，只把 bounds 改掉
		replaceAll(realHierarchyXML, "[120,580][200,620]", "[121,581][201,621]"),
	)
	if err != nil {
		t.Fatalf("parseHierarchy() error = %v", err)
	}
	if fingerprint(first) != fingerprint(moved) {
		t.Fatal("fingerprint changed after only bounds moved")
	}
	renamed, err := parseHierarchy(replaceAll(realHierarchyXML, "NEXT", "DONE"))
	if err != nil {
		t.Fatalf("parseHierarchy() error = %v", err)
	}
	if fingerprint(first) == fingerprint(renamed) {
		t.Fatal("fingerprint must change when text changes")
	}
}

// replaceAll 是测试内的小工具，避免为一次替换引入额外导入。
func replaceAll(text, old, replacement string) string {
	result := ""
	for {
		index := indexOf(text, old)
		if index < 0 {
			return result + text
		}
		result += text[:index] + replacement
		text = text[index+len(old):]
	}
}

func indexOf(text, sub string) int {
	for index := 0; index+len(sub) <= len(text); index++ {
		if text[index:index+len(sub)] == sub {
			return index
		}
	}
	return -1
}

// changedHierarchyXML 与 realHierarchyXML 结构相同但包名与文本不同，
// 用于模拟「界面变了」。
const changedHierarchyXML = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example.next" content-desc="" bounds="[0,0][320,640]">
    <node index="0" text="Second screen" resource-id="android:id/title" class="android.widget.TextView" package="com.example.next" content-desc="" bounds="[20,100][300,140]" />
  </node>
</hierarchy>`

// scriptedPredicateClient 按给定的层级序列逐轮返回，用于模拟界面随时间变化。
//
// 与 predicateClient 的区别：那个每轮都回同一份层级，无法表达「第 N 轮之后变了」，
// 而事件型判据的全部语义都建立在「与基线相比是否变化」上。
func scriptedPredicateClient(hierarchies []string) (*Client, *fakeADB) {
	results := []process.Result{{Stdout: []byte(twoOnlineDevices)}}
	errors := []error{nil}
	for _, xml := range hierarchies {
		results = append(results,
			process.Result{Stdout: []byte(twoOnlineDevices)},
			process.Result{Stdout: []byte("")},
			process.Result{Stdout: []byte(xml)},
			process.Result{Stdout: []byte("")},
		)
		errors = append(errors, nil, nil, nil, nil)
	}
	fake := &fakeADB{results: results, errors: errors}
	return NewClient("/fake/adb", fake), fake
}

// newWindow 在界面始终不变时不得成立。
//
// 这条是事件型判据最容易写错的方向：若第一轮就判定成立，调用方会以为已经
// 跳转到新界面，实际还停在原地。
func TestAwaitNewWindowDoesNotFireWithoutAChange(t *testing.T) {
	client, _ := predicateClient(6)

	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:      PredicateNewWindow,
		Operator:  OperatorOccurred,
		TimeoutMS: 1200,
	})

	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if result.Satisfied {
		t.Fatalf("newWindow fired without any change: %#v", result)
	}
}

// 界面确实换掉之后，newWindow 必须成立。
func TestAwaitNewWindowFiresAfterTheScreenChanges(t *testing.T) {
	client, _ := scriptedPredicateClient([]string{
		realHierarchyXML,
		changedHierarchyXML,
		changedHierarchyXML,
	})

	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:      PredicateNewWindow,
		Operator:  OperatorOccurred,
		TimeoutMS: 3000,
	})

	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("newWindow did not fire after the screen changed: %#v", result)
	}
	if result.ActualPackage != "com.example.next" {
		t.Fatalf("actualPackage = %q", result.ActualPackage)
	}
}

// scrollFinished 在界面从未变化时不得成立。
//
// 这是与 uiStable 的关键区别：uiStable 一开始就稳定即成立，而 scrollFinished
// 必须先观察到滚动确实发生过。否则在滚动开始前调用会立刻返回成立，
// 调用方以为滚完了，实际一行都没动。
func TestAwaitScrollFinishedRequiresMotionFirst(t *testing.T) {
	client, _ := predicateClient(6)

	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:             PredicateScrollFinished,
		Operator:         OperatorOccurred,
		StableDurationMS: 100,
		TimeoutMS:        1200,
	})

	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if result.Satisfied {
		t.Fatalf("scrollFinished fired although nothing ever moved: %#v", result)
	}
}

// 先变化再静止时，scrollFinished 必须成立。
func TestAwaitScrollFinishedFiresAfterMotionSettles(t *testing.T) {
	client, _ := scriptedPredicateClient([]string{
		realHierarchyXML,
		changedHierarchyXML,
		changedHierarchyXML,
		changedHierarchyXML,
		changedHierarchyXML,
	})

	result, err := client.AwaitPredicate(context.Background(), "FIRST", Predicate{
		Kind:             PredicateScrollFinished,
		Operator:         OperatorOccurred,
		StableDurationMS: 100,
		TimeoutMS:        4000,
	})

	if err != nil {
		t.Fatalf("AwaitPredicate() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("scrollFinished did not fire after motion settled: %#v", result)
	}
}

// 事件型判据只接受 occurred，且不接受 expected。
func TestEventPredicatesRejectOtherOperatorsAndExpected(t *testing.T) {
	client, _ := predicateClient(2)

	for _, predicate := range []Predicate{
		{Kind: PredicateNewWindow, Operator: OperatorExists},
		{Kind: PredicateScrollFinished, Operator: OperatorStable},
		{Kind: PredicateNewWindow, Operator: OperatorOccurred, Expected: "anything"},
	} {
		if _, err := client.AwaitPredicate(
			context.Background(), "FIRST", predicate,
		); err == nil {
			t.Fatalf("AwaitPredicate(%#v) = nil, want error", predicate)
		}
	}
}

// 一致性锁定：判据侧选择器与动作侧 SelectorMatcher 对同一 (节点, 选择器) 必须给出
// 相同的命中判定。两者是不同语言的实现，无法在同一测试里直接调用动作侧，
// 因此这里把动作侧 SelectorMatcher.candidateMatches 的语义（normalize 后精确相等）
// 复刻成一个参照函数，断言判据侧与它逐例一致。参照函数一旦与动作侧真实实现分叉，
// 应由动作侧的 Kotlin 单测（SelectorMatcher 的精确匹配用例）在 android-verify 中捕获。
func TestSelectorMatchingIsConsistentWithActionSideSemantics(t *testing.T) {
	// actionSideMatches 复刻 SelectorMatcher.normalized() == expected 的口径。
	actionSideMatches := func(actual, expected string) bool {
		return normalizeSelectorValue(actual) == normalizeSelectorValue(expected)
	}
	node := uiNode{
		ResourceID:  "android:id/save",
		Text:        "  Save Order  ",
		Description: "Save the order",
		Class:       "android.widget.Button",
		Package:     "com.example.shop",
	}
	cases := []struct {
		name     string
		selector NodeSelector
		field    string // 参与比较的节点字段值
		value    string // 选择器给的值
	}{
		{"text exact", NodeSelector{Text: "Save Order"}, node.Text, "Save Order"},
		{"text case", NodeSelector{Text: "save order"}, node.Text, "save order"},
		{"text partial", NodeSelector{Text: "Save"}, node.Text, "Save"},
		{"desc exact", NodeSelector{Description: "Save the order"}, node.Description, "Save the order"},
		{"desc partial", NodeSelector{Description: "Save"}, node.Description, "Save"},
		{"resourceId exact", NodeSelector{ResourceID: "android:id/save"}, node.ResourceID, "android:id/save"},
		{"resourceId partial", NodeSelector{ResourceID: "save"}, node.ResourceID, "save"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			predicateSide := selectorMatches(node, c.selector)
			actionSide := actionSideMatches(c.field, c.value)
			if predicateSide != actionSide {
				t.Fatalf(
					"predicate side = %v but action side = %v; the two paths diverged again",
					predicateSide, actionSide,
				)
			}
		})
	}
}

// scrollUntilFakeADB 按命令内容而非调用顺序应答，用于 ScrollUntil 测试。
//
// ScrollUntil 每滚一次会穿插 devices / wm size / input swipe / uiautomator dump
// 多种调用，用定位式脚本极易备错料。这里按 args 内容分派：hierarchy dump 在
// 滚动 scrollsBeforeMatch 次后才返回含目标文本的层级。
type scrollUntilFakeADB struct {
	scrollsBeforeMatch int
	swipes             int
	matchXML           string
	noMatchXML         string
}

func (f *scrollUntilFakeADB) Run(
	_ context.Context,
	_ string,
	args []string,
	_ process.Options,
) (process.Result, error) {
	joined := strings.Join(args, " ")
	switch {
	case strings.Contains(joined, "wm size"):
		return process.Result{Stdout: []byte("Physical size: 1080x1920\n")}, nil
	case strings.Contains(joined, "input swipe"):
		f.swipes++
		return process.Result{}, nil
	case strings.Contains(joined, "uiautomator dump"):
		return process.Result{}, nil
	case strings.Contains(joined, "exec-out") || strings.Contains(joined, "cat "):
		if f.swipes >= f.scrollsBeforeMatch {
			return process.Result{Stdout: []byte(f.matchXML)}, nil
		}
		return process.Result{Stdout: []byte(f.noMatchXML)}, nil
	case strings.Contains(joined, "rm -f"):
		return process.Result{}, nil
	default:
		// devices 查询等：回两台在线设备。
		return process.Result{Stdout: []byte(twoOnlineDevices)}, nil
	}
}

// scrollUntil 找到目标文本时应停止滚动并返回成立。
func TestScrollUntilStopsWhenPredicateHolds(t *testing.T) {
	targetXML := `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="已送达" resource-id="android:id/status" class="android.widget.TextView" package="com.example" content-desc="" bounds="[0,0][320,80]" />
</hierarchy>`
	fake := &scrollUntilFakeADB{
		scrollsBeforeMatch: 2,
		matchXML:           targetXML,
		noMatchXML:         realHierarchyXML,
	}
	client := NewClient("/fake/adb", fake)

	result, err := client.ScrollUntil(
		context.Background(), "FIRST", ScrollDown, 0.5, 10,
		Predicate{
			Kind:     PredicateText,
			Operator: OperatorContains,
			Selector: NodeSelector{Text: "已送达"},
			Expected: "已送达",
		},
	)

	if err != nil {
		t.Fatalf("ScrollUntil() error = %v", err)
	}
	if !result.Satisfied {
		t.Fatalf("predicate should hold after scrolling: %#v", result)
	}
	// 命中后必须停下：滚动次数不应用满 maxScrolls。
	if fake.swipes > 3 {
		t.Fatalf("kept scrolling after the target appeared: swipes=%d", fake.swipes)
	}
}

// scrollUntil 目标始终不出现时，滚满 maxScrolls 后返回未成立，不报错也不越界。
func TestScrollUntilStopsAtMaxScrolls(t *testing.T) {
	fake := &scrollUntilFakeADB{
		scrollsBeforeMatch: 999,
		matchXML:           realHierarchyXML,
		noMatchXML:         realHierarchyXML,
	}
	client := NewClient("/fake/adb", fake)

	result, err := client.ScrollUntil(
		context.Background(), "FIRST", ScrollDown, 0.5, 3,
		Predicate{
			Kind:     PredicateText,
			Operator: OperatorExists,
			Selector: NodeSelector{Text: "never appears here"},
		},
	)

	if err != nil {
		t.Fatalf("ScrollUntil() error = %v", err)
	}
	if result.Satisfied {
		t.Fatalf("predicate should not hold: %#v", result)
	}
	if fake.swipes != 3 {
		t.Fatalf("swipes = %d, want exactly maxScrolls=3", fake.swipes)
	}
}

// maxScrolls 越界必须在滚动前被拒。
func TestScrollUntilRejectsOutOfRangeMaxScrolls(t *testing.T) {
	client, _ := predicateClient(1)
	for _, maxScrolls := range []int{0, 51} {
		_, err := client.ScrollUntil(
			context.Background(), "FIRST", ScrollDown, 0.5, maxScrolls,
			Predicate{Kind: PredicateText, Operator: OperatorExists, Selector: NodeSelector{Text: "x"}},
		)
		if err == nil {
			t.Fatalf("ScrollUntil(maxScrolls=%d) = nil, want error", maxScrolls)
		}
	}
}
