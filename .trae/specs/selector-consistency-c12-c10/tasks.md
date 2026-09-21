# 选择器一致性与 C12/C10 实施任务

## 任务 A：统一选择器精确匹配

- [x] predicate.go 的 text/description 从 strings.Contains 改精确匹配
- [x] 新增 normalizeSelectorValue，与动作侧 SelectorMatcher.normalized() 同口径
- [x] resourceId/className/package 一并 normalize
- [x] 未动 447 行 OperatorContains 运算符实现
- [x] 改写原 contains 测试为精确匹配（含 normalize）
- [x] 新增跨侧一致性测试 + 变异验证
- [x] version.json 归类：narrow-valid-range（运行时行为收窄）

## 任务 B：className / package 维度

- [x] schema strategy enum + Kotlin 枚举 + parser + matcher
- [x] 三处 SelectorStrategy 穷举点同步（WebView 映射、两处录制白名单）
- [x] SelectorMatcher 测试：组合命中 + className 精确不前缀
- [x] version.json 归类：add-enum-value

## 任务 C：match 字段，默认 exact

- [x] schema match enum（exact/contains/startsWith/endsWith），默认 exact
- [x] Kotlin SelectorMatchMode 枚举 + candidate 字段
- [x] parser 解析 + 标识符维度非精确匹配运行时拒绝
- [x] SelectorMatcher.matchesText 按模式匹配
- [x] 不引入 regex
- [x] 测试：默认等价 exact（含变异验证）+ 各模式命中 + 标识符拒绝

## 任务 D：ui.scrollUntil

- [x] 判据求值从 AwaitPredicate 抽出为 predicateEvaluator（C11 回归通过）
- [x] adb.ScrollUntil：滚动循环 + 复用求值器 + maxScrolls 上限
- [x] schema uiScrollUntil（复用 predicate def）
- [x] service.ScrollUntil + Automation 接口
- [x] CLI check scroll-until
- [x] MCP android_scroll_until + README 16
- [x] 测试：命中即停 / 滚满 maxScrolls / 越界拒绝

## 收尾

- [x] 四道门禁 + android-verify（强制重跑核对 XML）
- [x] 收口清单同步：A/B/C/D 移入已收口，text 分叉标为已修
