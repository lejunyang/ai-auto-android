# N47 Production Fixture 设备前置修复规格

## 目标

修复 N47 concrete authorization provider 首次运行 API 30 production fixture 时暴露的
真实 host 前置缺陷，同时保持 N31 owned emulator、固定身份、动作后复观察和四步清理
边界不变。

本 change 只修复已由设备运行证据证明的确定缺陷，不放宽 signer、profile、serial、
fingerprint、snapshot、Bridge 或 residue 门，也不因单个 API 通过而提前勾选 N47。

## 已证实缺陷

1. Node 24 的 `crypto.randomUUID` 被作为未绑定方法保存，真实场景首次生成 run ID 时
   抛出 `ERR_INVALID_THIS`；失败发生在动作前，外层清理后设备、runtime、lease 和
   临时目录均为零。
2. 单轮只安装 App 与当前 fixture，但 cleanup 对固定三个包全部执行 `pm clear`；
   未安装的第三个包使补偿被误报为 `SCENARIO_CLEANUP_FAILED`。外层 N31 finally 仍
   清零设备、runtime 和 lease。
3. 固定 build-tools `36.0.0` 的 `apksigner --print-certs` 输出连续 64 位 hex，
   parser 只接受冒号分隔 hex，因此两份实际同 signer APK 被错误拒绝。离线复核证明
   App 与 androidTest APK 都命中固定 debug signer。

## 修复边界

- 默认 UUID provider 使用绑定包装函数，测试必须在不注入 fake UUID 时生成合法 ID。
- App 数据清理只接受本轮固定 App 加唯一当前 fixture，未知或重复包失败关闭。
- Signer parser 只接受连续 64 位 hex 或严格 32 字节冒号分隔 hex；重复摘要、未知
  signer 和多 signer 继续拒绝。
- 每次失败后先确认 owned emulator、runtime、AVD/port lease、临时目录和设备列表
  清零；只有发现新的确定根因后才允许下一次复验。

## 允许修改

- `.trae/specs/n47-production-fixture-device-fixes/`
- `test-lab/runner/src/production-fixture-ports.mjs`
- `test-lab/runner/src/production-fixture-authorization.mjs`
- 对应 N47 runner 定向测试

## 禁止修改

- Android production、公共协议、N31 runner、N48-N54、release 行为、SDK/cache
- 不降低 signer、身份、动作提交、清理或残留检查
- 不把失败轮、离线测试或单 API 结果描述为完整 N47/N52 验收

## 验收

- N47 runner 全量、注释与差异检查通过。
- API 30 production fixture 至少完成 native、WebView、Canvas 三场景和八类零残留，
  或记录新的稳定 blocker 及零残留证据后停止。
- API 30 通过后再串行运行 API 33、API 34；任一失败不得隐式跳过或重试动作。
- 最终运行项目全量验证并单独提交本 change。
