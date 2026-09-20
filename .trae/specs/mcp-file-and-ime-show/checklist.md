# MCP 文件传输与 ime.show 出口验收清单

## 风险门决策

- [x] 文件传输走独立门，未并入 `requireModelActionAllowed`
- [x] `risk.go` 原注释一字未改，其「不写入任何数据」标准继续成立
- [x] 决策理由写进 spec 与 `file_transfer.go` 文件头注释
- [x] 有测试断言 `file.push` / `file.pull` **不在**直连白名单内
- [x] `ime.show` 经核对符合原标准（只改焦点归属），走既有门

## 不覆盖保护（MCP 路径）

- [x] 用真实 `service.New` + fake ADB 验证，而非 fakeAutomation
- [x] push 到已存在远端：MCP 调用被拒，且 `adb push` 未发出
- [x] pull 到已存在本地：被拒，且本地文件内容未被扰动
- [x] 空闲路径可正常传输（防止否定断言因功能坏掉而空洞通过）
- [x] 变异验证：禁用 adb 层 `if exists` 拦截后，MCP 测试如期失败
      （file_transfer_test.go:98），恢复后通过

## schema 约束

- [x] push / pull 均无 force / overwrite / replace / delete 字段
- [x] 两者 `additionalProperties:false`
- [x] pull 的 `localPath` description 写明落盘在 MCP 服务端机器
- [x] 无任何删除类 MCP 工具

## ime.show 诚实表述

- [x] description 明说 not a keyboard visibility switch
- [x] 输出 schema 只有 route，无键盘可见性字段
- [x] 无可聚焦控件时返回 ACTION_NOT_SUPPORTED

## 验证

- [x] `make comments`
- [x] `make test`
- [x] `make verify`
- [x] `make build`
- [x] 未改 Android 侧，`make android-verify` 不适用

## 待设备验证（保持未勾选）

- [ ] 真机上 `aactl bridge ime-show` 的聚焦与键盘行为
- [ ] 真机上 MCP `android_file_push` 到已存在路径的拒绝表现
- [ ] 真机上 `android_file_pull` 的服务端落盘位置确认
