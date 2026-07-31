# N46 五包被动启动观察进度

本文件 append-only，记录设计、RED/GREEN、设备观察、清理和集成证据。

## Round 1

- N46 已完成五包官方来源、descriptor、API 30/33/34 安装同字节证明；N47 已有
  Bridge semantic production adapter，但真实第三方 App 尚未验收，且该 adapter
  需要已建立 Bridge，不适合最小 raw hierarchy smoke。
- 当前最小安全路径固定为复用 `runVerifiedExternalAppLifecycle` 与
  `createN31InstallRunner`，scenario 仅执行 `aactl device info`、package-only
  `aactl action launch` 和一次 `aactl observe hierarchy`。不增加任意动作端口，
  不点击页面，不启用无障碍或 Bridge。
- 五个固定包为 `tv.danmaku.bili`、`com.ss.android.ugc.aweme`、
  `com.max.xiaoheihe`、`com.tencent.mm` 和 `com.eg.android.AlipayGphone`。
  package-only launch 只证明 Launcher 命令已提交，最终必须由唯一一次 hierarchy
  区分目标包、系统权限页或其他保守页面信号。

## Round 2

- core scenario 固定且仅固定三条 `aactl` 调用：`device info`、package-only
  `action launch`、一次 `observe hierarchy`；调用方不能传入 package、serial、
  activity 或动作。启动后只等待 3 秒，不点击、输入、按键、滑动、停止 App、打开
  Bridge 或采集截图。
- hierarchy 在内存中复核 UTF-8 字节数与 SHA-256，只输出节点数、合法 package
  集合、target package 可见性和七个固定布尔信号；XML、text、content-desc、
  bounds、截图和 secret 不进入返回值或报告。runner 与矩阵 fake 测试覆盖歧义输出、
  复用场景、上下文漂移、单包观察不可用继续、安装复核失败继续、清理失败终止和
  `STOP_FAILED_LOCK_RETAINED` 单次固定 stop 重试。
- N46 Node 全量从 49/49 增至 58/58；仓库与全部 Git 历史的 APK/APKS/AAB/XAPK
  路径扫描仍为零，comments 与 diff-check 通过。

## Round 3

- API 34 首两轮均在 B站 `hierarchy` 阶段返回 `SCENARIO_FAILED`，每轮都完成当前
  App 数据清理、clean restore、emulator stop 并确认 0 devices/runtime/lease/temp。
  增加仅含 package、固定 stage、稳定 errorCode 和 `cleaned:true` 的 0600 失败报告，
  未记录 XML 或页面文本。
- 五包矩阵调整为逐包独立：仅 `SCENARIO_FAILED` 或
  `INSTALLED_INSPECTION_FAILED` 在 lifecycle 已完成 final restore 后分类当前包并
  继续；安装失败、数据清理失败和 final restore 失败仍立即终止。
- API 34 最终报告 5 包均完成 verified install 与清理；B站取得 hierarchy 但 target
  package 不可见，抖音 hierarchy 不可用，小黑盒、微信、支付宝 target package
  可见。小黑盒和支付宝出现 consent/permission 信号，支付宝另有 network failure
  信号；runner 未点击这些页面。

## Round 4

- API 33 五包均完成 verified install 与清理；小黑盒、支付宝 hierarchy 可用且
  target package 可见，B站、抖音、微信 hierarchy 不可用。小黑盒和支付宝仍出现
  consent/permission 信号，支付宝仍出现 network failure 信号。
- API 30 首轮业务完成后 emulator 已从 ADB 下线，但 N31 stop 未在预算内确认进程
  退出并返回 `STOP_FAILED_LOCK_RETAINED`；runtime/lease 保留。主线程核对 serial、
  pid、fingerprint 与 lease ID 后通过同一 N31 固定 stop 成功释放，没有强删锁。
- API 30 第二轮在抖音设备 `base.apk` 复核返回 `INSTALLED_INSPECTION_FAILED`，
  已清理且无残留。最终轮五包均完成 verified install 和 hierarchy，五个 target
  package 均可见；B站出现 consent/login 信号，小黑盒出现 consent/permission，
  支付宝出现 consent/permission/network failure，抖音和微信未命中固定信号。
- 三份外置报告 `passive-app-smoke-api-{30,33,34}.json` 均为 `0600`，无敏感字段；
  最终设备、runtime、AVD/port lease、APK staging/temporary 与 runner temp 均为零。
  这些结果只建立被动启动基线，不等于 N48-N51 多动作、十轮兼容率或小程序探索完成。

## Round 5

- N46 Node 全量最终 58/58、被动 runner 定向 9/9、固定 argv 静态审计、仓库与全部
  Git 历史 APK 扫描、`make comments` 和 `git diff --check` 通过。
- `make test`、`make verify`、`make build` 通过，覆盖 Go 全包、协议 52 项、Skills、
  comments 和 repository metadata。最终 doctor 健康，`aactl devices list` 为
  0，外置 runtime、lease、APK staging/temporary 和 runner temp 再次确认为零。
- 三份成功/分类报告只含固定 metadata 与布尔信号；静态扫描确认无 `xml`、`text`、
  `content-desc`、bounds、screenshot、token 或 cookie 字段。新增脚本的唯一动作
  argv 为 package-only launch，唯一观察 argv 为 hierarchy。

## Round 6

- 单一职责提交 `a6eccc8` 已以等价集成提交 `bc5a213` 落入 `main`，stable
  patch-id 均为 `fc80dd9cd74ed99c64b5f63943caa8dbe482a1be`。两个提交的 Author 与
  Committer 均为 `lejunyang <lejunyang@qq.com>`，提交末尾恰好一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。
- 临时 worktree 干净且已非强制移除，随后执行 `git worktree prune` 并删除已等价
  集成的临时分支；三份 `0600` 外置设备报告保留，主分支工作区只剩本状态收口。
