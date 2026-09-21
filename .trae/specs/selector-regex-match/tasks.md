# C12 regex 匹配任务

- [x] 核实结构：match 只有 Kotlin 一条引擎，Go 判据侧无 match 字段
- [x] schema match enum 加 regex + description 写明限长与超时
- [x] SelectorMatchMode 加 REGEX
- [x] parser 解析 "regex" + 入口限长(512) + 编译校验
- [x] 标识符维度守卫覆盖 regex（复用既有守卫，加测试确认）
- [x] SelectorMatcher.matchesText 加 REGEX 分支，走带超时匹配
- [x] DeadlineCharSequence + RegexTimeoutException（100ms 超时兜底 ReDoS）
- [x] **修复前序步骤遗留缺陷**：ProviderActionParser 补 className/package 策略与
      match 字段——B/C 步骤只改了 SelectorMatcher，漏了这层 schema 校验，
      导致带新维度/match 的动作在到达匹配器前就被挡下
- [x] 测试：命中/不命中、非法 pattern 入口拒绝、超长拒绝、标识符拒绝、
      ReDoS 不挂起、默认仍 exact
- [x] 变异验证：去超时防护→ReDoS 测试红；放大限长→超长测试红
- [x] 四道门禁 + android-verify（强制重跑核对 XML）
- [x] 收口清单 C12 regex 移入已完成

## 提交

- feat(selector): add regex match mode with ReDoS guards
