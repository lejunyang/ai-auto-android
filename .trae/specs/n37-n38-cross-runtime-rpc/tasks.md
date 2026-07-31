# N37/N38 真实跨运行时 LAN RPC 任务

- [x] 审计生产 Go listener/session、Kotlin outbound session 和 N31/N32 设备边界
- [x] 创建独立 change spec、分支和 worktree
- [x] 增加固定 invitation 参数解析与真实 Android socket instrumentation
- [x] 增加仅支持 `AUTH_INVALID` 的 test-only Go host
- [x] 增加固定 profile/interface/address orchestration 与参数拒绝测试
- [x] 修复 Android 11 缺失平台 X25519 provider 的同契约安全回退
- [x] 在 API 30/33/34 运行多轮只读 RPC、致命认证和关闭矩阵
- [x] 运行定向、全量、构建、注释、差异和零残留检查
- [x] 创建单一职责提交并集成

## 建议提交

`test(lan): verify go kotlin rpc interoperability`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
