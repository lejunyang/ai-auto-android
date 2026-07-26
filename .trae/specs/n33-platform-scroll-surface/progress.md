# N33 平台滚动 Surface 进度

本文件 append-only，记录设计更正、设备结果、清理和集成证据。

## Round 1

- 历史 API 30 多轮尝试均在最新 bounds 内成功注入 swipe，但自定义
  `DeterministicScrollView` 的无条件 intercept 与手写 MOVE 路由始终输出
  `VERTICAL_OFFSET:0`。节点定位、可见性和 surface 尺寸已被排除。
- 本轮不再叠加触摸补丁；恢复平台 `ScrollView` 处理，测试仍以真实 offset 与
  `OnScrollChangeListener` 独立状态双重验证，禁止直接写状态或程序化滚动伪造结果。

## Round 2

- RED JVM 契约先拒绝自定义 `MotionEvent`、intercept/dispatch/touch 与
  `scrollBy` 路由；将类型临时缩减为纯平台 `ScrollView` 后，模块
  test/lint/debug/AndroidTest/release 共 121 tasks 通过。
- API 30 clean AVD `emulator-5576` 绑定固定 fingerprint 后运行 core 单轮；最新
  bounds 内 `UiDevice.swipe` 返回成功，但 `VERTICAL_OFFSET` 仍为 0，按真实后置
  失败并停止。
- runner 停止并清理后，以新 clean serial `emulator-5578` 验证纯平台 View 的
  `UiObject2.scroll(Direction.UP, 0.8f)`；动作 API 直接返回 false，未产生有效
  滚动，再次失败关闭。
- 两种路线均未直接写状态、未调用程序化 `scrollTo/scrollBy`、未放宽 offset
  断言。实验代码已完整撤销，`git diff --exit-code -- android/device-fixture`
  为零；API 33/34 和重复矩阵未启动。
- 最终 `aactl` 为 0 devices，N31 runtime、AVD/port lease 和 owned emulator 为
  零。该证据否定“只需恢复平台 ScrollView”的方案，N33 继续保持未完成。
