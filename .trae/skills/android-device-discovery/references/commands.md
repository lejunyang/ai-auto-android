# 设备发现命令

## CLI

在不依赖 ADB 的情况下检查已安装的 CLI：

```bash
aactl version --json
```

诊断 ADB 可执行文件、server 状态、loopback 端口 5037 和 mDNS：

```bash
aactl doctor --json
```

列出规范化的 USB、Wi-Fi 和模拟器设备：

```bash
aactl devices list --json
```

在有限时间内观察设备插拔、状态和无线调试 mDNS 端点变化：

```bash
aactl devices watch --duration 30s --interval 1s --max-events 256 --json
```

`watch` 最长允许 5 分钟且事件数有上限；取消时返回已收集事件，不会运行
`adb kill-server`。

获取一个明确 serial 的详细信息与 capabilities：

```bash
aactl device info --device SERIAL --json
```

使用 Android 11+ 无线调试时，先通过 Android 显示的配对端点完成配对。命令在 stderr 中提示，并从 stdin 读取六位验证码，使其不会进入进程参数：

```bash
aactl devices pair 192.0.2.10:37123 --json
```

然后连接 Android 显示的连接端点。连接端口通常不同于配对端口：

```bash
aactl devices connect 192.0.2.10:40117 --json
```

重新运行 `devices list`，并精确使用返回的 mDNS Wi-Fi serial。用户确认设备身份后，
可保存不含秘密的可信档案：

```bash
aactl devices trust --device 'adb-DEVICE._adb-tls-connect._tcp' --json
aactl devices trusted --json
```

无线调试端口变化后，按档案中的稳定身份显式恢复：

```bash
aactl devices reconnect --identity adb-DEVICE --json
```

只有恰好一个私有或链路本地 mDNS 连接端点与 identity 匹配时才会连接。型号相同、
身份不一致或同一身份出现在多个网卡时命令失败。删除本项目保存的档案不会撤销 Android
的 ADB 信任：

```bash
aactl devices forget --identity adb-DEVICE --json
```

所有 JSON CLI 响应都使用协议信封：

```text
schemaVersion, requestId, ok, data, error, meta
```

将 `error.code` 视为稳定字段。可读消息可以增加细节，而无需更改该错误码。

## MCP

MCP server 暴露以下工具：

- 使用 `{}` 调用 `android_devices_list`。
- 使用 `{"device":"SERIAL"}` 调用 `android_device_get`。

配置 MCP 客户端启动以下长时间运行的 stdio 命令：

```bash
aactl mcp serve
```

不得将该 server 作为一次性交互命令调用。

MCP 不暴露 `doctor`、`watch`、`pair`、`connect`、可信档案、重连、信任变更或任意
shell。

## 设备选择契约

将所选 serial 记录为数据，而不是自然语言说明。后续每条命令都必须包含 `--device SERIAL`，后续每次 MCP 调用都必须包含 `"device":"SERIAL"`。不得使用型号名称代替 serial。
