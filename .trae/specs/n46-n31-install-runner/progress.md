# N46 N31 安装 Runner 进度

本文件 append-only，记录实现、设备安装、清理和集成证据。

## Round 1

- 五个官方 APK 已由 N46 verifier 在仓库外 cache 签发 descriptor，但现有 lifecycle
  只有 fake runner contract；设备技能禁止主线程用 raw ADB 绕过。
- 本轮实现仓库内固定 argv adapter，不增加 MCP/通用 CLI 安装能力；设备安装后拉回
  唯一 base APK 做同字节复核，再恢复 N31 clean snapshot。

## Round 2

- 新 runner 只接受进程内 verified descriptor、固定 `api-30|33|34`、N31 启动结果
  serial/fingerprint 和环境中统一的 android-tools 根；调用方不能传 serial、APK
  路径、ADB executable 或任意 argv。
- 安装前把无扩展名 cache artifact 复制为外置临时 `install.apk` 并再次校验
  size/SHA-256；等待明确 serial 的 package manager ready 后，以固定 argv 安装。
  安装后要求唯一 `/data/app/.../base.apk`，拉回外置临时目录并再次执行同
  size/SHA-256、package、version/versionCode、ABI、minSdk 和签名验证。
- 首轮设备 smoke 暴露 Platform-Tools 37 成功输出含 `Performing Push Install`、
  `Success` 和一条固定传输统计；runner 现在只允许该严格格式或单独 `Success`。
  安装未成功时跳过 `pm clear`，保留 primary error；所有路径仍恢复 clean snapshot、
  删除临时文件并停止 owned emulator。
- runner/CLI 定向 7/7、N46 全量 49/49、仓库/历史 APK 扫描、comments 和
  diff-check 通过。
- API 30 的 B站、抖音、小黑盒、微信和支付宝 5/5 均完成安装、设备 base APK
  同字节/签名复核、数据清理、clean restore 和 stop；每轮后设备、runtime、lease
  与临时文件为零。API 33/34 矩阵正在串行执行。

## Round 3

- API 30、33、34 × B站 `9.5.0`、抖音 `39.8.0`、小黑盒 `1.3.392`、微信
  `8.0.76`、支付宝 `12.12.10.8000` 共 15 个唯一组合全部完成 verified install、
  设备 base APK 拉回同 size/SHA-256、六项 inspector/签名复核、`pm clear`、clean
  restore 和 runner stop。
- API 34 微信首次在 5 分钟 pull 预算后返回 `INSTALLED_INSPECTION_FAILED`，该轮
  设备/runtime/lease/temp 均清零；将大 APK pull 独立预算调整为 10 分钟并增加测试
  后，在新 clean serial 重跑成功。总计 16 次尝试、15/15 唯一组合成功。
- Platform-Tools 37 的成功安装输出包含 `Performing Push Install`、`Success` 与
  严格单文件传输统计；runner 只接受该格式或单独 `Success`。cache artifact 保持无
  扩展名，安装使用外置临时同字节 `install.apk`，final restore 后临时目录为空。
- 最终 `aactl` 为 0 devices，N31 runtime、AVD/port lease、owned emulator 与 APK
  临时目录均为零。该矩阵只证明合法制品安装与同字节恢复，不等于 N48-N51 动态场景
  或登录/小程序能力完成。
