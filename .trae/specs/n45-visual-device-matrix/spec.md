# N45 视觉动作设备矩阵规格

## 目标

在 N31 API 30、33、34 disposable emulator 上，通过 debug/androidTest 专用入口复用
N44 observation 与 N45 production planner/executor，验证显式视觉 tap、long-click、
swipe 在三种分辨率和两种 rotation 下命中本地无敏感 fixture。

## 矩阵

- API：30、33、34。
- 自然方向分辨率：`720x1600`、`1080x2400`、`1440x3200`。
- rotation：0、90。
- 动作：tap、long-click、swipe；每个组合只提交一次动作并重新观察独立后置状态。
- 每个 API 共 18 个命中场景；全部从 N31 `clean` snapshot 开始，最终恢复并停止。

## 安全边界

- 只允许 N31 owned emulator、debug App、instrumentation 双签名和固定 test-only
  marker；真机、release、未知 serial/fingerprint 均拒绝。
- 目标只允许 debug `ScreenshotTestActivity` 或本地 device fixture，不接受外部包、
  任意 Activity、任意坐标、shell 或 raw ADB。
- debug harness 只创建内存 observation/candidate/current-screen/verifier，调用 N45
  production planner 与类型化 Accessibility executor；release APK 静态扫描为零入口。
- 坐标来自当前测试 View bounds 或固定 normalized fixture contract；每步前后重新
  observation，动作返回成功但后置未变化仍失败。
- 低置信度、多候选、过期、包漂移、secure、screen/rotation/density 漂移为零提交。
- 已提交动作后验证失败不得重放。

## 允许修改

- `.trae/specs/n45-visual-device-matrix/`
- `android/app/src/debug/` 的 N45 test-only adapter
- `android/app/src/androidTest/` 的 N45 instrumentation
- N45 专用主机矩阵脚本及测试
- 现有 N45 append-only progress/checklist/tasks
- `docs/next-phase-tasks.md` 由主线程单点更新

## 禁止事项

- 不修改 recording editor/N41、N37/N38 QR、N44 Go、协议 schema、生产动作模型。
- 不把 debug synthetic observation 描述为普通用户授权或真机 evidence。
- 不自动批准真机无障碍、ADB RSA、MediaProjection、相机或系统设置。
- 不提交截图、APK、hierarchy、日志、设备状态、cache 或 SDK 内容。

## 验收

- RED/GREEN 覆盖 debug harness 身份门、production planner/typed executor 与后置门。
- fake runner 覆盖固定 profile/矩阵、serial/fingerprint 漂移、JUnit 新鲜度和清理。
- API 30/33/34 的 18 个环境组合全部完成 tap/long-click/swipe 命中。
- 低置信度、过期、secure、漂移和后置失败设备用例保持零重放。
- App 定向/全量、lint、debug/androidTest/release、matrix tests、comments、
  diff-check、最终设备/runtime/lease 零残留通过。
