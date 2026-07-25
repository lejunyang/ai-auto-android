# N37 LAN CLI 接线验收清单

- [x] 独立 worktree、分支和允许修改范围符合约束
- [x] LAN interfaces/listen 命令不解析 ADB
- [x] 网卡 ID 与地址候选都必须显式选择
- [x] invitation 事件先于 accept 阻塞输出
- [x] stdout 不包含私钥、LAN token、derived key 或 tag
- [x] 未注入 QR provider 时不伪造二维码
- [x] timeout、cancel、信号和切网关闭端口并清零秘密
- [x] 重复 accept 不恢复 listener 或 invitation
- [x] race、test、verify、comments 和 diff-check 通过
- [x] 真实单地址 listener timeout 只标为本机 integration smoke
- [ ] macOS/Windows 同 LAN 与 Windows 防火墙保持未验收
- [x] 实现提交身份正确且 trailer 恰好一次
- [x] 实现提交后 worktree clean
- [ ] 规格证据提交身份正确且 trailer 恰好一次
