# N46 N31 安装 Runner 验收清单

- [x] 独立 change spec/worktree
- [x] 仅接受 verified descriptor 与 owned emulator
- [x] 安装、查询、拉回、清理均为固定 argv
- [x] 设备 base APK 同 size/hash/签名复核
- [x] 所有失败路径恢复 clean snapshot
- [x] API 30/33/34 五包安装证据
- [x] 最终设备、runtime、lease 和临时文件为零
- [x] 提交身份、trailer 和集成状态正确
