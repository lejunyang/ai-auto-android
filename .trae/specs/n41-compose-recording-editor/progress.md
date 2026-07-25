# N41 进展记录

> 本文件只追加，不覆盖历史记录。

## 2026-07-26 启动

- 从 `39711ea59319bf9ccc869a58034ba467d67da0b4` 创建并核对现有
  `phase2-n41-recording-editor-ui` worktree，初始工作区干净。
- 确认 N40 已提供 `ScriptEditorSession`、全部 `EditStep*` 命令、revision
  保存事务和只读 `ScriptDryRunEngine`，N41 不复制领域规则。
- 采用 JVM 状态测试与 Compose instrumentation 源码并行覆盖；只有实际连接
  设备运行后才会把 instrumentation 记为通过。

## 2026-07-26 实现

- 新增 `RecordingEditorViewModel`，只消费 N40 `ScriptEditorSession` 和命令。
  完整表单先在临时 session 验证，再以单个 `UpdateStep` 原子提交，因此一次 Undo
  可回退 selector、坐标、wait、retry、failure 和 notes 的全部变更。
- 新增可嵌入 `RecordingEditorScreen`，提供复制、步骤上/下移、启停、删除/复制、
  Undo/Redo/save、完整字段表单、dry-run 报告、冲突和离开确认。未修改导航根，
  当前仍需调用方把该组件接入现有详情页。
- `RecordingObservationHolder` 只持有调用方授权的 draw receiver 和短期 lease；
  ViewModel 仅保存归一化坐标文本，不保存 observation ID、图片、Base64、路径或权限
  句柄。过期、旋转重建和离开均幂等释放。
- N41 JVM 定向 6/6、App JVM 全量 288/288、AndroidTest APK 编译、`lintDebug`、
  comments 与 diff-check 通过。instrumentation 源码已编译但未在设备执行，真实
  UI 交互与授权 screenshot provider 接线保持未验收。
- 主实现提交为 `e42a21f`，author/committer 为
  `lejunyang <lejunyang@qq.com>` 且 trailer 恰好一次；提交仅包含 N41 允许的
  recording UI/spec/test 文件，未修改 N40 domain、协议、ReplayEngine 或导航根。
