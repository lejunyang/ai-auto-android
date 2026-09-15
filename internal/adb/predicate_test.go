package adb

// 测试用途：本文件验证界面判据的参数校验、选择器匹配与各运算符语义，
// 覆盖空选择器被拒、kind 与 operator 的组合约束以及 uiStable 指纹判据。

import (
	"context"
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

// 选择器对 resourceId 与 className 要求相等，对 text 与 description 用包含：
// uiautomator 的 text 常带空白或省略号，精确相等会让大量本该命中的元素落空。
func TestSelectorMatchesUsesEqualityForIDsAndContainsForText(t *testing.T) {
	node := uiNode{
		ResourceID:  "android:id/button1",
		Text:        "  NEXT  ",
		Description: "Next step",
		Class:       "android.widget.Button",
		Package:     "com.android.camera2",
	}
	if !selectorMatches(node, NodeSelector{Text: "NEXT"}) {
		t.Fatal("text should match by containment")
	}
	if !selectorMatches(node, NodeSelector{Description: "Next"}) {
		t.Fatal("description should match by containment")
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
