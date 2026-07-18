# Commands

`aactl` 等可执行程序的入口位于此目录。

当前设备发现命令：

```bash
aactl version --json
aactl doctor --json
aactl devices list --json
# pair 从交互式 stdin 读取六位配对码。
aactl devices pair 192.0.2.10:37123 --json
aactl devices connect 192.0.2.10:40117 --json
aactl device info --device SERIAL --json
```

当前实现没有 `devices watch`，多设备场景必须从 `devices list` 的结果中显式选择
serial。

直接设备能力要求每次显式指定 ADB serial：

```bash
aactl observe screenshot --device SERIAL --output screen.png --json
aactl observe hierarchy --device SERIAL --json
aactl action tap --device SERIAL --x 200 --y 400 --json
aactl action swipe --device SERIAL --x1 200 --y1 800 --x2 200 --y2 200 --duration 300 --json
aactl action text --device SERIAL --text "hello world" --json
aactl action key --device SERIAL --key BACK --json
aactl action launch --device SERIAL --package com.example.app --json
aactl action stop --device SERIAL --package com.example.app --json
```

这些命令只映射到固定的类型化 ADB 参数，不提供任意 shell。

桌面桥需要先在 Android App 的“桌面桥”页面手动开启。App 只监听设备
`127.0.0.1:38383`；`aactl` 使用指定设备的 ADB forward 分配本机临时端口：

```bash
# 省略 --code 时从交互式 stdin 读取一次性码。
aactl bridge open --device SERIAL --json
aactl bridge info --device SERIAL --json
aactl bridge snapshot --device SERIAL --package com.example.app --max-depth 64 --json
aactl bridge action --device SERIAL --action '{"type":"ui.back","params":{}}' --json
aactl bridge close --device SERIAL --json
```

`open` 的输出不包含会话 token。短期 token 保存在当前用户的受限配置目录，
供后续命令复用；`close` 会撤销 App 会话并移除 ADB forward。连接失败时也会
清理刚创建的 forward。

录制脚本由 Android App 创建、查看和选择。当前 CLI 只提供回放，不提供
`recording list`：

```bash
aactl recording replay --device SERIAL --script 123e4567-e89b-42d3-a456-426614174000 --json
```

MCP stdio 服务：

```bash
aactl mcp serve
```

MCP 暴露 `android_devices_list`、`android_device_get`、`android_observe`、
`android_action_execute` 和 `android_recording_replay`。其中
`android_observe` 统一支持 screenshot、hierarchy 和已建立 Bridge 会话下的
semantic；CLI 的 semantic 观察命令是 `bridge snapshot`。MCP 不暴露 doctor、
配对、连接、Bridge 会话管理、Bridge action 或任意 shell。
