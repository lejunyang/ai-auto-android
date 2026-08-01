# N43 Clean Snapshot 重复矩阵验收清单

- [x] 只接受固定 profile，不接受 serial、round、task 或测试类参数
- [x] 每 profile 恰好 20 轮，每轮先 restore clean snapshot
- [x] 每轮绑定同一明确 serial、profile fingerprint 和唯一在线 emulator
- [x] 每轮只运行一次固定 N43 instrumentation test
- [x] JUnit XML 必须新鲜、唯一且 test/failure 数一致
- [x] 产品失败与基础设施失败严格分类，不保存 stack 或页面内容
- [x] 每 profile 成功率不低于 95% 才勾选
- [x] 最终设备/runtime/lease/temp 为零，报告权限为 0600
- [x] 定向、Web fixture 构建、comments 与 diff-check 通过
- [x] 提交身份、trailer、集成和 worktree 清理正确
