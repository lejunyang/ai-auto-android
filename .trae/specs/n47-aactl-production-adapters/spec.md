# N47 aactl Production Adapter 规格

## 目标

把 N47 Runner 的窄端口接到固定身份的 `aactl` CLI、已建立的 loopback Bridge 和注入
的 N31 emulator lifecycle。Adapter 只使用显式 serial、固定 argv、严格 JSON 信封和
类型化动作，不运行 shell、不发现/切换设备、不建立或绕过人工授权。

## 适配范围

- observer：通过 `aactl bridge snapshot --device SERIAL --package PACKAGE --json`
  读取脱敏语义树，生成短期 observation ID、前台包和保守页面分类。
- conditions：在同一语义 observation 上验证 `foreground-package`、
  `semantic-state`、`node-present`、`node-absent`；`visual-state` 在未注入视觉
  verifier 时失败关闭。
- router：从语义节点动作能力选择 `semantic` route 并生成不透明 token；
  `hybrid/visual` 未接 N45 production port 时明确 `ROUTE_UNAVAILABLE`，不猜坐标。
- executor：`tap/long-click/scroll/back/home/recents` 只经
  `aactl bridge action`；`launch/switch-app` 只经 `aactl action launch`。
  每次固定 argv、显式 serial、`shell:false`，并严格区分明确拒绝、明确成功和调用异常。
- `input` 暂不接 production adapter：现有 `aactl bridge action --action` 会把明文
  放入进程 argv。未增加固定 stdin action 模式前返回 `INPUT_ADAPTER_UNAVAILABLE`，
  不以 argv 或剪贴板降级。
- lifecycle：只调用注入的 `stopScenario`、`closeBridge`、`clearAppData`、
  `restoreSnapshot` 类型化方法；adapter 不发送 raw ADB 或删除共享设备状态。

## 安全边界

- aactl 必须是绝对路径、普通文件、非符号链接，并在 adapter 创建时绑定 dev/inode、
  size 与 SHA-256；每次执行前复核，防止路径替换。
- stdout 只接受单个严格 N47 CLI envelope；拒绝未知字段、重复 JSON key、尾随值、
  超限输出、错配 requestId、`ok/error/data` 冲突和 stderr 非空成功。
- action JSON 由 adapter 构造固定字段，不接收调用方 raw JSON；input 明文不进入
  `execFile` argv、token、report 或异常。
- semantic target 只允许唯一匹配、目标包一致、可见、启用且具备对应 action；
  歧义、密码/敏感节点、未知页面、能力缺失和 snapshot 漂移均在动作前失败。
- 页面分类只基于固定无敏感模式标签；广告、更新、登录墙、模拟器检测、网络失败与
  unknown 不自动关闭。
- Bridge 必须由用户或 disposable emulator test-only 流程预先建立；adapter 不读取
  配对码、不打开相机/无障碍、不修改系统设置。

## 允许修改

- `.trae/specs/n47-aactl-production-adapters/`
- `test-lab/runner/adapters/`
- 对应 adapter 测试和文档

## 禁止修改

- N47 Runner core/Schema/fixture、Go CLI、Android 生产代码、N31/N34/N45 实现
- raw ADB、shell、坐标猜测、第三方 APK、账号、凭据或设备运行产物
- 公共路线图；由 main 集成后统一更新

## 验收

- RED 测试先证明 adapter 模块缺失。
- fake `execFile` 覆盖 snapshot、唯一节点、全部 semantic action、launch/switch、
  strict envelope、路径替换、输出预算、stderr、拒绝/异常提交状态和 secret redaction。
- visual/hybrid、visual-state 和敏感/歧义节点动作提交数为零。
- lifecycle fake 覆盖四步透传与失败传播；不执行 raw ADB。
- adapter 定向测试、N47 25 项、comments 和 diff-check 通过。
- 真实 emulator/Bridge 与 N45 visual production adapter 保持未验收。
