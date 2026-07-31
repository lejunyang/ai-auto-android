# N41 编辑器设备 instrumentation 规格

## 目标

在不修改生产编辑器、领域层、协议或共享构建配置的前提下，为 N41 真实详情入口补齐
可在 disposable debug emulator 上执行的 Compose instrumentation，并提供一个明确
test-only、无敏感数据、短生命周期的授权截图 provider。当前 change 先建立 RED
证据，设备执行由主线程在 emulator 空闲并完成集成后统一进行。

## 范围

- 使用真实 `RecordingHost`、`RecordingViewModel`、`RecordingScriptStore` 和 N41
  详情编辑入口，自动验证脚本复制、步骤重排/启停/复制/删除、Undo/Redo、dirty
  离开确认、revision conflict 和 dry-run 首失败步骤定位。
- 在 debug source set 提供只生成确定性内存图像的授权截图 provider；图像不来自
  设备页面，不读取文件、MediaProjection、Accessibility screenshot 或网络数据。
- provider 每次只发放一个显式授权，携带短 TTL、observation ID 和合成图像
  SHA-256；释放时清空并回收位图，旧授权失败关闭。
- Compose instrumentation 直接消费 provider，验证点选、框选和保存后的 normalized
  visual metadata 契约；真实详情入口另有 RED 用例要求显示同一授权 surface。

## RED 与最小 GREEN 缺口

- 当前 `RecordingHost` 在 `RecordingDestination.EDITOR` 中把
  `observationHolder` 固定为 `null`，debug provider 无法在不改生产 `main` 的情况下
  注入真实详情入口。最小 GREEN 需要由后续集成 change 为 Host 增加调用方提供的、
  默认 `null` 且不持久化的授权 observation provider/holder 端口。
- 当前 `ObservationSelection.Selected` 只有 `x/y`，表单提交最终只调用
  `EditStepCoordinate`；它不能把本次授权的 `observationId` 和 `imageSha256` 绑定到
  保存后的 `VisualTarget`。最小 GREEN 需要扩展 UI 到 N40 的原子提交适配，但不得
  绕过领域校验或持久化截图字节。
- 当前 observation surface 使用 `detectTapGestures`，没有 drag/bounds selection，
  `RecordingStepFormState` 也没有 normalized bounds。最小 GREEN 需要增加明确的框选
  状态和原子保存路径。
- 上述三个 RED 不在本 change 越界修复；它们必须在获准修改生产 recording UI 和
  必要领域接口后单独转绿。

## 允许修改

- `.trae/specs/n41-editor-device-instrumentation/`
- `android/app/src/androidTest/java/dev/aiauto/android/ui/recording/`
- `android/app/src/debug/java/dev/aiauto/android/ui/recording/`

## 禁止修改

- `docs/next-phase-tasks.md`
- 共享 Gradle、SDK、协议和 device fixture
- `android/app/src/main/` 下的编辑器、domain、导航或其他生产代码
- N33、N43、N45、N46、N47、N52 所有权目录

## 验收

- N41 JVM 定向测试通过。
- `:app:compileDebugAndroidTestKotlin` 与 `:app:assembleDebugAndroidTest` 通过。
- `:app:lintDebug`、`make comments` 和 `git diff --check` 通过。
- instrumentation 只记录为“源码已编译、等待设备 RED 执行”，不得描述为设备通过。
- 主线程集成后在明确 serial 的 disposable debug emulator 上逐项执行；每次动作前后
  重新观察，不自行启动、抢占或隐式选择当前由其他任务使用的设备。

## 失败与清理

- 每个测试只删除自身 `N41 设备` 前缀脚本，不清理其他录制数据。
- provider 在 composition 离开、测试结束或 TTL 过期时幂等释放并清零合成位图。
- 设备 RED 只接受规格列出的三类缺口；其他崩溃、超时或状态污染必须先收集证据，
  不盲目重试。
