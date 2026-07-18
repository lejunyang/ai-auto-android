# Commands

`aactl` 等可执行程序的入口位于此目录。

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
