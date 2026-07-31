# N33 原生纵向滚动 Surface 重设计验收清单

- [x] 纵向 surface 使用平台 `ListView`，不再包含 `ScrollView`
- [x] 目标和列表项提供稳定 resource ID 与固定 `contentDescription`
- [x] `VERTICAL_OFFSET` 源于真实可见列表位置且初始值为 0
- [x] `VERTICAL_SCROLL:1` 只在真实 offset 大于 0 后产生
- [x] instrumentation 单次动作后重新观察 offset 与状态
- [x] 纵向测试或生产滚动动作不以 `scrollTo`、`scrollBy` 伪造成功
- [x] 定向 unit、androidTest 编译、lint 和 Debug APK 构建通过
- [x] `make comments` 与 `git diff --check` 通过
- [ ] 最终 bounds 内 swipe route 通过 API 30 单轮设备复验
- [x] 设备、runtime、lock、lease 和 owned emulator 最终零残留
- [x] 变更仅位于允许目录，提交身份和 trailer 正确
