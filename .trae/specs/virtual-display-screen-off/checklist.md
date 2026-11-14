# 虚拟显示与息屏运行验收清单

已完成项必须有实现、测试或设备证据后才能勾选。

## 范围与流程

- [x] 独立 change spec、独立分支与 worktree，单一职责 Conventional Commit
- [x] 未回写任何既有规格的验收结论
- [x] 未修改 `android/app/`、`internal/mcpserver/`、`internal/bridge/`
- [x] 未触碰文件传输出口与 `TestFileTransferHasNoCLIEntryPoint`
- [x] 未 push 到远端

以上为 T1 本轮提交范围核对，后续任务仍须逐轮遵守。

## 安全边界

- [x] 辅助进程只监听 `localabstract`，无 TCP 监听
- [x] 每个连接校验对端 uid，只接受 0 与 2000，有测试
- [x] 方法集合类型化，无任意命令执行入口
- [x] 消息、帧尺寸、显示数量都有上限且有超限测试
- [x] 虚拟屏标志固定，不含显示安全内容的标志
- [x] 凭据锁定时不唤醒、不解锁、不输入凭据；取帧返回锁定错误
- [x] API 35 组休眠时返回 `DISPLAY_ASLEEP`，PIN 往返与重建恢复有设备证据
- [x] 拒绝连接时关闭异常不终止辅助进程（回归单测）
- [x] 目标显示无效或辅助进程不可用时不回退到主屏（T2/T3 单测；CLI 设备证据待 T4）

锁定项为 T1 实现与 JVM 单测证据，PIN 端到端待 T4；主屏 0 已实测拒绝为
DISPLAY_NOT_FOUND，辅助进程不可用的 Go/CLI 行为仍待 T2/T3。

## 功能

- [x] 创建、列出、关闭虚拟屏
- [x] 在虚拟屏上启动应用，主屏前台不变
- [ ] 对虚拟屏点按、滑动、按键、ASCII 文本
- [ ] 读取虚拟屏层级与帧
- [ ] `device.sleep` 使主屏息屏，与 `device.wake` 对称
- [x] 不带 `--display` 时既有命令行为逐字不变（T2 fake-adb 测试；CLI 参数待 T3）

T1 使用系统 Settings 验证启动与主屏前台保持，已验证帧；fixture 输入及层级链路待 T4。

## 模拟器证据（API 35）

- [x] 记录 AVD、system image、分辨率与设备指纹
- [ ] 主屏息屏后虚拟屏点按、层级、取帧仍成立
- [ ] `screen_off_timeout=15s` 下等待 60 秒以上，虚拟屏仍唤醒且帧非黑
- [ ] 测试 PIN 锁定后取帧返回锁定错误；测试 PIN 已清除
- [x] 停止后无残留进程、虚拟屏、转发与临时文件
- [ ] 连续 3 次运行的结果已记录

## 验证

- [x] `make comments`
- [x] `make test`
- [x] `make verify`
- [x] `make build`
- [x] `make android-verify`

以上验证对应 T1，本轮证据与已知限制见 progress.md Round 1。

## 待设备验证（保持未勾选）

- [ ] 真机与 OEM ROM 上虚拟屏的创建、启动与输入
- [ ] API 33 / API 34 上的行为差异
- [ ] 用户在主屏输入时输入法与焦点是否受影响
- [ ] 虚拟屏上的输入法与中文输入
- [ ] 无线 ADB 断开重连后辅助进程的存活情况
