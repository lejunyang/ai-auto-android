# N47 Test-only 授权 Provider 验收清单

- [x] Provider 只接受 N31 三 profile、显式 emulator serial/fingerprint 与 clean marker
- [x] App/debug androidTest 双签名必须一致且命中固定 signer
- [x] 固定 test token 不可由 CLI、环境或任意输入替换且不出现在日志
- [x] 安装、instrumentation、Bridge open/close 全部使用固定 argv 与显式 serial
- [x] Accessibility 原设置在成功、失败、取消和超时路径恢复
- [x] Bridge、test service、forward、session 与 App data 无残留
- [x] 真机、release、未知 signer/fingerprint/marker 全部失败关闭
- [x] release APK 静态扫描为零入口
- [x] N47 73/73 与新增 Node/Android 定向测试通过
- [x] comments/diff、Make 全量与 Android unit/lint/debug/androidTest/release 通过
- [x] 未操作设备，API 30/33/34 设备证据明确留给主线程
- [x] `docs/next-phase-tasks.md` 未修改，N47 未勾选
