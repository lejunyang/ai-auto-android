# N41 验收清单

- [x] 脚本复制入口生成可交付给调用方的独立副本
- [x] 步骤列表支持重排、启停、删除和复制
- [x] selector、坐标、wait、retry、failure、notes 表单原子提交
- [x] Undo、Redo、dirty 和未保存离开确认正确
- [x] 保存成功和 revision conflict 不丢失版本数据
- [x] dry-run 报告定位具体失败步骤
- [x] observation 只来自调用方授权短生命周期对象
- [x] observation 过期失败关闭且离开/旋转释放租约
- [x] 可恢复状态不包含图像、Base64、本地路径或权限句柄
- [x] N41 JVM/Compose 测试与 App JVM 全量通过
- [x] lint、中文注释和 diff 检查通过
- [x] 未修改禁止范围或共享路线图
- [x] 未将 instrumentation 编译描述为设备执行通过
