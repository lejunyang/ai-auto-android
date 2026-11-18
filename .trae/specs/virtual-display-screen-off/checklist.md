# 虚拟显示与息屏运行验收清单

已完成项必须有实现、测试或设备证据后才能勾选。

## 范围与流程

- [x] 独立 change spec、独立分支与 worktree，单一职责 Conventional Commit
- [x] 未回写任何既有规格的验收结论
- [x] 未修改 `android/app/`、`internal/mcpserver/`、`internal/bridge/`
- [x] 未触碰文件传输出口与 `TestFileTransferHasNoCLIEntryPoint`
- [x] 未 push 到远端
- [x] 文档写明窗口列表、普通层级歧义、休眠重建销毁语义、任务迁移与API33限制
- [x] MCP/发布/API33-34/真机OEM/普通层级歧义后续项登记，未描述为已交付

以上已核对至 T4，既有规格历史未改，未触及禁止目录与主工作区。

## 安全边界

- [x] 辅助进程只监听 `localabstract`，无 TCP 监听
- [x] 每个连接校验对端 uid，只接受 0 与 2000，有测试
- [x] 方法集合类型化，无任意命令执行入口
- [x] 消息、帧尺寸、显示数量都有上限且有超限测试
- [x] 虚拟屏标志固定，不含显示安全内容的标志
- [x] 凭据锁定时不唤醒、不解锁、不输入凭据；取帧返回锁定错误
- [x] API 35 组休眠时返回 `DISPLAY_ASLEEP`，PIN 往返与重建恢复有设备证据
- [x] 拒绝连接时关闭异常不终止辅助进程（回归单测）
- [x] 目标显示无效或辅助进程不可用时不回退到主屏（T2/T3 单测与 T4 CLI）
- [x] 窗口列表严格筛选目标编号；结果格式明确，不依赖活动窗口焦点

PIN往返已由T4三轮CLI验证；不存在的显示返回DISPLAY_NOT_FOUND，停止后status返回
DISPLAYD_NOT_RUNNING。API33未知显示组状态的限制保留，不能套用API35结论。

## 功能

- [x] 创建、列出、关闭虚拟屏
- [x] 在虚拟屏上启动应用，主屏前台不变
- [x] 对虚拟屏点按、滑动、按键、ASCII 文本
- [x] 读取虚拟屏层级与帧
- [x] `device.sleep` 使主屏息屏，与 `device.wake` 对称
- [x] 不带 `--display` 时既有命令行逐字不变（fake-adb锁定、CLI命令面测试；普通层级歧义另记）
- [x] 普通层级歧义已实测，不把它作为主屏隔离判据

本节按T4的seriesB-1/2/3真实aactl命令重新核对，证据见Round9。层级遵照f544271，
使用dump --windows与目标编号；普通层级三轮均返回虚拟fixture，主屏前台依据display0
的topResumedActivity核对。旧参数误判与失败尝试保留在progress历史中，不拼接验收。

## 模拟器证据（API 35）

- [x] 记录 AVD、system image、分辨率与设备指纹
- [x] 主屏息屏后虚拟屏点按、层级、取帧仍成立
- [x] `screen_off_timeout=15s` 下等待 60 秒以上，虚拟屏仍唤醒且帧非黑
- [x] 测试 PIN 锁定后取帧返回锁定错误；测试 PIN 已清除
- [x] 解锁后明确DISPLAY_ASLEEP，关闭重建并重新启动后恢复正常帧
- [x] 停止后无残留进程、虚拟屏、转发与临时文件
- [x] 连续 3 次运行的结果已记录

## 验证

- [x] `make comments`
- [x] `make test`
- [x] `make verify`
- [x] `make build`
- [x] `make android-verify`

以上已在T4最终验收重跑：Go303个顶层测试定义、Android612项（1既有跳过）、
Node脚本4项、注释588文件/16自测；详细结果与设备证据见progress.md Round9。

## 待设备验证（保持未勾选）

- [ ] 真机与 OEM ROM 上虚拟屏的创建、启动与输入
- [ ] API 33 / API 34 上的行为差异
- [ ] 用户在主屏输入时输入法与焦点是否受影响
- [ ] 虚拟屏上的输入法与中文输入
- [ ] 无线 ADB 断开重连后辅助进程的存活情况
