# N52 Production Residue Provider 任务

- [x] 建立独立 change spec，记录默认 capability unavailable 与安全交付边界。
- [x] 先新增 concrete provider 缺失的 RED 测试。
- [x] 实现固定 roots、profiles、packages 与 binding 的八类 inspector。
- [x] 覆盖非零、symlink、预算、身份竞态、stale runtime/lease/process 和绑定漂移。
- [x] 接入 N52 默认 production adapter，保持 capability 零 emulator 启动失败关闭。
- [x] 运行 N52 41 项、N47/N34 回归及 comments、diff、test、verify、build。
- [x] 审查允许修改范围并以单一职责 Conventional Commit 提交，不 push。
