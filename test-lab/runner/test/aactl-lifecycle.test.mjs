// 测试用途：验证 adapter 只透传窄 values/artifacts/lifecycle 端口并拒绝任意执行配置。
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  AactlAdapterError,
  createAactlScenarioPorts,
} from "../adapters/aactl.mjs";
import {
  conditionCatalog,
  fakeExecFile,
  lifecycle,
  makeExecutable,
  SERIAL,
  SYSTEM_PACKAGES,
} from "./adapter-helpers.mjs";

const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url))
  .replace(/\/$/u, "");

const makeDependencies = (lifecyclePort = lifecycle().port) => ({
  execFile: fakeExecFile().execFile,
  lifecycle: lifecyclePort,
  values: {
    resolve: async (valueRef) => `resolved:${valueRef}`,
  },
  artifacts: {
    collect: async ({ run }) => ({
      retained: run.outcome.status === "failed",
      errorCode: run.outcome.errorCode,
    }),
  },
  now: () => "2026-07-25T12:00:00.000Z",
});

const makeConfig = (executable) => ({
  executable,
  repositoryRoot,
  serial: SERIAL,
  observationTtlMs: 30_000,
  maxDepth: 64,
  conditionCatalog,
  systemPackages: SYSTEM_PACKAGES,
});

test("返回恰好七个 Runner 端口并透传 values 与 artifacts", async () => {
  const fixture = await makeExecutable();
  const ports = await createAactlScenarioPorts(
    makeConfig(fixture.executable),
    makeDependencies(),
  );

  assert.deepEqual(Object.keys(ports).sort(), [
    "artifacts",
    "conditions",
    "executor",
    "lifecycle",
    "observer",
    "router",
    "values",
  ]);
  assert.equal(await ports.values.resolve("fixture-input"), "resolved:fixture-input");
  assert.deepEqual(
    await ports.artifacts.collect({
      run: {
        outcome: {
          status: "failed",
          errorCode: "PAGE_UNKNOWN",
        },
      },
    }),
    {
      retained: true,
      errorCode: "PAGE_UNKNOWN",
    },
  );
});

test("四个 lifecycle 方法按原 context 透传且不增加设备发现或命令", async () => {
  const fixture = await makeExecutable();
  const lifecycleFixture = lifecycle();
  const fake = fakeExecFile();
  const ports = await createAactlScenarioPorts(
    makeConfig(fixture.executable),
    {
      ...makeDependencies(lifecycleFixture.port),
      execFile: fake.execFile,
    },
  );
  const context = Object.freeze({
    runId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
    scenarioId: "fixture-scenario",
    iteration: 1,
    targetPackages: Object.freeze(["dev.aiauto.fixture"]),
  });

  await ports.lifecycle.stopScenario(context);
  await ports.lifecycle.closeBridge(context);
  await ports.lifecycle.clearAppData(context);
  await ports.lifecycle.restoreSnapshot(context);

  assert.deepEqual(
    lifecycleFixture.calls.map(([name]) => name),
    ["stopScenario", "closeBridge", "clearAppData", "restoreSnapshot"],
  );
  assert.equal(
    lifecycleFixture.calls.every(([, received]) => received === context),
    true,
  );
  assert.equal(fake.calls.length, 0);
});

test("拒绝未知配置、raw adb、shell、任意 argv 和 condition callback", async () => {
  const fixture = await makeExecutable();
  const invalidConfigs = [
    {
      ...makeConfig(fixture.executable),
      adbPath: "/tmp/adb",
    },
    {
      ...makeConfig(fixture.executable),
      shell: true,
    },
    {
      ...makeConfig(fixture.executable),
      argv: ["shell", "id"],
    },
    {
      ...makeConfig(fixture.executable),
      conditionCatalog: {
        unsafe: {
          kind: "semantic-state",
          package: "dev.aiauto.fixture",
          predicate: () => true,
        },
      },
    },
  ];

  for (const config of invalidConfigs) {
    await assert.rejects(
      () => createAactlScenarioPorts(config, makeDependencies()),
      (error) => {
        assert.ok(error instanceof AactlAdapterError);
        assert.equal(error.code, "AACTL_ADAPTER_CONFIG_INVALID");
        return true;
      },
    );
  }
});

test("缺失 execFile、values、artifacts、lifecycle 或 now 时创建失败", async () => {
  const fixture = await makeExecutable();
  const dependencies = makeDependencies();

  for (const key of Object.keys(dependencies)) {
    const invalid = { ...dependencies };
    delete invalid[key];
    await assert.rejects(
      () => createAactlScenarioPorts(makeConfig(fixture.executable), invalid),
      (error) => error.code === "AACTL_ADAPTER_DEPENDENCY_INVALID",
    );
  }
});
