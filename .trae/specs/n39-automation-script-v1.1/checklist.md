# N39 验收清单

- [ ] 1.1 字段和 provenance 枚举有严格 Schema
- [ ] 所有 1.0 fixture 无损迁移
- [ ] 未知 major 与损坏 JSON 不覆盖原数据
- [ ] revision 冲突不覆盖较新脚本
- [ ] 原子保存失败保留原文件
- [ ] 导入拒绝未知字段
- [ ] 导出不包含 secret、截图或本地路径
- [ ] Go/Kotlin fixture round-trip 一致
- [ ] 定向测试、`make comments`、Android 构建和 `git diff --check` 通过
