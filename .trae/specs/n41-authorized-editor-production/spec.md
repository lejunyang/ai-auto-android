# N41 授权截图编辑器生产接线规格

## 目标

把 N41 已保留的三项设备 RED 接入生产编辑器：调用方可向真实 `RecordingHost`
显式提供一个短生命周期授权 observation，用户可在截图 surface 上点选或框选，并以
单个领域命令原子保存 normalized geometry、`observationId` 与 `imageSha256`。普通
入口默认不提供 observation，也不得显示截图 surface。

## 范围

- 为 `RecordingHost` 增加默认 `null` 的窄授权 observation provider；provider 只交付
  内存 holder，图片所有权仍属于调用方，编辑器退出或授权过期即释放。
- observation selection 同时携带 normalized point 或 bounds 以及授权元数据，不暴露
  图片字节、Base64、文件路径或可恢复图片状态。
- 新增原子 visual target 编辑命令，在一个不可分割的校验和历史提交中更新 geometry、
  observation/hash、步骤 provenance 与必要的 action target。
- Compose surface 用确定性手势区分 tap 与 drag；drag 仅由当前 surface 的实际尺寸
  归一化，不猜测屏幕坐标。
- 删除现有三项 `@Ignore`，保持原断言语义并让 Host 注入、point 保存和 bounds 保存
  instrumentation 契约真实转绿。

## 安全边界

- provider 必须由调用方显式注入；未注入、已释放、已过期或 metadata 非法时失败关闭。
- holder、provider 与图片租约不进入 `ViewModel`、saved state、脚本、repository 或
  导出；脚本只保存 normalized geometry、observation ID 与 SHA-256。
- point/bounds、observation ID 与 hash 必须由同一个领域命令校验和提交；拒绝时不得
  留下部分字段、Undo 历史或 dirty state。
- 手动修改坐标不得沿用旧授权 observation/hash，避免 geometry 与图片 provenance
  不一致。
- 本 change 不操作设备；API 34 instrumentation 由主线程在明确 serial 上统一执行。

## 允许修改

- `.trae/specs/n41-authorized-editor-production/`
- `.trae/specs/n41-editor-device-instrumentation/` 的 append-only 进展与状态
- `android/app/src/main/java/dev/aiauto/android/ui/recording/` 及对应测试
- 为原子 visual metadata 保存所必需的 recording editor command/domain adapter
- debug 授权 provider 与现有三项 RED instrumentation

## 禁止修改

- `docs/next-phase-tasks.md`
- Go、协议 schema、Gradle、SDK、device fixture
- N44、N45、N47、N52 所有权目录
- 其他 worktree、设备或 emulator 状态

## 验收

- 三项 ignored RED 删除 `@Ignore` 后可编译并保持原契约断言。
- JVM 覆盖 provider 失败关闭、point/bounds 原子保存、非法 metadata 原子拒绝、手动
  坐标清除旧授权 metadata 与 Undo/Redo。
- App 全量 JVM、Debug AndroidTest 编译/组装、lint、debug/release build、
  `make comments` 和 `git diff --check` 通过。
- API 34 设备 instrumentation 尚未执行时必须明确记录为待主线程验证，不得描述为
  设备已通过。

## 失败与清理

- 任何 selection 或领域校验失败都保持原脚本不变，不创建部分 history。
- composition 离开、provider 替换或测试结束时幂等释放 holder 和图片租约。
- 放弃本 change 时仅撤销本分支提交；不得清理或覆盖并行 worktree。
