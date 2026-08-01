# N47 原子视觉动作 Skills 进度

本文件 append-only，记录安全说明、镜像同步和验证证据。

## Round 1

- 审计确认新 MCP 工具是 destructive 原子 `propose-and-execute`，而既有
  `android_visual_target_propose` 在响应写出后已关闭 observation lease。
- 观察 Skill 现明确 proposal 不能作为未来动作授权；自动化 Skill 新增无坐标输入、
  debug disposable emulator、Bridge/Accessibility 前提、成功四字段判据和人工确认
  边界。
- 恢复参考新增 commit status 规则：not-committed 只能在 fresh observation 后重新
  决策，unknown/null 禁止重试，不能复用 proposal evidence。
- 首次 `make skills-smoke` 正确发现只更新 Trae mirror；同步 canonical `skills/`
  后，3 个 portable Skills 和 mirror 校验通过，CLI 示例 Go 测试通过，comments 与
  diff-check 通过。

## Round 2

- 实现已由 `d4ad454` `docs(skills): document attested visual actions` 落入
  `main`；Author/Committer 均为 `lejunyang <lejunyang@qq.com>`，要求的
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>` trailer 恰好一次。
- 本规格仅补记已验证实现的独立 change spec 与收口证据，不改变 N47 路线图状态或
  任何设备授权结论。
