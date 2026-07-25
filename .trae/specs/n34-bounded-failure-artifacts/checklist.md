# N34 验收清单

- [x] 独立 change spec 已建立且 progress 仅追加
- [x] 结果 Schema 严格约束 run、scenario、设备、时间线、产物和结果
- [x] timeout 与 assertion failure 的完整 fake-input 产物测试通过
- [x] 密码、验证码、token、配对码、API Key 和目标 App 文本统一脱敏
- [x] 嵌套、大小写、URL、Base64、Base64URL 和 Hex 变体测试通过
- [x] 未证明安全的 PNG 或无法判断文本只保留稳定错误码摘要
- [x] 截图信任锚来自调用进程，输入 processorId 与 self-hash 不能自行建立信任
- [x] verifier 缺失、返回 false 或抛错均失败关闭，默认 CLI 不保留 PNG
- [x] 单文件、单场景和整轮预算确定性截断
- [x] 成功立即清理，失败 TTL 到期清理，复核均无残留
- [x] 20 轮统计可复现并区分 product/device/infrastructure/flaky
- [x] workflow 只执行语义 smoke，不冒充真实设备矩阵
- [x] 专用测试、Schema 验证、`git diff --check` 和 `make comments` 通过
- [x] Git 中无运行产物、截图、日志、secret、APK 或缓存
