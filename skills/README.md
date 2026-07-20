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
