# C12 regex 匹配进度

本文件 append-only。基线 `1b2937f`，worktree `E:/Projects/wt-rx`，
分支 `selector-regex-match`。

## Round 1：核实引擎不对称的真相

调研文档担心「Go RE2 vs Kotlin java.util.regex 两侧分叉」。逐代码核实后发现：
选择器 `match` 模式**只有一条执行引擎**——只被 Kotlin `SelectorMatcher.matchesText`
消费。Go 判据侧 `NodeSelector`（`predicate.go:82`）没有 match 字段；Go 的 regexp
只服务判据运算符 `matches`，是另一条独立路径。所以选择器 regex 不存在两侧，
上批修的 text 分叉是跨语言路径，与此不同。

据此仍选方案一（RE2 兼容取向 + 保守防护）：ReDoS 与是否分叉无关，必须防；
限定行为也为将来若判据侧支持 selector match 时对齐 Go RE2 留余地。

## Round 2：发现并修复前序步骤的潜伏缺陷

写「parses regex match mode」测试时，它以 "Unsupported fields: match" 失败——
在 `ProviderActionParser.parse`（parse 链的第一层校验）就被挡下。追查发现：

**B 步的 className/package 与 C 步的 match 字段，都只改了 SelectorMatcher，
漏改了上游的 ProviderActionParser。** 而 `AccessibilityCommandJsonParser.parse`
第 42 行先调 `ProviderActionParser.parse`，所以带这些新维度/字段的 ui.click
在到达匹配器前就被拒。B/C 的测试全绿只因它们直接调 SelectorMatcher，
绕过了完整解析链——典型的「exit 0 不是证据」。

本轮一并修复：ProviderActionParser 的 SELECTOR_STRATEGIES 补 className/package，
selectorCandidate 允许 match 键并校验枚举 + 标识符守卫（与下游同一判据，
两层一致才不会「schema 放行、匹配器拒绝」错位）。

## Round 3：ReDoS 测试的实证纠偏

第一版用 `(a+)+$` 配 60 个 a 作灾难性 pattern。**变异测试（去掉超时防护）却没让
测试变红**——说明这个 pattern 在当前引擎下根本不灾难。没有停在「测试通过」，
写了独立 Java 探针实测：JDK 21 会优化 `(a+)+$`（0ms 秒回），而 `(.*a){15}$` 配
40 个 a 才真正指数级（3s 仍未结束）。据此改用后者。

改后重跑变异：去掉 DeadlineCharSequence → ReDoS 测试因 JUnit4 timeout=5000 超时
变红；放大 MAX_REGEX_PATTERN_LENGTH → 超长测试变红。两处防护均证明载重。
有防护时 ReDoS 测试 0.106s 通过（超时 100ms 触发）。

## 门禁结果

make comments 559 / make test / make verify 协议 56 项 / make build 全通过。
make android-verify 删 test-results 强制重跑，74 XML / 561 tests / 0 failures，
逐条核对 6 条 regex 用例真实 PASS。
