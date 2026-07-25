# N32 Debug-only Test Control Plane 规格

## 目标

为 N31 管理的 disposable emulator 提供仅存在于 debug/androidTest 变体的测试控制面，
使设备矩阵能够无人值守地查询状态、编排测试无障碍服务、生成 Bridge 测试配对状态、
预置无敏感数据脚本并读取自检结果，同时保证 release APK 不包含任何测试入口。

## 范围

- 在无 Android 依赖的安全内核中签发一次性、短期 test token。
- token 明确绑定 scope、emulator serial、N31 AVD fingerprint、debug App 签名摘要、
  instrumentation 调用方签名摘要和固定 test-only marker。
- debug 适配层只生成受控测试意图并复用现有 Bridge、录制存储和可访问性状态能力。
- androidTest 使用 `UiAutomation` 在 disposable emulator 内完成 test-only 编排；
  不给生产 App 增加写系统安全设置权限。
- 提供 release APK 静态检查，拒绝测试组件、权限、字符串、marker 和代码路径泄漏。

## 非目标

- 不修改 main/release 源集、生产 Bridge 协议、普通真机授权流程或动作白名单。
- 不在真机上自动启用无障碍、ADB RSA、MediaProjection 或其他系统安全授权。
- 不把 test token 写入日志、测试报告、异常消息、持久化文件或 Bridge 响应。
- 不由本任务注册共享 Gradle 模块或修改 App 的 debug/androidTest 依赖。

## 安全模型

1. test token 由 256-bit 安全随机数生成，最长存活五分钟；内存只保留 SHA-256，
   明文只在签发结果中返回一次。
2. 每个 token 只授权一个 scope 和一次请求。任何授权尝试都会先消费 token，
   包括 scope、身份或构建变体校验失败，避免攻击者重复试探。
3. serial 必须匹配 `emulator-PORT` 且与签发绑定值完全一致；缺失、真机 serial 或
   identity 漂移均失败关闭。
4. N31 AVD fingerprint、debug App 签名、instrumentation 调用方签名和
   `AI_AUTO_TEST_ONLY_V1` marker 必须同时匹配；任何值无法读取时不得降级。
5. 仅接受 `debug` 构建；控制面不注册 exported 组件、不新增权限，也不进入 release。
6. scope 仅包括状态读取、测试无障碍控制、Bridge 配对状态生成、无敏感脚本预置和
   自检读取；未知或越权 scope 在执行副作用前拒绝。

## 允许修改

- `.trae/specs/n32-debug-test-control-plane/`
- `android/app/src/debug/`
- `android/app/src/androidTest/`
- `android/test-control/core/`

## 验收

- core 单元测试覆盖成功、过期、重放、scope 越权、真机 serial、serial 漂移、
  fingerprint、App/调用方签名、marker、release 构建和非法 token。
- API 30、33、34 通过主线程共享 Gradle 集成后，可自动建立测试 Bridge 并完成一次
  只读 snapshot，结束后撤销 token、关闭 Bridge、禁用测试服务并清理测试脚本。
- release APK 静态检查确认测试组件、额外权限、marker、控制动作和 class 路径均为零。
- 运行定向单元测试、`git diff --check` 和 `make comments`。

## 失败与清理

- 授权失败不执行任何副作用，已提交 token 保持已消费。
- 设备流程失败时按显式 serial 停止 Bridge、恢复原无障碍设置并删除测试脚本。
- 不执行共享 `adb kill-server`，不清理其他任务的设备、AVD、缓存或授权。
