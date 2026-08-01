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

支持的按键为 `BACK`、`HOME`、`RECENTS`、`ENTER`、`TAB`、`ESCAPE`、`SPACE`、`DELETE`、`FORWARD_DEL`、`DPAD_UP`、`DPAD_DOWN`、`DPAD_LEFT`、`DPAD_RIGHT`、`DPAD_CENTER`、`VOLUME_UP`、`VOLUME_DOWN` 和 `VOLUME_MUTE`。

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
{"device":"SERIAL","action":"app.stop","package":"com.example.app"}
```

MCP 直接动作与对应 CLI 命令具有相同的校验和后端语义。MCP 不暴露任意 shell 或 Bridge 动作执行。

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

当前 Bridge 可执行 `ui.click`、`ui.longClick`、使用非敏感字面文本的 `ui.setText`、`ui.scroll`、`ui.tap`、`ui.swipe`、`ui.back`、`ui.home` 和 `ui.recents`。其他 protocol v1 动作可能符合 schema，但会在此 Bridge 返回 `CAPABILITY_UNAVAILABLE`。

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
