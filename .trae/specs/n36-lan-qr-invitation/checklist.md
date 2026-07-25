# N36 验收清单

- [x] invitation 字段严格且 QR 不含长期秘密或最终凭证
- [x] 只允许私网或链路本地地址并绑定显式网卡与端口
- [x] TTL、过期和 invitation ID/nonce 重放在网络连接前拒绝
- [x] X25519 公钥、短指纹和字段篡改失败关闭
- [x] 固定 X25519、HKDF、AEAD、transcript 与双方确认分域
- [x] capability/version 协商不允许明文或旧版本降级
- [x] LAN 与 loopback Bridge token、配对和会话语义完全分离
- [x] 合法及全部威胁 fixture 通过预期契约
- [x] 协议全量验证、定向测试、`make comments` 和 `git diff --check` 通过
- [x] 未修改 Android/Go 生产实现、动作白名单或现有 Bridge 认证
