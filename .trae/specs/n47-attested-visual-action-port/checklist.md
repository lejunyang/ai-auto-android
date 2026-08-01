# N47 可信桌面视觉动作验收清单

- [x] MCP 输入无坐标、candidate/observation/hash/handle，输出无候选 geometry 或图片
- [x] 单次服务调用绑定 serial、device fingerprint、package、observation、PNG、crop、
  screen、rotation、density、insets、confidence 与 expiry
- [x] 低置信度、多候选、secure 和全部 evidence 漂移均零提交
- [x] Android 端调用 N45 planner 与 package-bound Accessibility typed executor
- [x] 动作后重新观察，提交后 transport/verification 异常为 unknown commit且不重放
- [x] PNG 在 Bridge write、失败、取消和完成后清零
- [x] release/真机/未授权路径失败关闭，自动入口仅 debug/test disposable emulator
- [x] Go race/vet、Android JVM/lint/assemble 与仓库全量门禁通过
- [ ] production provider/device evidence 明确保持未验收
- [x] N47 路线图未勾选
