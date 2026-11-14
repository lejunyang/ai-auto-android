# 虚拟显示与息屏运行进度

本文件 append-only，记录前提核实、RED/GREEN 与验证结果。

## Round 0：可行性实测与立项（2026-11-14）

- 基线 `f861a61`，开工时 `git status` clean，`git worktree list --porcelain` 只有
  主工作区。worktree `E:/Projects/wt-virtual-display`，分支
  `virtual-display-screen-off`。
- 用户决定实施 N59 第 3 项「多显示屏」，并要求先做虚拟显示与息屏运行。
- 在 AVD `osdk-test-35`（API 35，`-read-only -no-snapshot`，serial
  `emulator-5554`）上用一次性探针实测，结论见 `spec.md` 的「实测依据」。探针不是
  仓库产物，未提交。
- 实测中的两个反例直接决定了设计：
  - 主屏息屏或面板断电后 `screencap` 返回全黑帧，所以虚拟屏的画面必须由辅助
    进程自己提供。
  - 设备被 PIN 锁定后两个显示组都进入 Dozing，虚拟屏帧全黑，且向虚拟屏发
    `KEYCODE_WAKEUP` 唤醒的是主屏。所以锁定状态定为明确报错，不做唤醒尝试。
- 本轮没有任何实现，`tasks.md` 与 `checklist.md` 全部未勾选。
