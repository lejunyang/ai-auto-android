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
