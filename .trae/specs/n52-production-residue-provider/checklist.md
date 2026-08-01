# N52 Production Residue Provider 验收清单

- [x] 八类结果绑定 N31 profile/API/serial/fingerprint/`clean` snapshot。
- [x] 固定 package 集与固定仓库外 state/report/staging roots，调用方不能扩展。
- [x] 可安全判零的文件系统类别实际检查，不以常量零替代。
- [x] 缺失 typed provider 时返回 `PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE`。
- [x] symlink、路径逃逸、预算、目录身份竞态和畸形状态失败关闭。
- [x] stale runtime、lease、process、截图、日志和 binding 漂移 fake 测试通过。
- [x] 默认 production capability 在 emulator 启动前失败关闭。
- [x] N52 41 项、N47/N34 回归与仓库级验证通过。
- [x] 未操作设备、未启动 emulator、未勾选 N52 路线图。
