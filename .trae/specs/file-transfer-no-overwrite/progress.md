# 文件传输不覆盖保护进度

本文件 append-only。

## Round 1：前提核实与实现

基线 `f4a0cce`（任务一至三已合入 main）。worktree `E:/Projects/wt-filexfer`，
分支 `file-transfer-no-overwrite`。

### 前提核实（成立）

底层与协议齐备、三通道零出口，与用户描述一致：

- `internal/adb/device_state.go:316/343` 的 `PushFile` / `PullFile`
- `internal/service/device_state.go:56/70` 的服务层包装
- `action.schema.json:1219/1246` 的 `file.push` / `file.pull`
- `internal/cli/direct.go:447` 的注释「刻意不包含 file.push / file.pull」

### 实测发现：空格路径会让保护静默失效

`validateRemotePath` 的字符集是 `^[A-Za-z0-9 ._\-/]+$`，**允许空格**。实测确认
`/sdcard/a b.txt` 通过校验，而 `;`、`$`、反引号、单双引号全部被拒。

这意味着若把路径裸拼进 `adb shell test -e <path>`，含空格的路径会被 shell 拆成
两个词，`test -e` 只看前半段，对**已存在**的文件判成不存在，于是放行覆盖。
这不是注入漏洞，是保护失效——方向恰好是最坏的那个。

解决：路径单引号包裹。白名单已排除单引号本身，因此不存在能闭合引号的字符，
包裹在此是完备的。该结论依赖白名单，二者必须一起维护，已写进代码注释。
专门加了 `TestPushFileDetectsExistingRemotePathContainingSpaces` 钉住。

### 保护放在 adb 层

放在 `internal/adb` 的 `PushFile` / `PullFile` 内部，不放 CLI 参数层。CLI 只是
出口之一，保护写在出口侧会在将来接其它出口时被绕过，而那恰是最需要保护的场景。

探测失败时**拒绝**传输而非视作「不存在」：否则设备临时不可达会让保护退化成允许
覆盖。用显式标记 `AACTL_REMOTE_EXISTS` 而非只看退出码，因为 `adb shell` 的退出码
在部分实现上反映的是 adb 自身而非远端命令。

### TOCTOU

探测返回「不存在」与 `adb push` 落盘之间存在窗口，adb 协议层无 O_EXCL 语义，
无法消除。代码注释与错误信息一律表述为「已检查该路径当时不存在」，不表述为
「保证不覆盖」。本地侧同理，`adb pull` 由外部进程写文件。

### 锁定测试换目标

`TestFileTransferHasNoCLIEntryPoint` 原锁「无 CLI 出口」，出口开放后不删除、
改锁新不变量，更名为 `TestFileTransferRefusesToOverwriteOrDelete`：无删除子命令、
无覆盖开关、MCP 无出口、正常出口确实存在（后者防止前面的否定断言空洞通过）。
MCP 断言改用权威的 `mcpserver.ToolNames` 而非自造枚举。

## Round 2：变异测试暴露一处空洞断言

不满足于「断言通过」，对两处保护各做一次变异：

1. 把 push 的 `if exists` 改成 `if false && exists` →
   `TestPushFileRefusesToOverwriteAnExistingRemotePath` 如期失败
   （file_transfer_test.go:55），恢复后通过。保护确实是载重的。

2. 把 `force` / `overwrite` 加进选项白名单 → 锁定测试**竟然仍通过**。
   查明原因：`parseNamedOptions` 的选项都是取值型，测试里 `--force` 放在末尾
   不带值，会先以「Option --force requires a value」失败，**根本没走到白名单判断**。
   该断言是空洞的。改为 `--force true` 带值传递后重跑变异，如期失败
   （direct_surface_test.go:261），恢复后通过。

第 2 条如果只看「测试通过」就会漏掉，记录在此以免日后重蹈。

变异后已确认源码无残留（grep `if false &&` / `"force"` / `"overwrite"` 均无命中）。

### 门禁结果

见 checklist。未改动 Android 侧，故不跑 `make android-verify`。
