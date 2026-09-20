# 验收清单

## C6 剪贴板

- [x] 只做写入，未开放读取
- [x] `clipboard.get` 在桥侧被拒（CAPABILITY_UNAVAILABLE），有锁定测试
- [x] 锁定测试注释写明平台原因（无 READ_CLIPBOARD 权限；API 29+ 靠焦点/默认输入法
      限制；与「读别的 App 剪贴板」互斥；两条可行路径都需独立产品决策）
- [x] `ClipboardGateway.read()` 与 Empty/Unavailable 三态保留未动
- [x] 文本上限与协议一致，超限在写入前拒绝（有测试断言未写入）
- [x] 边界内文本可写（防止上一条空洞通过）

## C4 / C5 既有设计未被破坏

- [x] 窗口上限 12 未放开
- [x] 每窗口节点树按 maxDepth 截断
- [x] Toast 缓冲上限 64 未放开
- [x] **eventTimeMs 一路透传到 CLI 输出**，有专门测试 + 变异验证
- [x] 瞬时消息只读，不隐式清空

## C11 范围

- [x] 严格只加 2 个条件，未顺手做 C10 的 scrollUntil
- [x] 未扩出 UIAutomator 没有的第三个 EventCondition
- [x] 轮询 vs 事件的语义落差已如实表述，未承诺事件级保证

## 变异验证（实证，非仅断言通过）

- [x] 接线 `clipboard.get` → `clipboard get has no bridge entry point` 失败
      （make android-verify exit=2，549 tests 1 failed），恢复后通过
- [x] `newWindow` 去掉基线保护 → `TestAwaitNewWindowDoesNotFireWithoutAChange` 失败
- [x] `scrollFinished` 去掉「必须先动过」→ `TestAwaitScrollFinishedRequiresMotionFirst` 失败
- [x] 去掉 `eventTimeMs` → `TestBridgeMessagesPreserveEventTimestamps` 失败

## 门禁

- [x] `make comments` 559 文件
- [x] `make test`
- [x] `make verify` 协议 56 项
- [x] `make build`
- [x] `make android-verify`：**删除 test-results 后强制重跑**，74 个 XML / 549 tests /
      0 failures；逐条核对新增的 7 条 clipboard+ime 与 4 条 parser 用例确实 PASSED

## 待设备验证（保持未勾选）

- [ ] 真机多窗口：弹窗 / 输入法 / 分屏 / 画中画下 `bridge windows` 的实际枚举结果

  说明：`flagRetrieveInteractiveWindows` 已在配置声明，但窗口类型映射与 layer 顺序
  只在真机上才能确认。

- [ ] 真机 Toast 捕获：触发 Toast 后 `bridge messages` 能读到文本与时间戳
- [ ] 真机剪贴板写入：`clipboard.set` 后在其它应用粘贴可得
- [ ] 真机事件判据：跨 Activity 跳转时 `newWindow` 的命中时机；长列表滚动时
      `scrollFinished` 的静止判定是否与人的观感一致

  说明：轮询间隔 400ms，快速滚动下的判定时机需要真机校准。
