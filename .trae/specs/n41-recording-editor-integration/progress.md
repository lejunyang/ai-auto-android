# N41 编辑入口集成进度

本文件 append-only，记录入口、持久化、dry-run 和剩余设备证据。

## Round 1

- follow-up worktree 基于已合入 N44/N41/N45 的 `e3d62ee`，启动时干净。
- 现有 `RecordingHost` 只有 list/session/detail；N41 组件已存在但没有用户入口。
- 当前详情页没有活跃 screenshot authorization，本任务保持 observation surface
  隐藏，不创建假图片或隐式权限。

## Round 2

- `RecordingDestination.EDITOR` 已接入现有 RecordingHost 子状态，不改变根 NavHost。
  编辑 ViewModel 使用 script ID key，旋转后复用表单、Undo/Redo 和 dirty 状态。
- 真实 `RecordingScriptStoreSavePort` 负责 revision CAS；保存后重新选择脚本并返回
  detail，复制使用 expected revision 0 保存后打开新 ID。冲突仍保留本地 attempted
  与磁盘 current。
- 新增只读 Accessibility dry-run adapter，缓存一次脱敏 snapshot，复用现有
  selector/coordinate transformer，并只检查 package/node 条件；类型不暴露 execute。
- 普通详情页显式传 `observationHolder=null`，没有 Automation Session 授权时不显示
  screenshot 点选 surface；真实授权 observation provider 和设备 instrumentation
  仍未执行。
- App JVM 全量 309/309、AndroidTest APK 编译、`lintDebug`、comments 与
  diff-check 通过。详情入口、真实 store save/copy 和只读 dry-run 已完成；真实
  screenshot 点选仍需调用方在活跃授权会话中传入 lease，未在本轮设备执行。
- 主实现提交为 `ede27d7`，author/committer 均为
  `lejunyang <lejunyang@qq.com>`，trailer 恰好一次；提交仅修改 recording UI、
  对应测试和本 follow-up spec，worktree 提交后 clean。
