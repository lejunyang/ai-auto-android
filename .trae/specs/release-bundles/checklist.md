# 验收清单

- [x] 规格先于实现提交，提交均在指定分支且未 push
- [x] locator 优先级、损坏拒绝、符号链接、CWD 无关及来源诊断通过
- [x] 固定清单源于官方 XML，版本匹配 osdk，实下载核对后记录 SHA-256
- [x] 许可显式要求，slim 无 Platform-Tools 网络访问，CI 显式同意
- [x] 离线伪归档安全/摘要/大小/清理测试通过
- [x] helper debug APK 同版进两包；App APK 独立，无裸 CLI 发布资产
- [x] 八归档单层目录、白名单、顺序/权限/时间与九资产 SHA256SUMS 通过
- [x] Windows 完整包隔离 doctor 命中 bundled，精简包 ADB_NOT_FOUND
- [x] emulator-5554 指纹/环境固定，包内 CLI 的 devices/截图/start/create/close/stop 通过
- [x] 设备临时文件、forward、helper、模拟器与宿主进程已清理
- [x] README/安全/虚拟显示/排障/路线图仅 N60.2 与实测一致
- [x] make comments、make test、make verify、make build、make android-verify 通过
- [x] 未运行平台、项目许可证待定及其余风险明确记录
- [x] 无二进制/缓存/产物入 Git，worktree 干净
