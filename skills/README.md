# Agent 技能

`skills/` 是可移植 Agent Skills 的发布源：

| Skill | 使用时机 |
| --- | --- |
| `android-device-discovery` | 诊断 ADB，发现 USB/Wi-Fi 设备，配对、连接和显式选择设备 |
| `android-device-observation` | 获取设备信息、截图、UI hierarchy 或 App Bridge 语义快照 |
| `android-device-automation` | 执行类型化动作、Bridge 工作流和录制回放，并处理确认与恢复 |

每个目录均可作为独立 Skill 安装到兼容
[Agent Skills](https://agentskills.io/specification) 的客户端。例如，发布或手动安装时使用：

```text
skills/android-device-discovery
skills/android-device-observation
skills/android-device-automation
```

Trae 从以下项目级路径自动发现对应镜像：

```text
.trae/skills/android-device-discovery
.trae/skills/android-device-observation
.trae/skills/android-device-automation
```

只编辑 `skills/` 发布源，不直接修改 `.trae/skills/`。同步和严格校验命令：

```bash
make skills-sync
make skills-check
```

`skills-sync` 单向重建 Trae 镜像；`skills-check` 检查 frontmatter、引用存在、
`references/` 一层深度、安全约束、CLI 命令 smoke 和两个目录的逐字节一致性。
`make verify` 已包含该严格校验。

环境安装了官方参考工具时，还可逐个运行：

```bash
skills-ref validate skills/android-device-discovery
skills-ref validate skills/android-device-observation
skills-ref validate skills/android-device-automation
```

## 虚拟显示的 CLI 补充说明

仓库新增的虚拟屏管理、`--display N` 和 `action sleep` 仅供电脑侧 CLI 使用，
命令与恢复流程见[虚拟显示操作](../docs/virtual-display.md)。三份已发布 Skill 的
信任、确认与禁止动作边界不变，不能把本功能理解为新增 MCP 或 App AI 权限。

使用显式目标显示的最新截图/窗口列表定位；窗口列表的 format 为
`uiautomator-windows-xml`。普通层级可能返回持焦点的虚拟屏，不能据此判断主屏。
显示目标或 helper 失败时不得去掉参数向主屏重试。DEVICE_LOCKED 等待用户正常解锁；
DISPLAY_ASLEEP 需关闭重建并重新启动应用，须说明原屏应用内容被销毁的效果。
已在主屏运行的任务向虚拟屏启动会整体移过去；API33 的 groupAwake=null。

本轮说明集中在此目录导航中，未更改 Skill 正文或 Trae 镜像，也未向通用技能开放
模拟器脚本的测试 PIN 路径；该路径只用于明确授权的 disposable emulator 验收。
