# N33 平台滚动 Surface 规格

## 目标

将 API 30 上始终产生 `VERTICAL_OFFSET:0` 的自定义触摸路由恢复为平台
`ScrollView` 行为，同时保留稳定 resource ID、最新 bounds 内 swipe 和真实 offset
后置验证。

## 允许修改

- `.trae/specs/n33-platform-scroll-surface/`
- `android/device-fixture/`
- `.trae/specs/n33-native-device-fixture/progress.md` 的 append-only 设备证据

## 禁止修改

- 生产 App、Bridge、协议、N34/N47/N52 与公共路线图
- 直接写入滚动状态、用测试 API 调用 `scrollTo/scrollBy` 伪造动作结果
- raw ADB、shell、持久化坐标或跳过真实 offset 后置断言

## 验收

- 纵向 surface 使用平台 `ScrollView` 触摸实现，不覆盖 dispatch/intercept/touch。
- UIAutomator 只在最新节点 bounds 内执行一次 swipe，成功要求 `scrollY>0` 和独立
  `VERTICAL_SCROLL:1`。
- API 30 单轮通过后，才允许推进重复轮次和 API 33/34。
- 每轮显式 serial；结束后设备、runtime、lock、lease 和 owned emulator 为零。
