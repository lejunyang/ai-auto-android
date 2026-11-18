# 固定 Emulator Runner

本目录实现 N31 的 API 30、33、34 模拟器生命周期。SDK、AVD、锁、日志和 package
receipt 必须放在仓库外；命令要求显式传入所有根目录和 profile。

```bash
node scripts/emulator/cli.mjs verify \
  --profile api-33 \
  --sdk-root "$ANDROID_SDK_ROOT" \
  --avd-root "$ANDROID_AVD_HOME" \
  --java-home "$JAVA_HOME" \
  --state-root "$AACTL_EMULATOR_STATE"
```

首次生命周期依次为 `create`、`start-cold`、`wait`、`save-snapshot`、`stop`；
后续运行使用 `start`、`wait`、`restore`、`stop`，不需要时执行 `delete`。只有
`start-cold` 允许跳过固定快照，防止常规测试静默从脏状态启动。每个成功响应都返回
明确 AVD、API、serial 和可用时的设备指纹；错误响应使用稳定 `code`。

`package-receipts.json` 由安装流程在 state root 生成，记录官方 SDK archive 的
SHA-256 和已安装 revision。profile 中的 revision/hash 必须与 receipt 逐字一致，
缺失或无法解析时 runner 拒绝启动。停止失败时运行时记录和双重锁会保留，操作员应先
检查对应 PID/serial，再重试 `stop`，不得直接删除锁或执行共享 `adb kill-server`。

### 虚拟摄像头关联保留回归

verify-virtual-camera.mjs另验证start --virtual-camera→记录编号→stop --keep-camera-association
后编号不变且helper/临时文件无→再次start同号复用→默认stop移除；最终仍要求无工具关联。
每项前冷启动osdk-test-35，使用-read-only -no-snapshot -no-boot-anim -no-audio，
boot_completed后再等待45秒。该等待不是已证明的flaky根因修复，不放宽判据。
脚本结束关闭模拟器；只允许emulator-5554，发现其它设备停止，不触碰真机。
模拟器编号保留不证明虚拟CAMERA授权跨helper重启，真机人工验收仍待执行。
