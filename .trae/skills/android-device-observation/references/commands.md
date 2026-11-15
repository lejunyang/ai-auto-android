# 设备观察命令

## 设备信息

```bash
aactl device info --device SERIAL --json
```

采集 UI 数据前，确认 `state` 为 `device`，并检查返回的 capabilities。

## PNG 截图

使用获准的本地输出路径。aactl请求0600文件模式（Windows依赖目录ACL），
返回绝对路径、格式、字节数和SHA-256；不要把Windows模式位当作额外ACL隔离保证：

```bash
aactl observe screenshot --device SERIAL --output screen.png --json
```

图像本身不会嵌入 CLI JSON 响应。

虚拟摄像头屏在display list/status中带camera:"back"、实际displayFlags和cameraInfo，
streams中的frames/error用于区分注册成功与实际绘制；空屏可能FRAME_NOT_READY。
普通屏省略摄像头字段。图片接收imageSet:true不证明应用读回成功，需由测试fixture的
CAMERA四象限文本验收；相机授权由用户按虚拟设备完成，不由模型自动处理。

使用 MCP 时，调用 `android_observe`：

```json
{"device":"SERIAL","kind":"screenshot"}
```

MCP 返回图像内容和结构化元数据。将客户端管理的图像视为敏感产物。

## 同轮视觉候选

只在已重新发现且明确选择的设备上调用。`expectedPackage` 必须来自调用前最新观察，
`target` 至少提供一个明确的 `role` 或 `label`；两者同时提供时必须命中同一唯一
UIAutomator 节点。

```json
{
  "device": "SERIAL",
  "expectedPackage": "com.example.app",
  "target": {"role": "button", "label": "Continue"}
}
```

工具名为 `android_visual_target_propose`。它在一次稳定门内绑定 PNG、前后相同的
UIAutomator hierarchy、前台包、screen/rotation 和短生命周期 observation，只返回
template 候选，`actionCommitCount` 固定为 `0`。包、设备、层级或 rotation 漂移，
黑屏/保护窗口、无候选或多候选都会失败关闭，不会执行点击或坐标动作。服务端在 MCP
响应写出后清零图片；客户端收到的图像仍按敏感产物处理。

该 observation 和候选只用于当前只读响应，响应写出后 lease 已关闭。后续自动化不得
携带或复用其中的 observation ID、候选、hash 或 geometry；需要执行视觉动作时，转入
自动化 Skill 的 `android_visual_action_execute` 原子路径，让服务在同一调用内重新
采集、证明、执行和后置观察。

## UIAutomator 层级

```bash
aactl observe hierarchy --device SERIAL --json
```

CLI JSON 数据包含 `format: "uiautomator-xml"`、`xml`、`sizeBytes` 和 `sha256`。CLI 没有 hierarchy 输出文件选项。

使用 MCP 时：

```json
{"device":"SERIAL","kind":"hierarchy"}
```

## App Bridge 语义快照

用户必须先在 Android App 中启用桌面桥，并生成新的单次验证码。省略 `--code`，让 CLI 通过交互方式读取：

```bash
aactl bridge open --device SERIAL --json
```

检查已建立 Bridge 的设备信息：

```bash
aactl bridge info --device SERIAL --json
```

采集限定到指定包的语义树：

```bash
aactl bridge snapshot --device SERIAL --package com.example.app --max-depth 64 --json
```

`--package` 可选。`--max-depth` 可选，接受 1 到 100；共享服务默认值为 64。

使用 MCP 时，需要已有的 Bridge session：

```json
{"device":"SERIAL","kind":"semantic","targetPackage":"com.example.app","maxDepth":64}
```

完成 Bridge 工作后：

```bash
aactl bridge close --device SERIAL --json
```

MCP不暴露Bridge open/info/close或任意bridge action透传；有独立语义白名单的
android_bridge_action等工具可使用已建立会话。会话建立/关闭仍须用户经CLI完成。

## 其它直连与Bridge观察

```bash
aactl observe foreground --device SERIAL --json
aactl observe apps --device SERIAL --include-system false --with-details true --json
aactl observe screen-state --device SERIAL --json
aactl observe input-methods --device SERIAL --json
aactl observe recording --device SERIAL --output screen.mp4 --seconds 3 --json
aactl bridge foreground --device SERIAL --package com.example.app --json
aactl bridge windows --device SERIAL --package com.example.app --max-depth 64 --json
aactl bridge messages --device SERIAL --toasts-only true --json
```

observe recording是有界MP4录屏，MCP用android_screen_record；录制脚本摘要另用
recording list或android_observe kind=recordings，二者不能混淆。语义树使用bridge snapshot，
窗口/瞬时消息等要求已有Bridge会话和各自能力，不能仅凭Schema声明能力可用。

android_observe完整8种kind为screenshot、hierarchy、foreground、apps、semantic、windows、
transientMessages、recordings。前四种为直连观察，后四种要求Bridge；原始XML/PNG没有
语义脱敏和只读视觉候选的同轮稳定保证，proposal不构成后续动作授权。

## 显式主屏与虚拟屏

```bash
aactl observe hierarchy --device SERIAL --display 0 --json
aactl observe screenshot --device SERIAL --display 0 --output primary.png --json
aactl observe hierarchy --device SERIAL --display 2 --json
```

0明确主屏，不要求helper；正整数必须归操作员已启动的helper所有，示例2须替换为实际ID。
不带显示参数的旧层级读的是活动窗口，可能来自虚拟屏；不用于证明主屏身份。
按显示层级format=uiautomator-windows-xml，只含该display窗口组。
MCP只在screenshot/hierarchy接受同名displayId；其它观察、视觉等收到此字段明确拒绝。

focus默认isolated：API34+不打断主屏输入，但息屏无层级、虚拟屏无IME；steal可息屏层级
但打断主屏输入法。API33 focusIsolated=false。DISPLAY_EMPTY要求先在目标屏启动应用；
WINDOWS_UNAVAILABLE提示唤醒主屏或用steal重建（销毁原屏内容），不能删除目标参数改向主屏。
MCP可用android_display_list/status读取；helper start/stop只有CLI，升级v2前先旧CLI停止v1。
