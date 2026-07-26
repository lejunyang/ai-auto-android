# N43 WebView 能力探针验收清单

- [x] 独立 worktree/change spec
- [x] 只读取四类唯一节点的状态和 action ID
- [x] 不调用 performAction、坐标、截图、剪贴板、IME、DOM/JS 或 shell
- [x] 输出不含 bounds、页面文本、hierarchy、截图或设备内容
- [x] API/WebView profile 漂移失败关闭
- [x] API 30/33/34 action matrix 均有设备证据
- [x] 每轮设备和 N31 状态残留为零
- [x] 不把 capability probe 描述为完整场景通过
- [ ] 提交身份、trailer 和 worktree 状态正确
