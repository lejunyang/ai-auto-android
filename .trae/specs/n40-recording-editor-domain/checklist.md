# N40 验收清单

- [x] 脚本复制生成独立 ID、revision 1 和人工来源且不改变原脚本
- [x] insert/delete/duplicate/reorder/enable/update 均可 Undo/Redo
- [x] selector/coordinate/wait/retry/failure policy/notes 均可 Undo/Redo
- [x] 100 步确定性编辑可完整 Undo 和 Redo，新分支清空 Redo
- [x] 非法索引、缺失 ID、重复 ID 和不一致 replacement 原子失败
- [x] dirty state、关闭保护、放弃恢复和 revision 保存基线正确
- [x] revision 冲突保留 attempted/current 且编辑器保持 dirty
- [x] dry-run 端口只包含 snapshot、selector、coordinate 和 condition 能力
- [x] 歧义 selector、过期 snapshot 和越界 coordinate 明确失败
- [x] 导入预览严格校验且导出预览默认脱敏
- [x] 导入导出预览不创建临时文件
- [x] editor 定向测试和 App 全量单测通过
- [x] `make comments` 与 `git diff --check` 通过
- [x] 未修改 recording core、UI、Gradle、protocol、路线图或其他任务目录
