# N47 固定 Fixture Production Runner 验收清单

- [x] 独立 branch/worktree
- [x] 允许修改范围和禁止目录已记录
- [x] 真实 test-only Bridge/Accessibility 授权缺口已精确记录
- [x] CLI 只接受 `--profile api-30|api-33|api-34`
- [x] 调用方不能传入 serial/package/APK/task/action/scene
- [x] capability 缺失时零 emulator 启动、零动作
- [x] N31 serial/fingerprint/clean snapshot 在场景前后绑定
- [x] native/WebView/Canvas 三个固定场景通过严格 Schema
- [x] semantic/input 使用固定 aactl argv/stdin
- [x] visual 使用无坐标 `android_visual_action_execute`
- [x] 每步前后观察，旧结果拒绝
- [x] unknown commit 不重试、不重放
- [x] 成功、失败、取消均执行四步场景清理
- [x] 八类 residue 全零门
- [x] N47 Node schema/smoke 与原有 59 项测试通过
- [x] N34 49 项测试通过
- [x] `make comments`、`make test`、`make verify`、`make build` 通过
- [x] `git diff --check` 通过
- [ ] API 30/33/34 emulator 设备证据已取得

最后一项必须由主线程在 capability 安全可用后实际运行设备才能勾选；本 change 不操作
设备，也不得勾选 N47 路线图任务。
