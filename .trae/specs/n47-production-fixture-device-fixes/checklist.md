# N47 Production Fixture 设备前置修复验收清单

- [x] 默认 UUID provider 在 Node 24 不依赖 fake 注入
- [x] App 数据清理只覆盖本轮实际安装的固定 App 与 fixture
- [x] 连续 hex 与冒号 hex signer 格式均严格支持
- [x] 未知 signer、重复摘要、多 signer 和身份漂移继续失败关闭
- [x] 全部 API 30 失败轮均确认设备、runtime、lease 和临时目录清零
- [x] N47 runner 74/74、provider 8/8、comments 和 diff-check 通过
- [ ] API 30、33、34 三个 profile 的三个场景均有设备结果
- [ ] 成功 profile 的八类 residue 全零
- [x] 全量验证和 Git 身份/trailer 审计通过
- [ ] N47/N52 仅在满足各自完整验收后勾选
