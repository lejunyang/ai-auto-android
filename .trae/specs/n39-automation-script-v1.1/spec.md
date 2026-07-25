# N39 AutomationScript 1.1 规格

## 目标

扩展录制脚本模型，使编辑器和视觉步骤具备版本迁移、来源追踪、并发保护和安全导入
导出能力，同时保证现有 1.0 脚本无损迁移。

## 范围

- 增加 `revision`、`updatedAt`、`provenance`、`enabled`、`visualTarget`、
  `notes` 及兼容等待和重试字段。
- 定义 `semantic`、`visual`、`coordinate`、`manual`、`imported` 来源语义。
- 实现 1.0 到 1.1 迁移、未知 major 拒绝、原子保存和 revision 乐观锁。
- 实现严格 JSON 导入导出；导出默认移除 secret、截图和设备本地路径。
- 保持 Go/Kotlin 消费的 Schema 与 fixture round-trip 一致。

## 安全边界

- 迁移失败不得覆盖原 1.0 文件。
- revision 冲突不得覆盖较新数据，必须保留双方内容供上层处理。
- 导入拒绝未知字段、损坏 JSON 和未知 major。
- 导出不得包含明文 secret、截图字节或设备本地绝对路径。

## 允许修改

- `protocol/schema/`
- `protocol/fixtures/`
- `protocol/fixtures/manifest.json`
- Android recording core model、store、migration 及对应测试
- 本 change spec

## 验收

- 所有 1.0 fixture 无损迁移到 1.1。
- 1.1 Schema、Kotlin 编解码与 fixture round-trip 一致。
- revision 冲突和保存失败不覆盖已有数据。
- 导入导出对秘密、截图和本地路径失败关闭或脱敏。
- 协议测试、Android 定向单测、`make comments` 和 `git diff --check` 通过。
