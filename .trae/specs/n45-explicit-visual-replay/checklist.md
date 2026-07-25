# N45 验收清单

- [x] visual/coordinate/manual 跳过语义 selector 且 semantic 行为无回归
- [x] tap、long-click、swipe 只通过类型化 executor 提交
- [x] 三种分辨率和 0/90/180/270 的映射单测通过
- [x] natural screen、density、system bar、window offset 和 crop 逆映射受校验
- [x] observation ID、唯一候选、前台包、屏幕元数据和过期时间在动作前绑定
- [x] 低置信度、多候选、`FLAG_SECURE` 和越界动作提交数为零
- [x] 每个成功动作都经过只读 observation/snapshot verifier
- [x] 后置验证失败明确失败且不自动重复已提交动作
- [x] 无 raw sendevent、shell、剪贴板、DOM/JS 或隐式坐标猜测
- [x] N45 定向测试、App 全量单测、lint、comments 和 diff-check 通过
- [ ] API 30 fixture 在至少三种分辨率和两种 rotation 准确命中
- [ ] API 33 fixture 在至少三种分辨率和两种 rotation 准确命中
- [ ] API 34 fixture 在至少三种分辨率和两种 rotation 准确命中
- [ ] 提交为单一职责、身份与 trailer 正确且 worktree 干净
