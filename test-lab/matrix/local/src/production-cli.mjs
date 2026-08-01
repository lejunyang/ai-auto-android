#!/usr/bin/env node

// 脚本用途：以零参数固定入口运行 N52 production matrix，禁止覆盖路径、设备或场景。
import {
  ProductionMatrixError,
  runProductionCli,
} from "./production.mjs";
import {
  createDefaultProductionAdapterFactory,
} from "./production-adapters.mjs";

try {
  const report = await runProductionCli({
    argv: process.argv.slice(2),
    adapterFactory: createDefaultProductionAdapterFactory(),
  });
  process.stdout.write(`${JSON.stringify(report)}\n`);
  if (!report.succeeded) process.exitCode = 1;
} catch (error) {
  const code = error instanceof ProductionMatrixError
    ? error.code
    : "PRODUCTION_INTERNAL_ERROR";
  process.stderr.write(`${code}\n`);
  process.exitCode = code === "PRODUCTION_CLI_ARGUMENT_FORBIDDEN" ? 2 : 1;
}
