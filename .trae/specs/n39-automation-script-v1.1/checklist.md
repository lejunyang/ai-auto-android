# N39 验收清单

- [x] 1.1 字段和 provenance 枚举有严格 Schema
- [x] 所有 1.0 fixture 无损迁移
- [x] 未知 major 与损坏 JSON 不覆盖原数据
- [x] revision 冲突不覆盖较新脚本
- [x] 原子保存失败保留原文件
- [x] 导入拒绝未知字段
- [x] 导出不包含 secret、截图或本地路径
- [x] Go/Kotlin fixture round-trip 一致
- [x] 定向测试、`make comments`、Android 构建和 `git diff --check` 通过
