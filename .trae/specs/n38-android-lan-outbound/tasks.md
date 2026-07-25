# N38 Tasks

- [x] Task N38.1：创建独立 change spec 并固定共享集成边界
- [x] Task N38.2：先增加 N36 fixture/vector 与权限状态机 RED Kotlin 测试
- [x] Task N38.3：实现严格 invitation 解析、preflight 和用户确认门
- [x] Task N38.4：实现平台 X25519、HKDF、transcript confirmation 和加密 frame
- [x] Task N38.5：实现注入式出站会话、切网/过期/恢复停止和秘密清理
- [x] Task N38.6：实现 provider-neutral 扫码、手工输入和无 secret UI 状态
- [x] Task N38.7：完成定向测试、注释、差异和提交边界验证

## 依赖

N38 基于 N36 提交 `9d5017e`。N37 与本任务并行，真实同 LAN 集成只能在两者合入后由
主线程串行执行；公共导航、Manifest、Gradle 和相机 provider 也由主线程集成。
