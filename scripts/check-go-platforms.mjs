// 脚本用途：跨系统编译并检查 Go 生产与测试源码，防止平台文件名让通用实现或测试被遗漏。
import { spawnSync } from "node:child_process";

const go = process.argv[2] ?? "go";

for (const target of ["linux", "darwin", "windows"]) {
  console.log(`GOOS=${target} CGO_ENABLED=0 ${go} vet ./...`);
  // 直接设置子进程环境，兼容 Windows 与 POSIX shell；禁用 cgo 避免要求交叉 C 编译器。
  // go vet 会编译测试源码，但不执行目标系统的测试二进制或设备测试。
  const result = spawnSync(go, ["vet", "./..."], {
    env: { ...process.env, GOOS: target, CGO_ENABLED: "0" },
    stdio: "inherit",
  });
  if (result.error) console.error(result.error.message);
  if (result.status !== 0) process.exit(result.status ?? 1);
  console.log(`GOOS=${target}: PASS`);
}
