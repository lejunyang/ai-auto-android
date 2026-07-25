# N40 录制编辑器领域层规格

## 目标

在不依赖 Compose UI、导航或真实动作执行器的前提下，为不可变
`AutomationScript 1.1` 提供可测试、可撤销且 revision 安全的编辑事务。

## 范围

- 支持脚本复制和步骤 insert、delete、duplicate、reorder、enable、update。
- 支持 selector、coordinate、wait、retry、failure policy 和 notes 编辑。
- 提供 Undo/Redo、dirty state、保存 revision 基线、未保存变更保护及冲突数据。
- dry-run 仅通过只读端口获取 snapshot、匹配 selector、预览坐标和检查条件。
- 导入预览复用 N39 严格解析；导出预览复用 N39 默认脱敏输出且不创建临时文件。

## 安全边界

- 编辑命令只返回新的不可变脚本；非法索引、ID、重复 ID 或非法值不得产生部分修改。
- dry-run API 不接收动作执行器，歧义 selector、过期 snapshot 和越界坐标必须失败关闭。
- revision 冲突保持 attempted/current 双方，不能覆盖较新脚本或清除本地 dirty 状态。
- 导入和导出预览不得写文件，不得泄露 secret、截图字节或设备本地路径。
- 本领域层不修改 N39 core model/store/migration/repository，不接入生产 UI 或回放执行器。

## 允许修改

- `.trae/specs/n40-recording-editor-domain/`
- `android/app/src/main/java/dev/aiauto/android/automation/recording/editor/`
- `android/app/src/test/java/dev/aiauto/android/automation/recording/editor/`

## 验收

- 每个编辑命令均有 Undo/Redo 反向操作测试。
- 100 步确定性编辑可逐步 Undo 回原脚本，并可完整 Redo；新分支清空 Redo。
- 非法索引、ID 和重复 ID 原子失败且不改变历史。
- 保存成功推进 revision 基线；冲突保留 attempted/current，未保存变更受到关闭保护。
- dry-run 完成只读观察并证明动作提交能力不存在；歧义、过期和越界均返回稳定失败。
- 严格导入和脱敏导出预览不创建临时文件且不包含敏感制品。
- editor 定向测试、App 全量单测、`make comments` 和 `git diff --check` 通过。
