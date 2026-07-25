# aactl Production Adapter

`aactl.mjs` 将 N47 Runner 的 semantic 端口接到固定身份的 `aactl` 与已建立的 App
Bridge。创建 adapter 前必须由调用方完成：

- 明确选择唯一 serial；
- 用户手动启用/配置无障碍与桌面 Bridge，或在 disposable emulator 中使用已验收的
  test-only 预置；
- 通过 N31 lifecycle 提供 stop、close、clear 和 restore 类型化方法；
- 提供严格 condition catalog、value resolver 与 N34 artifact collector。

## 安全配置

adapter 只接受仓库外绝对 `aactl` 路径，并绑定文件 identity 与 SHA-256。每次调用都
复核 identity，再以 `execFile`、固定 argv、`shell:false`、显式 serial 和步骤剩余
deadline 执行。

`systemPackages` 只列出当前固定 AVD 的 launcher/SystemUI 包，用于 Home/Recents
后的观察与显式 `switch-app` 恢复。不得填入任意第三方包来绕过 Runner 的前台包门。

condition catalog 是声明式对象，只支持：

- `foreground-package`
- `semantic-state` 的 `equals`/`contains`
- `node-present`
- `node-absent`

不接受回调函数、任意代码或 visual predicate。

## 当前限制

- 只接 `semantic` route；`hybrid/visual` 和 `visual-state` 明确失败关闭。
- `input` 未接 production：现有 CLI `--action` 会暴露 argv 明文，adapter 返回
  `INPUT_ADAPTER_UNAVAILABLE`。必须先实现固定 stdin action 模式。
- 不负责 Bridge open、配对码、无障碍授权、设备发现、APK 安装或模拟器创建。
- fake `execFile` 测试不等于 emulator 或真实 App 验收。
