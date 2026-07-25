# N31 验收清单

- [ ] 三个 API 的 package hash、ABI、分辨率、语言、时区和导航模式固定
- [ ] 每个命令返回明确 serial 和设备指纹
- [ ] 两个进程不能复用同一 AVD 或 serial
- [ ] stop 失败时保留锁并允许显式恢复
- [ ] WebView 或工具版本无法解析时失败关闭
- [ ] API 30、33、34 各连续运行 10 次且清理率 100%
- [ ] macOS 验收通过，Windows 等价路径有 smoke 证据
- [ ] 未执行 `adb kill-server`，未影响普通真机或其他 AVD
- [ ] 定向测试、`make comments`、相关构建和 `git diff --check` 通过
