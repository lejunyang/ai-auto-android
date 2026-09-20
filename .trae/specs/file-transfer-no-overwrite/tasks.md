# 文件传输 CLI 出口任务

## 实现

- [x] `internal/adb`：push 前探测远端存在性，已存在则 `ACTION_NOT_ALLOWED`
- [x] `internal/adb`：pull 前 `os.Stat` 检查本地，已存在则 `ACTION_NOT_ALLOWED`
- [x] 探测失败时拒绝传输，不把「测不出来」当作「不存在」
- [x] 远端路径单引号包裹，解决含空格路径的拆词误判
- [x] `internal/cli`：新增 `file push` / `file pull`
- [x] 不提供删除能力，不提供 `--force` / `--overwrite`
- [x] `docs/protocol.md` 补不覆盖语义、注入面与 TOCTOU 说明

## 测试

- [x] push 到已存在远端被拒且未发出 push
- [x] push 到空闲远端成功
- [x] push 对含空格的已存在路径正确识别（防拆词误判）
- [x] 探测失败时拒绝
- [x] pull 到已存在本地被拒且本地内容未被扰动
- [x] pull 到空闲本地成功
- [x] 锁定测试改锁新不变量：无删除、无覆盖开关、MCP 无出口、正常出口存在
- [x] 变异测试确认断言非空洞

## 收尾

- [x] 四道门禁
- [x] 提交
