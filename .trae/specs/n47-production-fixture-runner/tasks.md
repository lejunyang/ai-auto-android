# N47 固定 Fixture Production Runner 任务

- [x] 建立独立 branch/worktree 并核对并行 worktree
- [x] 审阅 N31 lifecycle、N47 adapter、N34 artifact、N45 visual port 与 N52 residue
- [x] 记录 Bridge/Accessibility test-only 授权无法安全由 host 组装的精确缺口
- [x] RED：固定 CLI、capability 零启动、身份漂移、旧结果与清理测试
- [x] GREEN：实现固定 production orchestrator、attested visual adapter 与 CLI
- [x] 固定 native/WebView/Canvas production scenario definitions
- [x] 通过 N47/N34 定向测试和仓库最终验证
- [ ] 审查允许修改范围并创建单一职责 Conventional Commit

## 依赖与汇合点

- N31 仅作为注入式 owned emulator lifecycle；不修改其共享实现。
- N47 runner 与 `aactl` adapter 是执行内核；production orchestrator 是唯一集成点。
- N45 只复用已存在的 `android_visual_action_execute` MCP 契约，不修改 N45 文件。
- 规格状态只写本目录；不更新路线图，也不勾选 N47。

## 提交边界

建议单提交：`feat(test-lab): add fixed production fixture runner`。

## 失败回滚与清理

- 测试仅使用 fake 端口和临时目录，不启动设备、不写 SDK/cache。
- 实现失败时删除本 change 新增文件即可，不回退或覆盖并行 worktree。
- production 执行失败时仍完成四步场景清理、owned emulator stop 和八类残留检查。
