# Android 控制通道与 CI 加固

## 目标与边界

修复已复现的 osdk 0.0.6 Android 许可证安装失败，以及 LAN 读取预算、取消和握手缓冲交接缺陷。保持协议、工具版本、权限、动作白名单和默认绑定行为；不新增公网服务、不连接真实设备、不实现远程多租户网关。

## 顺序、范围与验收

1. CI：修改 verify/release workflow、osdk.toml、工作流回归测试和 README；仅安装编译所需工具，显式限定 Android SDK 许可证，Windows POSIX 包使用 when，模拟器依赖 lazy。以发布版 osdk 0.0.6 验证，不依赖 one-sdk 草稿 PR。独立提交。
2. LAN：修改 internal/bridge/lan 读取、握手和 session 生命周期；使用 net.Pipe 和内存 fixture 覆盖未换行超长输入、已取消及运行中取消、缓冲 frame 交接，保持 wire format。独立提交。
3. ADB：internal/adb/direct.go 与回归测试隔离 UI hierarchy 临时文件，dump 失败也清理；不同调用不得覆盖或删除对方产物。独立提交。
4. 历史 CI：test-lab/artifacts/test/collector.test.mjs 的严格单文件断言使用原生路径分隔符，保留全部防泄漏检查；Node pin 满足现有 engines >=24。验证 artifacts/matrix smoke 与三平台 Actions。
5. 评估：docs 记录能力矩阵、安全边界、共享契约建议、远程 SSH stdio 配置与未验证平台。执行 make test/verify/build/comments、Node portability、Go race、Android 单测/构建/lint；推送独立分支及 draft PR 并监控 CI。

本轮不并行派发代码修改。失败时保留测试证据，回滚限定提交即可；隔离工具链缓存位于仓库外 /workspace/android-tools。真实 ADB、USB/Wi-Fi、无障碍授权和跨设备 LAN 未执行。
