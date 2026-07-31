# N41 编辑器设备 instrumentation 验收清单

- [ ] 真实详情入口覆盖脚本复制和步骤重排/启停/复制/删除
- [ ] 真实详情入口覆盖 Undo/Redo 与 dirty 离开确认
- [ ] 真实 store 外部更新可稳定触发 revision conflict UI
- [ ] dry-run UI 自动定位首个失败步骤且不提交动作
- [x] test-only provider 不读取真实页面、文件、网络或权限数据
- [x] test-only provider 短 TTL、单活跃授权、释放清零且旧租约失败关闭
- [ ] 点选保存 normalized point、observation ID 与 image hash
- [ ] 框选保存 normalized bounds、observation ID 与 image hash
- [ ] 真实详情入口能消费显式授权且普通入口默认无 screenshot surface
- [x] 三类生产接线缺口已写为 RED 源码且未越界修改 `main`
- [x] 三类预期 RED 使用带稳定 spec reason 的 `@Ignore` 保留，不污染全量 instrumentation
- [x] provider 生命周期与真实详情入口非缺口场景保持可运行
- [x] JVM、AndroidTest 编译、lint、comments 与 diff-check 通过
- [ ] instrumentation 设备执行由主线程集成后完成
