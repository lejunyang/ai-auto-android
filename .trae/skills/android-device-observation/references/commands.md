# 设备观察命令

## 设备信息

```bash
aactl device info --device SERIAL --json
```

采集 UI 数据前，确认 `state` 为 `device`，并检查返回的 capabilities。

## PNG 截图

使用获准的本地输出路径。`aactl` 使用仅所有者可访问的权限写入文件，并返回绝对路径、格式、字节数和 SHA-256：

```bash
aactl observe screenshot --device SERIAL --output screen.png --json
```

图像本身不会嵌入 CLI JSON 响应。

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

MCP 不暴露 Bridge 的 open、info、action 或 close。必须在用户直接参与下通过 CLI 建立和关闭 session。

## 当前命令边界

CLI 仅支持 `observe screenshot` 和 `observe hierarchy`。语义观察使用
`bridge snapshot`；MCP 通过 `android_observe` 统一基础数据源，并通过
`android_visual_target_propose` 提供同轮脱敏视觉候选。原始 hierarchy 不具有视觉
候选路径的脱敏和稳定保证；只读 proposal 也不构成后续动作授权。
