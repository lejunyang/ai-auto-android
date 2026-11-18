# 自动化命令

## CLI 直接动作

每条命令都需要明确的设备 serial：

```bash
aactl action tap --device SERIAL --x 200 --y 400 --json
aactl action swipe --device SERIAL --x1 200 --y1 800 --x2 200 --y2 200 --duration 300 --json
aactl action text --device SERIAL --text "hello world" --json
aactl action key --device SERIAL --key BACK --json
aactl action launch --device SERIAL --package com.example.app --json
aactl action launch --device SERIAL --package com.example.app --activity .MainActivity --json
aactl action stop --device SERIAL --package com.example.app --json
```

坐标接受 0 到 100000。滑动时长接受 1 到 60000 ms，默认值为 300 ms。直接文本最多接受 10000 个 ASCII 字节，可使用字母、数字、空格和 `@._+-,:/`。

支持的按键为 `BACK`、`HOME`、`RECENTS`、`ENTER`、`TAB`、`ESCAPE`、`SPACE`、`DELETE`、`FORWARD_DEL`、`DPAD_UP`、`DPAD_DOWN`、`DPAD_LEFT`、`DPAD_RIGHT`、`DPAD_CENTER`、`MOVE_HOME`、`MOVE_END`、`PAGE_UP`、`PAGE_DOWN`、`VOLUME_UP`、`VOLUME_DOWN` 和 `VOLUME_MUTE`。

这些命令映射到固定的 ADB 参数数组，不提供 shell 命令。

## MCP 直接动作

使用以下一种受支持的形式调用 `android_action_execute`：

```json
{"device":"SERIAL","action":"ui.tap","x":200,"y":400}
```

```json
{"device":"SERIAL","action":"ui.swipe","startX":200,"startY":800,"endX":200,"endY":200,"durationMs":300}
```

```json
{"device":"SERIAL","action":"ui.setText","text":"hello world"}
```

```json
{"device":"SERIAL","action":"ui.pressKey","key":"BACK"}
```

```json
{"device":"SERIAL","action":"app.launch","package":"com.example.app","activity":".MainActivity"}
```

```json
{"device":"SERIAL","action":"ui.longClick","x":200,"y":400,"durationMs":750}
```

```json
{"device":"SERIAL","action":"ui.scroll","direction":"down","amount":0.6}
```

MCP直接动作与对应CLI共享校验/后端，但只开放既有七项：tap/swipe/longClick/scroll/
ASCII setText/pressKey/launch。app.stop与sleep/wake等CLI动作不开放，任意shell也不开放。
选择器动作另用独立白名单的android_bridge_action，不是bridge action整体透传。

## 原子视觉动作

仅在已重新发现并明确选择的 debug disposable emulator 上使用。用户必须已在 App
中人工启用 Accessibility 并建立新的 Bridge session；release、真机、未授权服务或
无 Bridge session 会失败关闭。

输入只描述明确目标和类型化动作意图，不提供、接收或复用坐标、candidate、
observation、PNG hash 或 handle。`target` 至少提供 `role` 或 `label`；两者同时提供
时必须命中同一唯一候选：

```json
{
  "device": "SERIAL",
  "expectedPackage": "com.example.app",
  "target": {"role": "button", "label": "Continue"},
  "action": {"type": "tap"}
}
```

工具名为 `android_visual_action_execute`。支持：

- `{"type":"tap"}`
- `{"type":"long-click","durationMs":750}`
- `{"type":"swipe","direction":"up","durationMs":450}`

它在单次调用内重新采集 device/hierarchy/PNG，绑定 serial、设备 fingerprint、前台
包、screen/rotation/density/insets、唯一候选、hash、confidence 和 expiry；随后通过
Bridge `visual.action.execute` 调用 N45 planner 和 package-bound Accessibility
executor，并在动作后重新观察。输出不含坐标、候选 geometry、图片或可复用 lease。

只有 `succeeded:true`、`verified:true`、`commitStatus:"committed"` 且
`actionCommits:1` 同时成立时才能报告成功。`not_committed` 表示零提交，可在重新观察
后重新决策；`unknown` 或 `actionCommits:null` 表示提交结果未知，必须停止且不得
重试。该工具具有 destructive 标记，涉及发送、提交、删除、离开页面或外部数据变更
时仍需逐动作取得新的人工确认。

## App Bridge 动作

用户必须启用 Android App 桌面桥，并打开短期 session：

```bash
aactl bridge open --device SERIAL --json
```

选择目标前采集新的语义快照：

```bash
aactl bridge snapshot --device SERIAL --package com.example.app --max-depth 64 --json
```

执行一个 protocol v1 动作对象。全局返回动作没有 target：

```bash
aactl bridge action --device SERIAL --action '{"type":"ui.back","params":{}}' --json
```

语义点击使用最新快照中的选择器候选：

```bash
aactl bridge action --device SERIAL --action '{"type":"ui.click","params":{"target":{"packageName":"com.example.app","selectorCandidates":[{"strategy":"resourceId","value":"com.example.app:id/continue","weight":1,"required":true}]}}}' --json
```

当前Bridge action.execute由AccessibilityCommandJsonParser逐项映射，分为：

