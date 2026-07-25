# N46 Tasks

- [x] Task N46.1：审计 N31、N34、安全边界和目录所有权
- [x] Task N46.2：定义严格文本 manifest 与 JSON Schema
- [x] Task N46.3：先编写来源、缓存、元数据和生命周期失败测试
- [x] Task N46.4：实现仓库外 cache 定位、文件 hash 与大小验证
- [x] Task N46.5：实现类型化 inspector、verified descriptor 和固定 argv provider
- [x] Task N46.6：实现 clean AVD 前置、App 数据清理和最终恢复接口契约
- [x] Task N46.7：实现工作区、Git 历史和 `.gitignore` APK 扫描
- [x] Task N46.8：完成专用测试、注释、diff 和范围验证
- [x] Task N46.9：以单一职责 Conventional Commit 提交

## 依赖

N46 依赖已完成的 N31 deterministic emulator runner 和 N34 failure artifact
collector。本任务基线为 `7974815`，与 N44 及其他并行任务目录互斥。
