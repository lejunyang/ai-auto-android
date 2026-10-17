# Android 控制通道与 CI 加固

## 目标与边界

修复已复现的 osdk 0.0.6 Android 许可证安装失败，以及 LAN 读取预算、取消和握手缓冲交接缺陷。保持协议、工具版本、权限、动作白名单和默认绑定行为；不新增公网服务、不连接真实设备、不实现远程多租户网关。

## 顺序、范围与验收

1. CI：修改 verify/release workflow、osdk.toml、工作流回归测试和 README；仅安装编译所需工具，显式限定 Android SDK 许可证，Windows POSIX 包使用 when，模拟器依赖 lazy。原失败以发布版 osdk 0.0.6 复现；按用户后续要求改用官方 `lejunyang/one-sdk@main` Action + `version: latest`，不再固定 osdk 本体或手写安装器。Action 的 install-tools/install-deps 关闭，后续只安装项目需要的工具；不依赖 one-sdk 草稿 PR。独立提交。
2. LAN：修改 internal/bridge/lan 读取、握手和 session 生命周期；使用 net.Pipe 和内存 fixture 覆盖未换行超长输入、已取消及运行中取消、缓冲 frame 交接，保持 wire format。独立提交。
3. ADB：internal/adb/direct.go 与回归测试隔离 UI hierarchy 临时文件，dump 失败也清理；不同调用不得覆盖或删除对方产物。独立提交。
4. 历史 CI：test-lab/artifacts/test/collector.test.mjs 的严格单文件断言使用原生路径分隔符，保留全部防泄漏检查；Node pin 满足现有 engines >=24。验证 artifacts/matrix smoke 与三平台 Actions。新 CI 暴露的 Windows stat.mode 断言拆成 POSIX 专项，Linux/macOS 继续检查 0600，所有平台仍检查原子发布与清理；Windows ACL 未验收。
5. Android CI：SessionCoordinatorTest 的活跃 engine mock 改为长期挂起，并使用同一虚拟调度器推进状态/历史/恢复；实际推进 5 秒验证停止超时，保留无终态返回的独立测试。修复前 Actions 出现状态/stop 竞态；定向单测、全量单测/assemble/lint 验收。
6. 会话历史：SessionCoordinator 写历史前捕获开始时间，避免 IO 延迟时混入下一会话时间；独立双调度器 RED/GREEN 回归验证。
7. MCP 视觉测试：客户端收包不等价服务端 Write 已返回；新增 test-only transport 完成事件，同步后仍严格验证写后清零/无 pending，修复复现的断言竞态。
8. 评估：docs 记录能力矩阵、安全边界、共享契约建议、远程 SSH stdio 配置与未验证平台。执行 make test/verify/build/comments、Node portability、Go race、Android 单测/构建/lint；推送独立分支及 draft PR 并监控 CI。

本轮不并行派发代码修改。失败时保留测试证据，回滚限定提交即可；隔离工具链缓存位于仓库外 /workspace/android-tools。真实 ADB、USB/Wi-Fi、无障碍授权和跨设备 LAN 未执行。
