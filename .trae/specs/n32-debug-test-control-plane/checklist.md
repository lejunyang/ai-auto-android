# N32 验收清单

## 规格与边界

- [x] 独立 change spec 已创建，`progress.md` 保持 append-only。
- [x] 允许和禁止修改范围已记录。
- [x] release 不包含测试入口的静态证据已取得。
- [x] 未修改 main/release、共享 Gradle、协议清单或路线图。

## 安全控制

- [x] token 为一次性短期随机值，存储和日志不保留明文。
- [x] scope、serial、fingerprint、双签名、marker、build variant 全量绑定。
- [x] 过期、重放、越权、真机和不确定状态均失败关闭。
- [x] 测试脚本不含 secret、截图、设备路径或外部副作用。

## 自动验证

- [x] core 单元测试全部通过。
- [x] debug/androidTest 通过临时串行集成构建。
- [x] API 30/33/34 Bridge 与只读 snapshot 设备矩阵通过。
- [x] `git diff --check` 通过。
- [x] `make comments` 通过。
- [x] worktree 提交后干净。

## 人工边界

- [x] 真机无障碍、ADB RSA 和系统安全授权仍要求用户操作。
- [x] 控制面不自动确认登录、验证码、支付、购买、发送或安全设置。
- [x] 模拟器证据不描述为真机授权可绕过。
