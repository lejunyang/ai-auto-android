# N47 固定 Fixture Production Runner 规格

## 目标

提供只接受 `api-30`、`api-33`、`api-34` 的可执行 CLI，将 N31 owned emulator
lifecycle、显式 serial/fingerprint、`clean` snapshot、Bridge 生命周期、N47
`aactl` semantic/input adapter 和无坐标
`android_visual_action_execute` 组装到仓库内 native、WebView、Canvas 三个固定无敏感
fixture。调用方不能提供 serial、package、APK、Gradle task、场景、动作、selector、
坐标或任意命令。

## 固定执行计划

- Profile 只能是 `api-30`、`api-33`、`api-34`，API level 分别固定为 30、33、34。
- 场景顺序固定为 `production-native-fixture`、
  `production-webview-fixture`、`production-canvas-fixture`。
- Package 只允许 `dev.aiauto.fixture` 与 `dev.aiauto.webfixture`；APK、构建任务和安装
  argv 均由实现内固定，不接受 CLI 输入。
- native 与 WebView 使用 N47 `aactl` semantic/input 端口；Canvas 动作只通过
  `android_visual_action_execute`，MCP 输入不含坐标、observation、candidate、hash
  或可复用租约。
- 每步由 N47 runner 在动作前后各取得 fresh observation；后置失败或提交状态未知时
  不重试、不重放，也不继续后续场景。

## Capability 与身份门

- 启动 emulator 前必须验证 N31 owned lifecycle、三个 fixture、N34 artifact、
  semantic/input Bridge、attested visual、test-only action authorization 和八类残留
  检查能力；任一缺失时 emulator 启动数和动作提交数都为零。
- `startClean` 必须返回当前 profile、API、唯一显式 emulator serial、64 位十六进制
  fingerprint、`clean` snapshot 和完整 N34 设备元数据。
- 每个场景准备前和完成后都重新验证 serial、fingerprint 与 `clean` snapshot；
  serial/fingerprint/snapshot 漂移立即失败关闭。
- 旧场景报告、错配 scenario/iteration、复用 visual observation、旧 route token 和
  畸形 MCP 结果均拒绝；可能已提交的视觉调用按 unknown commit 处理且不重放。

## 清理与残留

- N47 runner 在成功、失败、取消路径都按
  `stopScenario -> closeBridge -> clearAppData -> restoreSnapshot` 执行四步清理。
- 四步任一未完成即 `SCENARIO_CLEANUP_FAILED`，但 production orchestrator 仍必须停止
  本次 owned emulator 并检查残留。
- stop 后检查 `appData`、`bridgeSessions`、`testServices`、`screenshots`、`logs`、
  `ownedProcesses`、`runtimeFiles`、`leases` 八类残留；任一非零即失败。
- 不执行共享 `adb kill-server`，不删除非本次 runner 所有的 AVD、进程、文件或锁。

## 当前真实授权缺口

当前仓库没有可由 host production CLI 安全调用的固定授权建立入口：

1. N32 `DebugTestControlPlaneDeviceTest` 仅在 instrumentation 进程内创建只读 Bridge，
   pairing code 与最小系统设置授权不暴露给 host runner。
2. N45 `N45VisualDeviceMatrixTest` 的 Accessibility 与视觉动作授权同样封装在专用
   instrumentation harness 内，不能为独立 `aactl mcp serve` 会话建立动作 Bridge。
3. production Bridge 不允许 runner 绕过用户授权；真机、release、未知身份和普通
   emulator 均必须保持失败关闭。
4. 因此默认 production CLI 当前必须返回
   `PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE`，且在 N31 start、APK 安装、Bridge
   建立或动作之前停止。fake 端口只验证编排契约，不代表设备 capability。

未来只有新增 debug/androidTest-only、绑定 N31 serial/fingerprint、可由 host 类型化
调用且具备完整 teardown 的授权入口后，才能把该 capability 改为可用；该入口不在本
change 的允许修改范围内。

## 允许修改

- `.trae/specs/n47-production-fixture-runner/`
- `test-lab/runner/`
- 如确需 N31 复用，仅新增 `scripts/test-lab/src/` 与 `scripts/test-lab/test/` 中
  production fixture runner 专用文件

## 禁止修改

- `docs/next-phase-tasks.md`、N45、N48-N52 目录、workflow、SDK/cache
- Android/Go production、protocol、第三方 APK、账号、凭据或设备运行产物
- 不勾选 N47，不把 fake、schema、编译或 CLI fail-closed 描述为 emulator 验收

## 验收

- RED 先证明 production orchestrator、CLI 与 attested visual adapter 缺失。
- fake 覆盖固定 argv/stdin、视觉无坐标、capability 零启动、serial/fingerprint/
  snapshot 漂移、旧结果、unknown commit 不重试、成功/失败/取消四步清理和八类残留。
- 三个固定场景通过严格 N47 Schema，不包含第三方包、网络、账号、任意命令或敏感值。
- 运行 N47 Node schema/smoke、原有 59 项测试、N34 49 项测试、`make comments`、
  `make test`、`make verify`、`make build` 与 `git diff --check`。
- 本 change 不操作设备；API 30/33/34 设备闭环证据保持缺失，N47 保持未勾选。
