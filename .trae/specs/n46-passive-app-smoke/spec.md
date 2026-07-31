# N46 五包被动启动观察规格

## 目标

在 N31 disposable emulator 上复用 N46 verified descriptor 和安装同字节证明，对五个
固定官方 APK 执行一次无交互启动与一次 UIAutomator hierarchy 观察，获得后续 N48-N51
场景设计所需的最小设备证据，同时不点击同意、权限、登录、广告或任何 App 页面。

## 范围

- 固定 B站、抖音、小黑盒、微信和支付宝五个仓库文本 manifest，不接受调用方传入
  APK、manifest 路径、package、activity、serial 或任意动作。
- 每个 App 从 N31 `clean` snapshot 开始，执行 verified install、设备 `base.apk`
  拉回同字节/签名复核，然后只调用一次 `device info`、一次 package-only `launch`
  和一次 `hierarchy`。
- 启动后只做固定有界等待，不点击、输入、按键、滚动、swipe、停止 App 或重试页面。
- hierarchy 仅在内存中解析；报告只保留大小、SHA-256、节点数、观察到的包集合、
  target package 是否可见和登录/同意/权限/更新/广告/网络/模拟器布尔信号。
- 原始 XML、文本、content-desc、截图、账号、token、cookie 和第三方 APK 均不得进入
  报告、日志、工作区或 Git。
- 每个 App 后执行 `pm clear` 和 final clean restore；整轮结束停止 owned emulator，
  并确认设备、runtime、lease 与临时文件为零。

## 非目标

- 不把一次启动观察描述为 N48-N51 动作场景、兼容率、连续十轮或小程序支持。
- 不自动同意隐私协议、运行时权限、更新、登录、验证码、支付、购买或发送操作。
- 不使用 Bridge、无障碍、截图、视觉模型、私有 DOM、JavaScript 注入或任意 shell。
- 不依据 hierarchy 文本自动决定后续动作；布尔信号只用于人工可复查的保守分类。

## 允许修改

- `.trae/specs/n46-passive-app-smoke/`
- `scripts/test-lab/src/passive-smoke.mjs`
- `scripts/test-lab/src/passive-smoke-cli.mjs`
- `scripts/test-lab/test/passive-smoke.test.mjs`
- `scripts/test-lab/test/passive-smoke-cli.test.mjs`
- `scripts/test-lab/package.json`
- `docs/next-phase-tasks.md`

## 验收

- fake 端口证明固定调用顺序，任意额外参数、错误 envelope、serial/API 漂移、重复观察、
  XML/输出超限和敏感字段泄漏均失败关闭。
- 至少 API 34 完成五包 verified install、package-only launch、一次 hierarchy、
  `pm clear`、clean restore 和 stop；条件允许时扩展 API 30/33。
- 报告权限 `0600` 且不含 `xml`、`text`、`content-desc`、坐标或截图字段。
- 运行 N46 全量 Node、仓库扫描、comments、diff-check 和改动范围内构建。

## 失败与清理

- 任一 App 安装、启动、观察或元数据不匹配时立即停止后续 App，不用 UI 动作绕过。
- lifecycle 仍执行当前 App 数据清理与 final restore，外层 finally 停止 owned emulator。
- 不执行共享 `adb kill-server`、强制 worktree 删除、手工删锁或清理其他任务设备。
