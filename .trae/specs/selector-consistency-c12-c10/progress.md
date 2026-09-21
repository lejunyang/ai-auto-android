# 进度

本文件 append-only。

## 实施顺序

A→B→C→D 严格串行，单一 owner。四者共享 SelectorMatcher.kt / predicate.go /
选择器 schema，同一文件只允许顺序改。基线 `d2ae7d8`，worktree `E:/Projects/wt-sel`，
分支 `selector-consistency-c12-c10`。

## 任务 A（提交 0976f13）

判据侧 text/description 从 strings.Contains 改精确匹配。关键发现：不能只把
Contains 换成 ==，动作侧用 normalized() 比较，只改 == 会把「包含 vs 精确」的分叉
换成「normalize vs 不 normalize」的分叉。因此新增 normalizeSelectorValue 并把
resourceId/className/package 也一并 normalize。447 行 OperatorContains 运算符实现
原样保留（grep 确认）。变异验证：把 text 改回 Contains，跨侧一致性测试在
"text partial" 用例如期失败。version.json 归类 narrow-valid-range。

## 任务 B（提交 fb43a4a）

动作侧补 className/package 维度。除 schema/enum/parser/matcher 外，还需同步三处
SelectorStrategy 穷举点（WebViewSemanticAdapter 反向映射、ScriptEditorSession 与
RecordingScriptStore 的选择器白名单）——不补会编译失败或录制拒绝新维度。

## 任务 C（提交 23ef3a3）

match 字段默认 exact。标识符维度非精确匹配在 parser 运行时拒绝。不引入 regex。
变异验证：默认改 contains，"default match is equivalent to exact" 与多条依赖
exact-by-default 的用例一并失败，印证默认值载重。

## 任务 D（本提交）

### 判据求值抽取

C11 把 newWindow/scrollFinished 加进 AwaitPredicate 轮询循环，状态（stableSince/
prevFinger/baseline*）都是循环内局部变量。ui.scrollUntil 要复用求值逻辑，必须先
抽成独立函数。做法：把逐轮 switch 抽成 predicateEvaluator.evaluate，状态提升为
struct 字段，AwaitPredicate 与 ScrollUntil 各持一个独立实例。抽取是纯搬迁，
C11 的四条事件判据测试全部通过，确认无回归。

### ui.scrollUntil

新增动作类型而非给 scrollIntoView 加 until（后者触及 change-field-semantics）。
adb.ScrollUntil 循环：先采基线求值，再滚动-采样-求值直到成立或达 maxScrolls。
until 判据复用 A 统一后的选择器与 C 的 match 语义。协议 description 写明轮询 vs
事件的语义落差。

三通道出口：CLI check scroll-until、MCP android_scroll_until（README 15→16）。
Bridge 侧不接：scrollUntil 走 Go 直连轮询，桥侧无需实现。

测试用内容感知的 fake executor（按 args 分派而非定位式脚本，因每次滚动穿插
devices/wm-size/input/dump 多种调用）：命中即停、滚满 maxScrolls 返回未成立、
越界拒绝。

## 过程中的自造错误

多行锚点 + .Replace() 连续编辑同一文件再次咬到自己插入的文本，导致
SelectorMatcher.kt、AccessibilityCommandJsonParser.kt、check.go 三次被写坏。
每次都 git checkout 单文件恢复后改用行索引/行扫描插入。这是本轮反复出现的同类
问题，已固定为：多行结构改动一律行索引 splice + numstat 核对。

## 门禁结果

make comments 559 文件 / make test / make verify 协议 56 项 / make build 全通过。
make android-verify 删 test-results 强制重跑，74 XML / 555 tests / 0 failures。
