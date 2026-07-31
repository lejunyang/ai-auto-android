# N46 N31 安装 Runner 规格

## 目标

为 N46 `runVerifiedExternalAppLifecycle` 提供生产类型化端口，在明确 N31 clean AVD
上安装 verified descriptor，并用设备端 `base.apk` 拉回后的同字节、同签名检查证明
安装结果，不开放任意 APK 或通用安装命令。

## 允许修改

- `.trae/specs/n46-n31-install-runner/`
- `scripts/test-lab/src/n31-install-runner.mjs`
- `scripts/test-lab/test/n31-install-runner.test.mjs`
- `scripts/test-lab/src/verified-install-cli.mjs`
- `scripts/test-lab/package.json`
- `.trae/specs/n46-*/progress.md` 的 append-only 证据

## 禁止修改

- Go `aactl`、MCP、Android App、协议、公共路线图和 N31 runner 行为
- 任意 executable/argv、任意 APK 路径、真机 serial、非 clean snapshot
- raw shell 字符串、root、remount、未知来源授权、系统安装确认绕过

## 安全契约

- descriptor 必须由当前进程的 N46 verifier 私有 provenance 签发。
- serial、profile、N31 runtime fingerprint 和 `clean` snapshot 必须逐项一致。
- ADB 只执行固定 argv：install、`pm path`、pull、`pm clear`。
- 设备必须只返回一个 `base.apk`；拉回 artifact 后重新验证原 size/SHA-256 和六项
  inspector 元数据。
- 成功、失败和取消最终都恢复 clean snapshot并删除外置临时拉回文件。

## 验收

- fake runner 覆盖合法安装、错误 serial/profile/fingerprint、split/多 base、pull
  替换、metadata mismatch、clear/restore 失败。
- API 30/33/34 对五个 verified 官方 APK 完成安装、同字节复核和 clean 恢复。
