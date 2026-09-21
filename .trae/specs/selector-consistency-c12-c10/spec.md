# 选择器一致性与 C12 / C10 实施

四个任务严格串行 A→B→C→D，单一 owner。四者都改选择器或其判据求值，
共享 `SelectorMatcher.kt` / `predicate.go` / 选择器 schema，同一文件只能顺序改。

## 任务 A：统一选择器到精确匹配（最高优先，破坏性变更）

### 现状（已核对）

同一类不一致存在于**两个维度**：

| 维度 | 动作侧 `SelectorMatcher.kt` | 判据侧 `predicate.go` |
| --- | --- | --- |
| text | 精确（`:197` `normalized() == expected`） | 包含（`:398` `strings.Contains`） |
| contentDescription/description | 精确（`:194`） | 包含（`:402` `strings.Contains`） |

调用方会遇到「`check wait --kind text` 等到了、`ui.click` 点不到」，
失败只报 `SELECTOR_NOT_FOUND`。

### 改动

把 `predicate.go` 的 398、402 两行 `strings.Contains` 改成精确相等，与动作侧对齐。
text 与 description 一起改。

**绝对不动的一处**：`predicate.go:431` 的 `strings.Contains` 是 `OperatorContains`
运算符实现（`evaluateTextComparison`，426-432 行），是调用方显式写
`operator: contains` 时的正常语义，与选择器字段定位无关。同理不碰 416/420 的运算符
分派。只动 398/402。

### 一个必须处理的细节：normalize 不对称

动作侧 `SelectorMatcher` 用 `normalized()`（trim + 折叠空白）后再比较，
而判据侧 `selectorMatches` 直接比 `node.Text`。若判据侧只改成 `==` 而不 normalize，
两侧对 `"  NEXT  "` 仍会分叉——动作侧命中、判据侧不命中。因此判据侧改精确匹配时
必须**同样先 normalize**，否则 A 只是把「包含 vs 精确」的分叉换成「normalize vs
不 normalize」的分叉，没真正统一。这是 A 的关键，不是可选优化。

### version.json 归类

行为收窄（原本部分匹配也成立，现在要求完全相等）。对照 `requiresNewMajor`：
`narrow-valid-range`。结论写进本 spec。但注意：这是**运行时匹配行为**的收窄，
schema 层的取值范围没变（选择器字段一直是任意字符串）。协议 fixture 不编码匹配
语义，因此 56 项协议校验不受影响——需实测确认。

### 现有测试

`predicate_test.go:217` 的 `TestSelectorMatchesUsesEqualityForIDsAndContainsForText`
显式断言 text/description 走包含匹配，其注释还说明了 trimming 动机。这条测试要
改写为断言精确匹配（含 normalize），不是删除——它的对照价值保留。

### 一致性测试（防再次分叉）

新增测试：同一选择器值对同一节点，动作侧与判据侧命中结果必须一致。
text、description 各一例（精确命中 + 部分匹配不命中），resourceId 作正向对照
（它本就精确，两侧应一致）。

## 任务 B：补 className / package 维度

动作侧 `SelectorStrategy` 只有 6 维，判据侧已有 ClassName/Package
（`predicate.go:86-87`）。给动作侧补这两维，精确匹配，与 A 统一后口径一致。

- 落点：`AccessibilityModels.kt` 的 `SelectorStrategy` 枚举、
  `SelectorMatcher.candidateMatches`、`action.schema.json` 的 strategy enum、
  三通道传递。
- version.json：`add-enum-value`——属新增能力，破坏性等级低于 A。逐条核对。

## 任务 C：match 字段，默认 exact

给 `SelectorCandidate` 加 `match` 字段控制匹配方式，**默认 exact**。
默认 exact 时行为与 A 统一后完全一致 → `add-optional-field`，不触发 requiresNewMajor。
若默认设 contains 就是 `change-default-behavior`——默认值锁死 exact。

- 取值集合：本步做 `exact` / `contains` / `startsWith` / `endsWith`。
  **不引入 regex**（评估为可不做，留待单独决策）。
- match 只对 text / contentDescription 生效；resourceId/className/package 是标识符，
  部分匹配会命中意外元素，运行时校验拒绝在这些维度上用非 exact。
- 加测试钉住「不传 match 等价于 exact」。

## 任务 D：ui.scrollUntil

新增动作类型，不给 scrollIntoView 加 until。

- **前置**：判据求值目前内嵌在 `AwaitPredicate` 轮询循环、依赖循环内状态
  （`stableSince` / `previousFingerprint` / `baseline*`）。复用前抽成独立函数，
  状态外置。抽取本身要保证 C11 的 newWindow/scrollFinished 行为不变——加回归测试。
- until 判据复用 A 统一后的选择器语义与 C 的 match 字段。
- 排在 A/B/C 之后：选择器稳定后再做避免返工。

## 不做

- C12 的 regex（match=regex）
- 其余需权衡 7 项、有意不做 10 项

## 验收

- 四道门禁 + `make android-verify`（删 test-results 强制重跑，核对 JUnit XML）
- A 的一致性测试做变异验证（让两侧重新分叉，确认测试变红）
- 无设备证据项保持未勾选
