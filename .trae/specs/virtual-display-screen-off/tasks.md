# 虚拟显示与息屏运行任务

依赖与波次：T1 → T2 → T3 → T4 串行，同一 worktree、同一分支，由单一执行者依次
完成。T1 定下设备端协议后 T2 才能开始；规格状态文件（本文件、`checklist.md`、
`progress.md`）只由当前任务的执行者修改。

## T1：设备端辅助进程 `displayd`

目录所有权：`android/displayd/`、`android/settings.gradle.kts`。

- [x] 先写失败测试：请求解析、尺寸与数量范围校验、对端 uid 判定、消息超限拒绝
- [x] 新建 Gradle 模块，产出可作为 `app_process` CLASSPATH 的 APK
- [x] 以 shell 身份创建虚拟屏（标志固定，API 33 下限，API 34+ 才设独立焦点）
- [x] `localabstract` 服务端：对端凭据校验、有界按行 JSON、帧的二进制返回
- [x] 方法：`hello`、`display.create|list|close|capture`、`status`、`shutdown`
- [x] 常驻脱离 `adb shell` 会话；退出时释放全部虚拟屏
- [x] 保持虚拟屏显示组唤醒；凭据锁定时不唤醒，`display.capture` 返回锁定错误
- [x] 模拟器冒烟：用原始 `adb` 命令启动辅助进程并走通 create → capture → shutdown
- [x] `make comments`、`make android-verify`
- [x] 提交：`feat(displayd): add shell-uid virtual display helper`

证据：Round 1（29 项 JVM 单测、API 35 原始 adb 冒烟）；锁定策略为实现与单测证据，
PIN/息屏/超时端到端仍属 T4。API 33 的 `groupAwake=null` 限制见 Round 1 与模块 README。

## T1b：复核缺陷修复（T2 前置）

目录所有权：`android/displayd/` 与本规格状态文件，串行汇合点为本阶段提交。
失败时停止后续阶段，shutdown、移除本次 forward、删设备临时文件、清 PIN、关闭模拟器。

- [x] 先写失败测试：休眠组拒帧、API 33 非 ON 拒帧、拒绝连接关闭异常隔离
- [x] 编码前后检查组状态，新增 `DISPLAY_ASLEEP`；API 33 查询限制如实记录
- [x] 调查固定框架唤醒路径，采用明确错误 + 关闭重建，README 说明销毁语义
- [x] API 35 PIN 往返：锁定拒帧、解锁后休眠拒帧、重建恢复正常帧
- [x] `make comments`、`make android-verify`
- [x] 提交：`fix(displayd): reject capture while the display group is asleep`

## T2：Go 侧显示参数与辅助进程客户端

目录所有权：`internal/adb/`、`internal/vdisplay/`、`internal/apperr/`。

- [x] 先写失败测试（fake-adb 与假服务端）
- [x] `Forward` 支持 `localabstract`
- [x] 点按、滑动、按键、文本、启动、层级增加可选目标显示；不带参数时逐字不变
- [x] `internal/vdisplay`：部署与 SHA-256 校验、启动、探活、类型化客户端、清理
- [x] 稳定错误：显示不存在、辅助进程未运行、API 过低、设备已锁定、显示组休眠、身份无法验证
- [x] `make test`
- [x] 提交：`feat(adb): target virtual displays through displayd`

## T3：service 与 CLI 出口

目录所有权：`internal/service/`、`internal/cli/`、`protocol/`（仅 `device.sleep`）。

- [ ] 先写失败测试（命令面与 service 一致性）
- [ ] `aactl display start|status|stop|create|list|close`
- [ ] `observe screenshot|hierarchy` 与 `action tap|swipe|text|key|launch` 的 `--display`
- [ ] `aactl action sleep`（`device.sleep`）
- [ ] 任何失败都不回退到主屏执行
- [ ] `make test`、`make verify`、`make build`
- [ ] 提交：`feat(cli): add virtual display commands and screen-off action`

## T4：模拟器端到端验收与文档

目录所有权：`scripts/emulator/`（新增脚本）、`docs/`、`README.md`、`skills/`。

- [ ] 验收脚本按规格「验收」第 1–5 步执行，显式 serial，记录设备指纹
- [ ] 连续运行 3 次并记录结果；失败产物限额、脱敏，验收后清理
- [ ] `docs/architecture.md`、`docs/protocol.md`、`docs/next-phase-tasks.md`、
      `docs/README.md`、`README.md` 与相关 skill 说明
- [ ] 后续项登记进 `docs/next-phase-tasks.md`：MCP 出口、发布打包、真机矩阵
- [ ] 全量：`make comments`、`make test`、`make verify`、`make build`、
      `make android-verify`
- [ ] 提交：`test(emulator): verify virtual display under screen-off` 与
      `docs: document virtual display and screen-off operation`

## 实际提交

- T1：`feat(displayd): add shell-uid virtual display helper`（本轮单一提交）。
