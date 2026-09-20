# ime.show 聚焦语义任务

## 任务一：视觉动作三类型执行现状

- [x] 核实 507 行是否为执行分支（结论：是校验函数，前提不成立）
- [x] 探针实测五种动作组合是否抵达端口（五种全部 committed）
- [x] 补测试锁定三类型执行路径、参数边界与 swipe 四方向（16 个子用例）
- [x] 删除临时探针文件

## 任务二：ime.show 实现

- [x] 先增加 RED 测试：聚焦成功、无输入控件失败、不声称键盘可见
- [x] `AccessibilityCommand` 新增 `ImeShow` 变体
- [x] `AccessibilityActionBackend` 新增 `focusInputControl()`
- [x] `AndroidAccessibilityBackend` 实现，复用 `focusedInputNode()` 与 ACTION_FOCUS
- [x] `AccessibilityActionRouter` 新增 `executeImeShow`
- [x] `AccessibilityCommandJsonParser` 新增 `"ime.show"` 分支
- [x] `action.schema.json` 的 `imeShow` 补能力上限描述
- [x] `docs/protocol.md` 对应行补能力上限
- [x] `AndroidBridgeMethods` capability 如实声明限制

## 任务三：文档更正

- [x] `task.finish` 从 N59 待决清单改写为「已归属明确，非缺口」
- [x] `ime.show` 实现后从该清单移除

## 收尾

- [x] `make comments` / `make test` / `make verify` / `make build`
- [x] `make android-verify`（本轮改 Kotlin，必跑）
- [x] 审查范围并提交

## 实际提交

- `test(visual): lock attested visual action type coverage`
- `feat(accessibility): implement ime.show as input focus`
- `docs: correct task.finish ownership and action count`
