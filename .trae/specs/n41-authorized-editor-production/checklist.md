# N41 授权截图编辑器生产接线验收清单

- [x] 普通 `RecordingHost` 默认无 screenshot surface
- [x] 显式授权 provider 可向真实详情编辑入口注入唯一短期 holder
- [x] composition 离开、过期或 provider 替换均释放图片租约
- [x] point 原子保存 normalized point、observation ID 与 image SHA-256
- [x] bounds 原子保存 normalized bounds、observation ID 与 image SHA-256
- [x] bounds 手势由 Compose surface 实际尺寸确定性归一化
- [x] 非法 geometry、observation ID 或 hash 原子拒绝且不污染历史
- [x] 手动坐标编辑不会保留过期授权 observation/hash
- [x] 三项 instrumentation 不再使用 `@Ignore`
- [x] JVM/App 全量、AndroidTest、lint、build、comments 与 diff 验证通过
- [ ] API 34 instrumentation 由主线程完成
