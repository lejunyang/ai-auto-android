# N40 Progress

本文件 append-only，记录测试先行、实现、验证和失败证据。

## Round 1

- 已确认 worktree 位于 `phase2-n40-editor-domain`，基线为 `75b0321`，开工时工作区
  干净；N39 依赖提交 `012406b` 已包含在基线中。
- 已核对 N40 只拥有 editor 生产/测试目录与本 change spec，不修改 recording core、
  Compose/UI、Gradle、protocol、路线图、真实动作执行器或其他任务目录。
- 已先落盘领域事务核心失败测试，覆盖 12 类编辑命令可逆、100 步确定性
  Undo/Redo、分支后清空 Redo、非法索引/ID 原子失败、dirty/关闭保护和 revision
  冲突双方保留；生产实现尚未开始。

## Round 2

- 已实现 `ScriptEditorSession` 与纯函数编辑命令。历史保存完整不可变脚本快照，
  insert、delete、duplicate、reorder、enable、update、selector、coordinate、
  wait、retry、failure policy 和 notes 均可精确 Undo/Redo；非法命令只返回稳定
  `EditRejectionCode`，不改变脚本或历史。
- 脚本复制生成新 ID、revision 1、统一创建/更新时间及 `manual` 来源；保存适配器只
  暴露 expected revision CAS，成功保存推进基线并清空历史，冲突保留 attempted/current
  双方且本地会话继续保持 dirty。
- dry-run 构造器只接收 snapshot、selector match、coordinate preview 和 condition
  check 四个只读端口，不存在 executor/commit 能力。歧义 selector、过期 snapshot、
  非目标包、缺失 selector、越界坐标和不满足条件均失败关闭。
- 导入预览直接复用 N39 `importScript` 严格解析；导出预览直接复用默认
  `exportScript` 脱敏。测试比较预览目录前后文件名，确认不创建临时文件，并验证不
  输出 secret、截图字节、`/data/user/0` 或 `/sdcard` 路径。
- 首次测试命令误用根 `testDebugUnitTest --tests`，App production 编译通过，但
  `web-fixture` 因同一过滤器找不到 editor 测试而失败；改用精确
  `:app:testDebugUnitTest` 后正确执行 N40 测试。
- 首次精确定向运行 15 项中 13 项通过，2 项失败均为测试夹具问题：compact JSON
  字符串替换未注入未知字段，fingerprint 夹具结构不满足 N39 约束。改为 JSON object
  注入并补合法 fingerprint 后，15/15 通过。
- 100 步历史测试使用固定 seed `40` 确定性随机选择命令、步骤和索引；100 次 Undo
  恢复原脚本，100 次 Redo 恢复最终脚本，Undo 后新分支会清空 Redo。
- 最终 editor 定向测试 15/15 通过；App `:app:testDebugUnitTest` 全量 214/214
  通过，0 failure/error/skip。中文注释检查覆盖 208 个手写文件与 14 个检查器正反例
  通过，`git diff --check` 通过。
- 修改范围仅包含本 change spec、editor 生产目录和对应测试目录；未修改 recording
  core、Compose/UI、导航、资源、Gradle、protocol、路线图、N43 或真实动作执行器。
