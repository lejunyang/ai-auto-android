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

重新运行 `devices list`，并精确使用返回的 serial。当前 CLI 不提供 `devices watch` 命令。

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

MCP 不暴露 `doctor`、`pair`、`connect`、信任变更或任意 shell。

## 设备选择契约

将所选 serial 记录为数据，而不是自然语言说明。后续每条命令都必须包含 `--device SERIAL`，后续每次 MCP 调用都必须包含 `"device":"SERIAL"`。不得使用型号名称代替 serial。
