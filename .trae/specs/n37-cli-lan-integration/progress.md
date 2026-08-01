# N37 LAN CLI 接线进度

本文件 append-only，记录 CLI 测试先行、生命周期验证和未覆盖平台边界。

## Round 1

- worktree `/private/tmp/ai-auto-n37-cli` 位于
  `phase2-n37-cli-integration`，基线 `3e79bf9`，开工时干净。
- 已复核 N37 核心具备明确网卡选择、单地址 binder、invitation、accept、加密
  session 和清理语义；共享 CLI 尚未注册任何 LAN 命令。
- CLI 普通响应只会在命令结束后写出单个 JSON 信封，不适用于“先展示 invitation、
  再阻塞 accept”的 listener 生命周期。本任务为该子命令增加显式流式 JSON 事件，
  不改变其他命令的单信封契约。
- 测试使用固定 fake interface/binder；localhost 只用于 integration smoke，不代表
  macOS/Windows 同 LAN 或 Windows 防火墙验收。

## Round 2

- RED 测试先落盘，`go test ./internal/cli -run TestLAN` 按预期因 App 缺少 LAN 依赖、
  结果类型、listener/session 窄接口和导出 capability 常量而编译失败；共享 Go cache
  的沙箱权限问题先单独修正，未记作 RED 证据。
- `App.Run` 在 ADB locator 之前分流 `bridge lan`，因此 `interfaces` 和 `listen`
  不解析 ADB。其他命令继续使用原有单信封执行路径。
- `interfaces` 只输出 N37 核心分类的合格候选并明确标记 tunnel；`listen` 同时要求
  `--interface` 与 `--address`，拒绝 tunnel、wildcard、未知候选和隐式默认。
- `listen` 先写 invitation 协议信封，再阻塞一次 accept；成功只输出已认证
  endpoint、capability 和过期时间并立即安全关闭 session，不暴露 token、私钥、
  derived key、confirmation tag 或二维码 bytes。
- invitation 输出失败不会进入 accept，并立即关闭 pending listener。timeout、
  cancel、切网和认证失败都输出稳定 `LAN_*` code，关闭端口并撤销 invitation。

## Round 3

- N37 CLI 定向 6 个顶层测试（含 2 个生命周期子场景）通过，覆盖无 ADB 网卡发现、
  流式 invitation、timeout、cancel、切网、显式选择、secret redaction、已认证
  session 关闭和输出失败清理。
- `go test -race ./internal/bridge/lan ./internal/cli ./cmd/aactl` 通过；LAN core 与 CLI
  均无 race 报告。
- 生产 `aactl bridge lan interfaces --json` smoke 成功，发现当前 `if-16-en1`
  的 `192.168.31.16` 私网候选并把 utun 接口标记为 tunnel；命令未解析 ADB。
- 在明确 `if-16-en1 / 192.168.31.16` 上运行 100ms 真实 listener timeout smoke：
  先输出 payload-only invitation，再返回 `LAN_ACCEPT_TIMEOUT` 和退出码 5；临时端口
  `51791` 随后不可达。该证据仅证明本机单地址绑定与清理，不是同 LAN 互操作结论。
- `make test`、`make verify`、`make build`、`make comments` 和 `git diff --check`
  全部通过。协议 52 项、3 个 skills、repository metadata 和 Go 全量测试均成功。
- CLI 当前是一次性握手验证模式，认证后立即关闭；持续双向 RPC、Android 真实同 LAN、
  macOS/Windows 互操作和 Windows 防火墙仍未验收，路线图 N37 必须保持未完成。

## Round 4

- 实现提交 `b38235b`（`feat(cli): connect lan bridge listener`）包含 change spec、
  类型化 LAN CLI、早期无 ADB 分流、测试和 capability 窄导出。
- 提交 author/committer 均为 `lejunyang <lejunyang@qq.com>`，消息含且仅含一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`；实现提交后 worktree 干净。

## Round 5

- 独立 change `n37-n38-qr-integration` 为生产 CLI 默认注入纯 Go、无 CGO 的固定
  Version 28-L QR Model 2 provider，并通过 invitation 事件输出有界
  `terminal-utf8-v1` representation；不调用外部命令、不打开 GUI、不创建二维码文件。
- 仓库内 decoder 与 `/private/tmp` 独立 `gozxing` 均能精确还原 N36 payload；后者
  实际确认 Version 28、EC L、1921 codewords。首轮跨实现 checksum 失败暴露并修正
  alignment function map 后才记为通过。
- QR 容量不足仅降级为 `payload-only`，不影响 `manualCode` 或 listener；取消和
  provider 内部错误仍失败关闭。Go LAN 62 项、CLI 82 项、定向 race 及仓库全量门禁
  通过。Windows 同 LAN、防火墙和真实 Wi-Fi/热点/VPN 矩阵仍未由本 change 验收。
