# N45 视觉动作设备矩阵验收清单

- [x] 独立 change spec、分支和 worktree
- [ ] debug/test-only 身份门拒绝真机、release 和 fingerprint 漂移
- [ ] production planner 与类型化 Accessibility executor 被实际复用
- [ ] tap、long-click、swipe 均有动作返回和独立后置状态
- [ ] 低置信度、多候选、过期、包/屏幕/secure 漂移零提交
- [ ] 后置失败保持一次提交且不重放
- [ ] API 30 三分辨率两 rotation 全部准确命中
- [ ] API 33 三分辨率两 rotation 全部准确命中
- [ ] API 34 三分辨率两 rotation 全部准确命中
- [ ] release APK 零 N45 test harness/marker
- [ ] 最终设备、runtime、lease、owned emulator 和临时目录为零
- [ ] 定向、全量、构建、comments 与 diff-check 通过
- [ ] 提交身份、trailer、集成与 worktree 清理正确
