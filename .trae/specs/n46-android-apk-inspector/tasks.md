# N46 Android APK Inspector Tasks

- [x] Task N46.I1：核对既有 cache verifier、安装前边界与独立 worktree
- [x] Task N46.I2：先编写固定 argv、工具路径和严格解析失败测试
- [x] Task N46.I3：实现可信 build-tools 路径校验与 identity 固定
- [x] Task N46.I4：实现限时限额 `execFile` 固定命令执行
- [x] Task N46.I5：实现 badging 与签名证书严格解析
- [x] Task N46.I6：验证 mismatch 在 descriptor 与安装前失败
- [x] Task N46.I7：更新 N46 仓库外缓存调用文档
- [x] Task N46.I8：完成 smoke、注释、diff 与范围验证
- [x] Task N46.I9：以单一职责 Conventional Commit 提交

## 依赖与并行

本 follow-up 依赖已完成的 N46 manifest、cache verifier 与 lifecycle。目录所有权
限于本 change spec、`scripts/test-lab/` 和 `test-lab/apps/README.md`，不与 N41、
N45、Android、Gradle、workflow 或共享路线图并行修改相交。

## 回滚与清理

失败时不保留部分 descriptor，不执行安装；测试只清理由系统临时目录托管的无
`.apk` 扩展名文本 fixture。若实现未通过验收，移除本 follow-up 新增模块与测试，
既有 N46 verifier 和生命周期保持不变。
