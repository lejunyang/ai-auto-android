# N40 Tasks

- [x] Task N40.1：先定义编辑命令、事务历史与冲突的失败测试
- [x] Task N40.2：实现不可变脚本复制和所有步骤编辑命令
- [x] Task N40.3：实现 Undo/Redo、dirty state、关闭保护与 revision 保存基线
- [x] Task N40.4：实现只读 dry-run 端口和歧义、过期、越界失败语义
- [x] Task N40.5：实现严格导入和默认脱敏导出预览适配器
- [x] Task N40.6：完成 100 步确定性历史、原子失败和全量回归验证
- [x] Task N40.7：以 `feat(recording): add script editor domain` 单一提交

## 依赖

N40 依赖 N39 的 AutomationScript 1.1 和安全 store 契约；依赖提交
`012406b` 已位于当前基线。N41 只能消费 N40 公开领域接口，不得反向把 UI 状态写入
本目录。
