# ime.show 聚焦语义验收清单

## 范围与流程

- [x] 独立 change spec 与允许范围符合 AGENTS.md
- [x] 未回写 `.trae/specs/build-ai-android-automation-mvp/`（已用 git diff 核验）
- [x] 独立分支 + worktree，单一职责 Conventional Commit
- [x] 未 push 到远端
- [x] 未触碰文件传输与 `TestFileTransferHasNoCLIEntryPoint`（已用 git diff 核验）

## 任务一：视觉动作

- [x] 已核实 507 行是校验而非执行分支，结论写入 spec 与 progress
- [x] 三类型执行路径有测试覆盖
- [x] long-click 与 swipe 的 0 / 1 / 10000 / 越界参数边界有测试
- [x] swipe 四方向均有测试
- [x] tap 原有路径无回归（原有测试全绿）
- [x] 未新增动作类型、未放宽参数范围
- [x] `validPortResult` 三态约束未改动

## 任务二：ime.show

- [x] 桥侧可执行 `ime.show`
- [x] 无可聚焦输入控件时明确失败，不返回假成功
- [x] 结果字段不声称「键盘已显示」，只表达「已聚焦」
- [x] `action.schema.json` 描述写明不保证键盘出现
- [x] `docs/protocol.md` 对应行写明同一上限
- [x] capability 声明写明该限制
- [x] 未引入 InputMethodService，未扩大权限面
- [x] 未用 dumpsys 猜测键盘可见性

## 任务三：文档

- [x] `task.finish` 不再挂在待决清单
- [x] `ime.show` 已从待决清单移除
- [x] 未改动其它历史结论

## 验证

- [x] `make comments` 通过（555 个手写文件）
- [x] `make test` 通过
- [x] `make verify` 通过（协议 56 项）
- [x] `make build` 通过
- [x] `make android-verify` 通过（543 单测 0 失败，已逐条核对 JUnit XML）

## 待设备验证（保持未勾选）

- [ ] 真机 / 模拟器上 `ime.show` 聚焦后键盘实际弹出行为

  说明：本项刻意不作为契约承诺。无障碍通道无法查询键盘可见性，键盘是否出现由
  输入法实现与当前焦点状态决定。可断言的「聚焦成功」已由单测覆盖；此条记录的是
  真机观察，不是通过条件。

- [ ] 真机上无可编辑焦点时返回 `ACTION_NOT_SUPPORTED` 的实际表现
