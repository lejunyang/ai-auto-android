# N33 平台滚动 Surface 验收清单

- [x] 独立 change spec/worktree
- [x] 临时 platform surface 不覆盖 dispatch/intercept/touch
- [x] 设备测试不直接调用 scrollTo/scrollBy 伪造结果
- [x] 两种失败均保留真实 offset/action 返回值
- [x] 已知失败实现已撤销，fixture 源码与基线一致
- [ ] API 30 单轮核心场景通过
- [ ] API 30/33/34 重复矩阵有真实设备证据
- [x] 最终设备和 N31 状态残留为零
- [x] 提交身份、trailer 和集成状态正确