- 元素/指针：ui.click、ui.longClick、非敏感字面量ui.setText、ui.scroll、ui.tap、ui.swipe。
- 有界手势：ui.drag、ui.multiTap、ui.pinch、ui.rotate、ui.twoFingerScroll、ui.pathGesture。
- 节点/编辑：ui.scrollIntoView、ui.selectText、ui.copy/cut/paste、ui.nodeAction、ui.setProgress、ui.scrollToPosition。
- 导航/系统：ui.back/home/recents、ui.notifications、ui.quickSettings、ui.lockScreen、ui.powerDialog、ui.dismissNotificationShade，仍受运行时API与本地策略限制。
- ui.pressKey只可靠发送MOVE_HOME/MOVE_END；ime.show聚焦当前可编辑节点，不保证键盘可见；clipboard.set只写入。

上述是Bridge解析/路由集合，不等于App AI风险白名单，也不等于MCP开放集合。
clipboard.get等合法Schema但未接线的动作返回CAPABILITY_UNAVAILABLE，不伪造结果。
MCP android_bridge_action只含ui.click、ui.longClick、ui.scrollIntoView、ui.nodeAction四项；
Unicode文本另走android_bridge_set_text，聚焦另走android_ime_show。配对与无障碍授权不能自动完成。

完成 Bridge 工作后关闭 session：

```bash
aactl bridge close --device SERIAL --json
```

## 录制列表与回放

当前 CLI 可以列出经过脱敏的录制摘要，并回放一个明确的 UUID，但不能编辑录制，也不能检查其中的步骤、变量或 secret。需要已有的 Bridge session：

```bash
aactl recording list --device SERIAL --json
```

回放前，要求用户在 Android App 中审核并选择脚本：

```bash
aactl recording replay --device SERIAL --script 123e4567-e89b-42d3-a456-426614174000 --json
```

使用 MCP 时，可通过只读 `android_observe` 工具获取脱敏摘要：

```json
{"device":"SERIAL","kind":"recordings"}
```

兼容性 MCP 工具 `android_recording_replay` 在此 MVP 中始终返回 `CONFIRMATION_REQUIRED`，不会执行回放。只有在人工直接审核并确认后才能使用 CLI。

检查 `succeeded`、`requiresIntervention`，以及每个步骤的 `status`、`attempts`、`route`、`matchScore`、`errorCode` 和 `message`。

## 显示目标与焦点策略

坐标必须来自目标显示最新观察，动作后重新读取同屏验证；不得按失败结果盲目重复。
CLI tap/swipe/long-press/text/key/launch接受--display N，MCP对应六类动作接受displayId。
0明确主屏且不要求helper，正整数须归helper所有；ui.scroll当前不接显示目标。
等待/断言/滚动直到、视觉与Bridge工具尚不支持displayId，拒绝后不能去掉参数改向主屏。

```json
{"device":"SERIAL","action":"ui.setText","displayId":2,"text":"Ascii"}
```

示例2须替换为android_display_create实际返回值；该工具参数是device/width/height/dpi及
可选focus=isolated或steal（默认isolated）。android_display_list/status/close提供其它管理，
close会销毁其上应用；helper启停仍只有CLI。v2升级先用旧CLI停止v1，再更新并启动。

isolated适合有人在主屏输入：API34+保护主屏焦点，但虚拟屏无IME、只能受限ASCII；
BACK直接交给应用，在根页会退出并读到DISPLAY_EMPTY，重新启动后恢复。主屏息屏时
层级返回WINDOWS_UNAVAILABLE，可唤醒或用steal重建；取帧与按键注入仍可用。
steal适合息屏挂机，会打断主屏输入法，其BACK可先收IME，不能套用isolated断言。
API33没有隔离标志，focusIsolated=false；中文输入与多显示Bridge仍待独立实现。

以上焦点/IME/息屏结论只适用于普通 DisplayManager 屏。摄像头屏由 VirtualDevice
创建，android_display_create额外接受可选camera:"back"，需要操作员先以CLI
`display start --virtual-camera`启用。只有CLI `camera set-image --display N --image PATH`
接受本机PNG/JPEG（8MiB/4096上限）；MCP不开放图片路径或启停。应用在虚拟设备上
的CAMERA必须用户单独授权，不自动点权限框、不给任何应用授予权限。NX888J的isolated/fixture/640×480 GPU读回与33分钟单流已由集成方和负责人验收；需用户默认设备+虚拟设备两处CAMERA授权。高分辨率、steal读回、IME/息屏、多轮统计及自动重连真机效果仍待验。
操作员CLI可用display stop --device SERIAL --keep-camera-association保留精确关联，
无匹配省略keptCameraAssociation；普通运行helper忽略开关，不查伴生关联。MCP无入口。
保留期间常驻shell的COMPANION_DEVICE_APP_STREAMING关联及既有虚拟CAMERA授权；
只读查adb -s SERIAL shell cmd companiondevice list USER_ID中的com.android.shell+00:00:00:00:00:10。
归还前用不带开关display stop --device SERIAL移除（无helper亦可）；保留后重启免重授真机待验。
启动失败只移除本次新建关联，既有/崩溃遗留保留；不disassociate-all、不代授权或点弹窗。

摄像头屏close/stop若返回VIRTUAL_CAMERA_BUSY（retryable=true，CLI退出4），
须让操作员先使目标应用释放相机，再显式重试关闭；不要自动重试、force-stop或kill helper。
helper正在出流时外部kill/崩溃可能卡死A16系统virtual_camera，help超时/残留失效相机
应停止操作并交负责人重启。releasePending屏已进入释放，不可再启动/输入/取帧；
本轮已改6秒排空后注销的顺序，真机关闭及保留关联免重授仍待验。
