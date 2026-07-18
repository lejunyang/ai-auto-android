# Automation Commands

## Direct CLI Actions

Every command requires an explicit device serial:

```bash
aactl action tap --device SERIAL --x 200 --y 400 --json
aactl action swipe --device SERIAL --x1 200 --y1 800 --x2 200 --y2 200 --duration 300 --json
aactl action text --device SERIAL --text "hello world" --json
aactl action key --device SERIAL --key BACK --json
aactl action launch --device SERIAL --package com.example.app --json
aactl action launch --device SERIAL --package com.example.app --activity .MainActivity --json
aactl action stop --device SERIAL --package com.example.app --json
```

Coordinates accept 0 through 100000. Swipe duration accepts 1 through 60000 ms
and defaults to 300 ms. Direct text accepts at most 10000 ASCII bytes from
letters, numbers, spaces, and `@._+-,:/`.

Supported keys are `BACK`, `HOME`, `RECENTS`, `ENTER`, `TAB`, `ESCAPE`,
`SPACE`, `DELETE`, `FORWARD_DEL`, `DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`,
`DPAD_RIGHT`, `DPAD_CENTER`, `VOLUME_UP`, `VOLUME_DOWN`, and `VOLUME_MUTE`.

These commands map to fixed ADB argument arrays. There is no shell command.

## MCP Direct Actions

Call `android_action_execute` with exactly one supported shape:

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

MCP direct actions have the same validation and backend semantics as their CLI
equivalents. MCP does not expose arbitrary shell or bridge action execution.

## App Bridge Actions

The user must enable the Android App desktop bridge and open a short-lived
session:

```bash
aactl bridge open --device SERIAL --json
```

Take a fresh semantic snapshot before selecting a target:

```bash
aactl bridge snapshot --device SERIAL --package com.example.app --max-depth 64 --json
```

Execute one protocol v1 action object. A global back action has no target:

```bash
aactl bridge action --device SERIAL --action '{"type":"ui.back","params":{}}' --json
```

A semantic click uses selector candidates from the latest snapshot:

```bash
aactl bridge action --device SERIAL --action '{"type":"ui.click","params":{"target":{"packageName":"com.example.app","selectorCandidates":[{"strategy":"resourceId","value":"com.example.app:id/continue","weight":1,"required":true}]}}}' --json
```

The current bridge executes `ui.click`, `ui.longClick`, `ui.setText` with
literal non-sensitive text, `ui.scroll`, `ui.tap`, `ui.swipe`, `ui.back`,
`ui.home`, and `ui.recents`. Other protocol v1 actions can be valid schema but
return `CAPABILITY_UNAVAILABLE` at this bridge.

Close the session after bridge work:

```bash
aactl bridge close --device SERIAL --json
```

## Recording Replay

The current CLI can replay, but cannot list, edit, or inspect recordings.
Require the user to select and review the script in the Android App and provide
its UUID. An existing bridge session is required:

```bash
aactl recording replay --device SERIAL --script 123e4567-e89b-42d3-a456-426614174000 --json
```

With MCP, call `android_recording_replay`:

```json
{"device":"SERIAL","scriptId":"123e4567-e89b-42d3-a456-426614174000"}
```

Inspect `succeeded`, `requiresIntervention`, and every step's `status`,
`attempts`, `route`, `matchScore`, `errorCode`, and `message`.
