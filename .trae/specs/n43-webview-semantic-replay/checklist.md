# N43 验收清单

- [x] full、partial、canvas 映射为完整语义、混合和仅视觉结果
- [x] 受限或无法验证后置条件的页面映射为不支持
- [x] 点击、输入、长按、滚动、跳转和 Back 只生成现有类型化动作
- [x] 输出动作不包含 tap、swipe、recorded bounds 或归一化坐标回退
- [x] 动态 DOM、iframe 或动作能力缺失返回 `SEMANTIC_UNAVAILABLE`
- [x] 节点歧义、package/page/runtime 漂移和 observation 过期失败关闭
- [x] 首步失败场景动作提交数为零，执行后失败停止后续动作
- [x] 流程先保存、再 reset，并逐步验证前置与后置语义状态
- [x] 默认在动作提交前重新观察，页面或运行时变化返回 `OBSERVATION_STALE`
- [x] 纯单元测试覆盖完整、部分、Canvas、动态缺失、iframe 缺失和多页面回放
- [x] 设备 instrumentation 可通过独立端口接入且无需修改 recording core
- [ ] API 30 完整语义流程连续 20 轮成功率不低于 95%
- [ ] API 33 完整语义流程连续 20 轮成功率不低于 95%
- [ ] API 34 完整语义流程连续 20 轮成功率不低于 95%
- [ ] 60 轮均从 clean snapshot 开始并完成 fixture 数据和产物清理
- [x] 定向测试、完整 app 单测、`make comments` 和 `git diff --check` 通过

## 当前结论

N43 的独立领域实现和 JVM 回归证据已完成，但设备矩阵尚未执行。N42 在三套 API 上
各 3 项 instrumentation 的证据只证明虚拟节点基线可用，不能替代 N43 的跨页面流程
各 20 轮验收，因此本清单不宣称 N43 完成。
