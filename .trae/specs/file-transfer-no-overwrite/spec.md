# 文件传输 CLI 出口与不覆盖保护

## 目标

给 `file.push` / `file.pull` 接 CLI 出口，语义严格限定为**只新增、不覆盖、不删除**。

底层早已实现（`internal/adb/device_state.go:316/343`），服务层已包装
（`internal/service/device_state.go:56/70`），协议已声明
（`action.schema.json:1219/1246`），唯独三通道零出口——
`internal/cli/direct.go:447` 有注释「刻意不包含 file.push / file.pull」。
本项是接线 + 加保护，不是从零实现传输。

## 核心不变量

1. push 到**已存在**的远端路径必须失败。`adb push` 本身直接覆盖，所以必须在
   push 之前检查远端是否存在。
2. pull 到**已存在**的本地路径必须失败。
3. 不提供任何删除能力，也不提供 `--force` / `--overwrite` 逃生口。
   将来要开覆盖必须走独立决策。

## 保护放在哪一层：服务端（internal/adb）

保护放在 `internal/adb` 的 `PushFile` / `PullFile` 内部，而不是 CLI 参数解析层。

理由：CLI 只是出口之一。若保护写在 CLI，将来 MCP 或其它调用方接出口时会绕过
它，而那恰恰是最需要保护的场景（模型自主发起）。放在 adb 层意味着任何经过
`PushFile` / `PullFile` 的调用都自动受保护，无法绕过。

## 远端存在性检查的实现与注入面评估

用 `adb -s SERIAL shell test -e '<path>' && echo EXISTS` 形式的固定结构命令。

注入面分析：

- `validateRemotePath`（`device_state.go:383`）是第一道防线，字符集白名单为
  `^[A-Za-z0-9 ._\-/]+$`，已实测确认拒绝 `;`、`$`、反引号、引号等全部 shell
  元字符，并要求绝对路径、双重 `..` 穿越检查。
- 关键点：**该字符集允许空格**（实测 `/sdcard/a b.txt` 通过）。因此裸拼
  `shell test -e /sdcard/a b.txt` 会被 shell 拆成两个参数，`test -e` 只看前半段，
  对「存在但含空格」的文件会误判为不存在，**从而静默放行覆盖**。这是本项最容易
  踩空的地方：它不是注入漏洞，而是保护失效。
- 解决：路径用**单引号包裹**后传给 shell。由于白名单已排除单引号本身，路径中
  不可能出现能闭合引号的字符，单引号包裹在此是完备的——这一点依赖白名单，
  因此二者必须一起维护，测试里要把「白名单拒绝引号」这条也钉住。
- 与既有模式一致：项目已有固定参数 shell 调用先例（`ime list -s`、
  `rm -f <固定路径>`、`pm list packages`）。区别在于本用途的路径来自调用方输入，
  所以额外依赖白名单 + 引号包裹两重保障，而不是仅靠「参数固定」。

## TOCTOU：如实表述，不夸大

检查与写入之间存在竞态：`test -e` 返回「不存在」之后、`adb push` 落盘之前，
设备上可能有其它进程创建同名文件，此时仍会被覆盖。

这个窗口无法在 adb 协议层消除（`adb push` 没有 O_EXCL 语义）。因此：

- 代码注释与错误信息一律表述为「**已检查该路径当时不存在**」，
  不表述为「保证不覆盖」。
- 本地侧同理：`adb pull` 由外部进程写文件，Go 无法用 `O_EXCL`，
  只能先 `os.Stat` 再执行，同样存在窗口。

把它写进注释，而不是假装不存在。

## 路径范围

用户明确「暂不限制」，本轮不引入路径前缀白名单，沿用 `validateRemotePath` 现有
校验。位置放开与覆盖保护是两件事：位置放开，覆盖必须挡住。

## 锁定测试的处理

`TestFileTransferHasNoCLIEntryPoint`（`direct_surface_test.go:230`）原本锁定
「无 CLI 出口」，现在出口要开，但**不删除该测试**——换锁定目标而非取消：

- push 不覆盖已存在远端文件
- pull 不覆盖已存在本地文件
- 无删除类子命令（`file delete` / `file rm` 等一律不存在）
- MCP 侧仍无出口

## 落地范围

- `internal/adb/device_state.go`：加不覆盖保护与远端存在性检查
- `internal/cli/direct.go`：加 `file push` / `file pull` 子命令
- `internal/cli/direct_surface_test.go`：改写锁定测试
- `docs/protocol.md`：补「不覆盖」语义说明

## 不做的

- 不给 MCP 出口。文件读写是可持久化副作用，模型自主发起的风险档位与人显式调用
  不同，留待单独决策，并在锁定测试里钉住。
- 不加删除能力、不加覆盖开关、不引入路径前缀白名单。

## 验收

- push/pull 到不存在路径成功；到已存在路径被拒且未触达传输
- 路径校验：相对路径、`..` 穿越、非法字符、引号全部被拒
- 含空格路径的存在性检查不误判（本项最易错点，必须有专门用例）
- 无删除出口、MCP 无出口
- 四道门禁通过；不触及 Android 侧则不需要 android-verify
