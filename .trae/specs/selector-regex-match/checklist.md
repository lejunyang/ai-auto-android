# C12 regex 验收清单

## 引擎不对称决策

- [x] 核实 match 只有 Kotlin SelectorMatcher 一条执行引擎（Go 判据侧 NodeSelector
      无 match 字段，只有判据运算符 matches 用 Go RE2，是独立路径）
- [x] 选方案一（RE2 兼容取向 + 保守防护），理由写入 spec：ReDoS 与是否分叉无关，
      仍需防；限定行为为将来对齐 Go RE2 留余地

## ReDoS 三层防护

- [x] 限长：pattern > 512 在 parser 入口拒绝
- [x] 编译校验前置：非法 pattern 入口报 INVALID_ARGUMENT，不留运行时
- [x] 执行超时：DeadlineCharSequence 每次 charAt 检查 100ms 截止，超时判不匹配
- [x] 不新起线程（per-match 开销大且线程无法安全强杀），deadline-charAt 是
      java.util.regex ReDoS 的标准缓解

## ReDoS 实测（关键，非声明）

- [x] 先用独立探针实测：JDK 21 会优化 (a+)+$ 不回溯，(.*a){15}$ 才真正灾难性
      （3s 仍未结束）——据此改用后者，否则测试形同虚设
- [x] 有防护时 ReDoS 测试 0.106s 通过（超时在 100ms 触发）
- [x] 变异验证：去掉 DeadlineCharSequence → 该测试因 JUnit4 timeout=5000 超时变红
- [x] 变异验证：MAX_REGEX_PATTERN_LENGTH 放大到 100000 → 超长测试变红

## 作用维度

- [x] regex 只作用 text/contentDescription
- [x] 标识符维度（resourceId/className/package）拒绝 regex，两层守卫
      （ProviderActionParser + AccessibilityCommandJsonParser）都覆盖

## version.json

- [x] add-enum-value（match 加第 5 值），默认仍 exact，不触发 requiresNewMajor

## 门禁

- [x] make comments 559 文件
- [x] make test
- [x] make verify 协议 56 项
- [x] make build
- [x] make android-verify：删 test-results 强制重跑，74 XML / 561 tests / 0 failures

## 待设备验证（保持未勾选）

- [ ] 真机上 regex 选择器对真实动态文本的命中稳定性
- [ ] 真机上灾难性 pattern 的超时行为（模拟器 JVM 与真机 ART 的 regex 引擎不同，
      ART 的回溯特性需真机确认；100ms 超时是否够用可能要按真机调）
