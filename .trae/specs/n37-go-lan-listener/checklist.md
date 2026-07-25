# N37 验收清单

- [x] worktree 位于 `phase2-n37-lan-listener` 且基于 N36 `9d5017e`
- [x] change spec 与 RED 测试先于 listener/crypto 生产实现落盘
- [x] 合法 invitation 与 18 个 threat fixture 在 Go 中逐项一致
- [x] fingerprint、transcript、HKDF、confirmation 与低阶点 vector 一致
- [x] listener 只绑定明确接口，不默认使用 unspecified 或所有网卡
- [x] VPN、多网卡歧义、错误接口、地址归属与切网均失败关闭
- [x] invitation JSON 和手工邀请码确定一致且不包含长期或会话秘密
- [x] 无二维码库时仅提供 payload/provider 端口，不伪造图像能力
- [x] AES-GCM 每方向 nonce 唯一，AAD 绑定 transcript/方向/序号/type
- [x] 乱序、重放、篡改、过期、超时、断开和取消关闭 listener/session
- [x] 可控临时私钥、shared secret、derived keys 和 plaintext buffer 被清零
- [x] capability、风险门、目标包、deadline 和 close 仅经窄 adapter 复用
- [x] LAN token 不用于 loopback，现有 Bridge 认证和共享 CLI 未被修改
- [x] race、CLI、注释、格式与差异检查全部通过
- [x] 未提交 key、token、tag、二维码图像、缓存、日志或构建产物
- [ ] Windows 真实防火墙及 macOS/Windows 同 LAN 验收明确保持待办
- [x] 单一 Conventional Commit 含且仅含一次规定 trailer
- [x] worktree 提交后干净
