# N38 Android LAN Integration 验收清单

- [x] 独立 change spec 先于生产实现落盘
- [x] N37/N38 wire frame 元数据和 NDJSON 字节格式一致
- [x] 握手和加密 frame 读取有界且严格失败关闭
- [x] socket 绑定用户明确选择的 Android Network 后才连接 IP literal
- [x] 当前网络变化后 session 在下一个动作前停止
- [x] 用户确认候选、网卡和短指纹前不启动连接
- [x] ViewModel 失败、停止和销毁均清理 input、session 和 executor
- [x] 现有桌面 Bridge 页面提供明确 LAN 配对入口
- [x] 手工 invitation 可完整使用且原始 payload 不进入 UI state
- [x] 未注入扫码 provider 时不伪造相机能力或请求无用途权限
- [x] 定向测试、App JVM 单测、注释和差异检查通过
- [ ] 真实扫码、N37 同 LAN 互操作及 API 设备矩阵保持待验收
- [x] 实现提交使用规定作者、Conventional Commit 与唯一 trailer
- [ ] 规格证据提交使用规定作者、Conventional Commit 与唯一 trailer
