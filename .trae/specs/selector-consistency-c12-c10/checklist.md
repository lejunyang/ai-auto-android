# 验收清单

## 任务 A（破坏性变更）

- [x] 只改 predicate.go 398/402 两处选择器字段匹配
- [x] 447 行 OperatorContains 运算符实现原样保留（已 grep 确认）
- [x] text 与 description 一起改，未遗留 description 同类问题
- [x] normalize 对称：两侧都 trim+lowercase，"  NEXT  " 不再分叉
- [x] version.json 归类 narrow-valid-range，结论写入 spec
- [x] 协议 56 项校验不受影响（运行时行为，schema 未变）——已实测
- [x] 一致性测试锁定：同一 (节点,选择器) 两侧命中判定一致
- [x] 变异验证：text 改回 Contains → 一致性测试在 "text partial" 失败

## 任务 B

- [x] className/package 精确匹配，与判据侧口径一致
- [x] 三处 SelectorStrategy 穷举点全部同步（否则编译失败或录制拒绝）

## 任务 C

- [x] match 默认 exact，不传时行为不变
- [x] 变异验证：默认改 contains → "default match is equivalent to exact" 失败
- [x] 未引入 regex
- [x] 标识符维度（resourceId/className/package）非精确匹配被运行时拒绝

## 任务 D

- [x] 判据求值抽取无回归：C11 的 newWindow/scrollFinished/uiStable 全部通过
- [x] 命中即停，未滚满 maxScrolls
- [x] 目标不出现时滚满 maxScrolls 返回未成立，不报错
- [x] maxScrolls 越界（0/51）在滚动前被拒
- [x] until 判据复用 A 统一后的选择器与 C 的 match 语义
- [x] 轮询 vs 事件语义落差写入 schema description

## 门禁

- [x] make comments 559 文件
- [x] make test
- [x] make verify 协议 56 项
- [x] make build
- [x] make android-verify：删 test-results 强制重跑，74 XML / 555 tests / 0 failures

## 待设备验证（保持未勾选）

- [ ] 真机：统一到精确匹配后，原本靠部分匹配成立的判据是否有回归
      （模拟器上文本常带省略号/空白，normalize 覆盖大小写与首尾空白，
       但中间省略号需真机确认）
- [ ] 真机：className/package 选择器在真实 App 上的命中稳定性
- [ ] 真机：match=contains/startsWith/endsWith 对动态文本的实际命中
- [ ] 真机：ui.scrollUntil 在长列表上的滚动次数与命中时机（400ms 轮询间隔校准）
