# 文件传输不覆盖保护验收清单

## 核心不变量

- [x] push 到已存在远端路径失败，且未执行 `adb push`
- [x] pull 到已存在本地路径失败，且本地文件内容未被改动
- [x] 无任何删除类子命令（`file delete` / `rm` / `remove` 均不存在）
- [x] 无 `--force` / `--overwrite` 逃生口
- [x] 保护在 `internal/adb` 层，而非 CLI 参数解析层

## 保护有效性（变异测试实证，非仅看断言通过）

- [x] 把 push 拦截改成永假 → `TestPushFileRefusesToOverwriteAnExistingRemotePath`
      失败（file_transfer_test.go:55），恢复后通过
- [x] 把 `force` / `overwrite` 加进选项白名单 → 锁定测试失败
      （direct_surface_test.go:261），恢复后通过
- [x] 首版 `--force` 断言曾是空洞的（不带值时先以「缺少值」失败，未触及白名单），
      已改为带值传递后重新验证确实生效

## 注入面与误判

- [x] `validateRemotePath` 拒绝引号、分号、反引号、`$`、管道、换行（既有测试覆盖）
- [x] 远端路径单引号包裹，含空格路径不被拆词误判为不存在
- [x] 探测命令为固定结构，路径是唯一变量且已过白名单

## 范围

- [x] 未引入路径前缀白名单（用户明确「暂不限制」）
- [x] MCP 未开出口，并由锁定测试钉住
- [x] 未改动 Android 侧，故不需要 `make android-verify`

## 验证

- [x] `make comments`
- [x] `make test`
- [x] `make verify`
- [x] `make build`

## 待设备验证（保持未勾选）

- [ ] 真机上 push 到已存在路径的实际拒绝表现

  说明：`adb shell test -e` 在真实设备上的退出码与输出已用固定标记方式规避了
  跨实现差异，但未在真机核对。

- [ ] 真机上含空格远端路径的探测与传输往返

- [ ] TOCTOU 窗口：本项无法消除，不作为可验收项，仅在代码与文档中如实标注
