# 可移植工具根与 Windows CI 验收清单

- [x] runner 不含机器专属 `/Volumes` 合法根常量
- [x] 支持显式 `AACTL_TOOLCHAIN_ROOT` 和兼容 `ANDROID_TOOLS_ROOT`
- [x] 工具/cache/state 路径必须位于显式根内
- [x] POSIX 与 Windows 风格根均有单元测试
- [x] Go/Node/Shell/YAML checkout 固定 LF，batch 固定 CRLF
- [x] Windows Go formatting 不受 `core.autocrlf` 影响
- [x] N34 artifact tests 不依赖 POSIX `/tmp` 或 Unix mode
- [x] Emulator runner tests 不依赖 POSIX 可执行位
- [x] 相关 Node、Go、Android、comments 与 diff-check 通过
- [x] 提交身份、trailer、集成和 worktree 清理正确
