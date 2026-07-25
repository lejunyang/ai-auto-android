# N41 编辑入口集成验收清单

- [x] 详情页可进入编辑且根导航未变化
- [x] 旋转后编辑状态由 keyed ViewModel 保留
- [x] 保存成功刷新详情，revision conflict 不覆盖磁盘数据
- [x] 复制脚本使用 revision 0 CAS 并打开副本
- [x] dirty 离开明确确认
- [x] dry-run 只读且无 execute 能力
- [x] 无授权 observation 时不显示截图点选 surface
- [x] App 全量、AndroidTest APK、lint、comments 与 diff-check 通过
- [x] 真实截图点选与 instrumentation 设备执行保持未验收
