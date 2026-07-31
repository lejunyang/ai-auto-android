# N38 持久 LAN Bridge RPC 任务

- [x] Task N38-RPC.1：读取 N38/N36 规格、现有 LAN transport 与 Bridge 调度契约
- [x] Task N38-RPC.2：创建独立 change spec 并固定与 Go N37 的共享线契约
- [x] Task N38-RPC.3：先增加 token、入站 frame、adapter 和 session 生命周期 RED 测试
- [x] Task N38-RPC.4：实现严格加密 frame 读取、双向 codec 与 LAN token 派生
- [x] Task N38-RPC.5：实现复用 BridgeDispatcher 的 LAN 专用认证与 close adapter
- [x] Task N38-RPC.6：实现持久 request/response 循环及切网、过期、关闭读中断
- [x] Task N38-RPC.7：完成定向、App 全量、lint、构建、注释和差异验证
- [x] Task N38-RPC.8：记录验收证据并创建单一职责提交
- [x] Task N38-RPC.9：与 Go N37 在真实跨实现 socket 上完成多轮 RPC、致命认证和关闭互操作

## 依赖与波次

1. Wave A 由本 worktree 独占规格与 Android LAN 测试，先取得 RED。
2. Wave B 串行修改 Android LAN transport/session，再增加独立 Bridge LAN adapter；
   不修改现有 loopback `BridgeDispatcher` 对外行为。
3. Wave C 统一执行 Android 全量验证并更新 append-only `progress.md`。
4. Go N37 由其他 worktree 独占；本任务只读其 token 公式和 frame 契约，不覆盖其改动。
5. Wave D 的真实 Go/Kotlin socket 互操作必须等待双方提交集成后串行执行，不以各端
   单元测试或 localhost 自环测试替代。

## 文件所有权

- LAN transport、crypto、session：`android/app/src/main/java/dev/aiauto/android/bridge/lan/`
- LAN dispatcher adapter：仅新增或修改独立 LAN adapter 文件，不重写 loopback server
- 规格状态：仅 `.trae/specs/n38-persistent-lan-rpc/`
- 禁止协议、Go、路线图、Manifest、权限、相机和 Accessibility 文件

## 证据与失败清理

- RED 证据记录缺失 API 或断言失败，不伪造通过。
- 每轮验证记录命令、测试数量和失败原因；最终必须覆盖用户要求的全部命令。
- 失败时关闭 socket/线程池，清零可控 token/key/plaintext，保留 worktree 供审查；
  不执行 `reset`、`clean` 或强制 worktree 删除。

## 建议提交边界

单一提交 `feat(android): serve persistent lan bridge rpc`，包含实现、测试和本 change
spec，不夹带 Go、协议或其他任务改动。
