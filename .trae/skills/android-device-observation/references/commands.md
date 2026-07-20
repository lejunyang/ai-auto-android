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

CLI 仅支持 `observe screenshot` 和 `observe hierarchy`。语义观察使用 `bridge snapshot`；MCP 通过 `android_observe` 统一三种数据源。
