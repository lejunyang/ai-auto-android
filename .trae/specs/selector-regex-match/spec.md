# C12 第 3 步：选择器 regex 匹配

给选择器 `match` 字段加第 5 个取值 `regex`，作用于文本类维度。

## 关键结构事实（决定方案的前提，已核实）

`match` 字段**只有一条执行引擎**，不是两条：

- 选择器 `match` 模式只被 Kotlin `SelectorMatcher.matchesText`（`:224`）消费。
- Go 判据侧 `NodeSelector`（`predicate.go:82`）**没有** match 字段；
  `PredicateRequest.toPredicate()` 构造的是扁平精确选择器，从不读
  `selectorCandidates` 的 match 模式。
- `ReplayEngine.evaluateNode`（`:185`）也走同一个 Kotlin `SelectorMatcher`。
- Go 的 `regexp` 只服务判据**运算符** `matches`（`predicate.go:466`），那是另一条
  独立路径，本步不动它。

因此调研文档担心的「Go RE2 vs Kotlin java.util.regex 两侧分叉」，在选择器 match
这条路径上**不存在两侧**——只有 Kotlin 一侧执行。用户上批修的 text 分叉是
「判据侧 Go Contains vs 动作侧 Kotlin 精确」那种跨语言路径，而 regex 只落在 Kotlin。

## 方案选择：方案一（RE2 兼容语义），但落点是「桥侧保守校验」

尽管只有一条引擎，仍选方案一而非方案二，理由：

1. **ReDoS 是真实风险**，与是否分叉无关。Kotlin `Regex` 用 java.util.regex，
   有回溯，用户 pattern 可构造灾难性回溯（`(a+)+$`）。必须防。
2. **为将来对齐留余地**：Go 判据运算符 `matches` 用 RE2。若把选择器 regex 限定为
   RE2 可接受的子集，将来若判据侧也支持 selector match，两侧语义天然一致，
   不会重蹈 text 分叉。反之若现在放任 java.util.regex 全语法，将来对齐要收窄，
   那才是破坏性变更。

具体做法（三层防护，缺一不可）：

1. **限长**：pattern 长度上限 512（与 `selectorCandidate.value` 的 maxLength 一致）。
   超长在入口校验阶段拒绝。
2. **编译校验前置**：pattern 在 parser 入口 `Regex(pattern)` 编译一次，非法 pattern
   报明确 INVALID_ARGUMENT，不进运行时静默失配（对齐 `predicate.go:466` 模式）。
3. **执行超时兜底 ReDoS**：Kotlin 无法像 RE2 那样保证线性时间，因此匹配在独立
   线程执行并设硬超时（如 100ms），超时视为不匹配并记录。这是防 ReDoS 挂起的
   最后一道，且必须实测灾难性 pattern 确实不挂起，不能只声明「已加限制」。

RE2 子集校验作为**可选加强**：本步先做上述三层（限长 + 编译校验 + 超时）。
RE2 子集静态校验（禁嵌套量词等）留作后续，因为超时已能兜住 ReDoS 的实际危害，
而静态识别所有灾难性构造本身容易漏。spec 记录这一取舍。

## 作用维度

regex 只作用于 text / contentDescription（与其它非精确模式一致）。标识符维度
（resourceId/className/package）恒精确：现有 parser 守卫
`!textual && match != EXACT`（`AccessibilityCommandJsonParser.kt:307`）已自动覆盖——
regex 成为合法枚举值后，它在标识符维度上照样被这条守卫拒绝，无需新增逻辑。
加测试确认这条守卫对 regex 生效。

## version.json 归类

`add-enum-value`（match 枚举加第 5 值）。默认仍 exact，不改默认行为。
逐条对照 requiresNewMajor：不删字段、不改语义、不改默认、不收窄、不把可选变必填，
全部不触发。

## 落点

- `protocol/schema/v1/action.schema.json`：match enum 加 `regex`
- `AccessibilityModels.kt`：`SelectorMatchMode` 加 `REGEX`
- `AccessibilityCommandJsonParser.kt`：解析 `"regex"`；入口编译校验 + 限长
- `SelectorMatcher.kt`：`matchesText` 加 REGEX 分支，走带超时的匹配
- 测试：命中/不命中、非法 pattern 入口拒绝、超长拒绝、ReDoS 不挂起、
  标识符维度拒绝、默认仍 exact、regex 匹配的确定性一致

## 不做

- 不动 Go 判据运算符 matches（那是独立路径）
- 不引入 RE2 子集静态校验（超时已兜底，留后续）

## 验收

- 四道门禁 + android-verify（删 test-results 强制重跑，核对 XML）
- ReDoS 测试用 `(a+)+$` 配长文本，实测不挂起
- 变异验证：关掉超时或限长，确认对应测试变红
