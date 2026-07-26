// 测试用途：验证 input action 仅经 child stdin 传输，并对 stream 与进程不确定性失败关闭。
import assert from "node:assert/strict";
import { PassThrough, Writable } from "node:stream";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  AactlAdapterError,
  createAactlScenarioPorts,
} from "../adapters/aactl.mjs";
import { runScenario } from "../src/runner.mjs";
import {
  conditionCatalog,
  envelope,
  lifecycle,
  makeExecutable,
  semanticSnapshot,
  SERIAL,
  SECRET_INPUT,
  SYSTEM_PACKAGES,
  TARGET_PACKAGE,
} from "./adapter-helpers.mjs";
import {
  deterministicClock,
  DEVICE,
  oneStepScenario,
  RUN_ID,
} from "./helpers.mjs";

const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url))
  .replace(/\/$/u, "");

const inputAction = Object.freeze({
  type: "input",
  target: Object.freeze({
    kind: "semantic-name",
    name: "Fixture text input",
  }),
  valueRef: "fixture-input",
});

const createAdapter = async (execFile) => {
  const fixture = await makeExecutable();
  const adapter = await createAactlScenarioPorts(
    {
      executable: fixture.executable,
      repositoryRoot,
      serial: SERIAL,
      observationTtlMs: 30_000,
      maxDepth: 64,
      conditionCatalog,
      systemPackages: SYSTEM_PACKAGES,
    },
    {
      execFile,
      lifecycle: lifecycle().port,
      values: {
        resolve: async () => SECRET_INPUT,
      },
      artifacts: {
        collect: async () => ({ retained: false, errorCode: null }),
      },
      now: () => "2026-07-25T12:00:00.000Z",
    },
  );
  return adapter;
};

const snapshotThenInputExec = ({
  inputError = null,
  missingStdin = false,
  processError = null,
  actionErrorCode = null,
  actionData = {
    route: "nodeAction",
    matchedPath: [1],
    matchScore: 1,
  },
} = {}) => {
  const calls = [];
  const execFile = (executable, argv, options, callback) => {
    const call = {
      executable,
      argv,
      options,
      stdinBytes: [],
      stdinReferences: [],
    };
    calls.push(call);
    const stdin = inputError !== null && calls.length === 2
      ? new Writable({
        write: (chunk, _encoding, complete) => {
          call.stdinReferences.push(chunk);
          call.stdinBytes.push(Buffer.from(chunk));
          complete(inputError);
        },
      })
      : new PassThrough();
    if (stdin instanceof PassThrough) {
      stdin.on("data", (chunk) => {
        call.stdinReferences.push(chunk);
        call.stdinBytes.push(Buffer.from(chunk));
      });
    }
    queueMicrotask(() => {
      const snapshot = argv[0] === "bridge" && argv[1] === "snapshot";
      callback(
        snapshot ? null : processError,
        `${envelope({
          ...(snapshot
            ? { data: semanticSnapshot() }
            : actionErrorCode === null
              ? { data: actionData }
              : {
                ok: false,
                error: {
                  code: actionErrorCode,
                  message: "private rejection details",
                  retryable: false,
                },
              }),
        })}\n`,
        "",
      );
    });
    return missingStdin && calls.length === 2
      ? Object.freeze({ kill: () => true })
      : Object.freeze({
        stdin,
        kill: () => true,
      });
  };
  return { calls, execFile };
};

const observeAndRoute = async (adapter) => {
  const observation = await adapter.observer.observe({
    targetPackage: TARGET_PACKAGE,
    timeoutMs: 3000,
    dynamicRegions: [],
  });
  const resolved = await adapter.router.resolve({
    action: inputAction,
    observation,
    targetPackage: TARGET_PACKAGE,
    allowedRoutes: ["semantic"],
    minimumScore: 1,
    dynamicRegions: [],
  });
  return { observation, resolved };
};

test("input argv 不含明文且 child stdin 精确接收固定 JSON 加换行", async () => {
  const fake = snapshotThenInputExec();
  const adapter = await createAdapter(fake.execFile);
  const { observation, resolved } = await observeAndRoute(adapter);

  assert.deepEqual(
    await adapter.executor.execute({
      action: inputAction,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
      value: SECRET_INPUT,
    }),
    { committed: true },
  );

  const actionCall = fake.calls[1];
  assert.deepEqual(actionCall.argv, [
    "bridge",
    "action",
    "--device",
    SERIAL,
    "--stdin",
    "--json",
  ]);
  assert.equal(JSON.stringify(actionCall.argv).includes(SECRET_INPUT), false);
  const stdin = Buffer.concat(actionCall.stdinBytes).toString("utf8");
  assert.equal(stdin.endsWith("\n"), true);
  assert.deepEqual(JSON.parse(stdin), {
    type: "ui.setText",
    params: {
      target: {
        packageName: TARGET_PACKAGE,
        selectorCandidates: [{
          strategy: "contentDescription",
          value: "Fixture text input",
          weight: 1,
          required: true,
        }],
      },
      text: SECRET_INPUT,
    },
  });
  assert.equal(
    actionCall.stdinReferences.every((chunk) =>
      [...chunk].every((value) => value === 0)),
    true,
  );
});

test("input stream 缺失或写入失败均为 unknown 且 token 不可重试", async () => {
  for (const options of [
    { missingStdin: true },
    { inputError: new Error("private stdin failure") },
  ]) {
    const fake = snapshotThenInputExec(options);
    const adapter = await createAdapter(fake.execFile);
    const { observation, resolved } = await observeAndRoute(adapter);
    const execute = () => adapter.executor.execute({
      action: inputAction,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
      value: SECRET_INPUT,
    });

    await assert.rejects(execute, (error) => {
      assert.ok(error instanceof AactlAdapterError);
      assert.equal(error.code, "AACTL_STDIN_FAILED");
      assert.equal(error.message.includes("private"), false);
      return true;
    });
    await assert.rejects(
      execute,
      (error) => error.code === "ROUTE_TOKEN_CONSUMED",
    );
  }
});

test("进程异常即使 stdin 已写入仍保持提交状态未知", async () => {
  const fake = snapshotThenInputExec({
    processError: new Error("private process failure"),
    actionData: {},
  });
  const adapter = await createAdapter(fake.execFile);
  const { observation, resolved } = await observeAndRoute(adapter);

  await assert.rejects(
    () => adapter.executor.execute({
      action: inputAction,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
      value: SECRET_INPUT,
    }),
    (error) => error.code === "AACTL_EXEC_FAILED",
  );
  assert.equal(
    Buffer.concat(fake.calls[1].stdinBytes).toString("utf8").includes(SECRET_INPUT),
    true,
  );
});

test("stdin 非零稳定拒绝映射 committed false", async () => {
  const fake = snapshotThenInputExec({
    processError: Object.assign(new Error("exit 6"), { code: 6 }),
    actionErrorCode: "ACTION_NOT_ALLOWED",
  });
  const adapter = await createAdapter(fake.execFile);
  const { observation, resolved } = await observeAndRoute(adapter);

  assert.deepEqual(
    await adapter.executor.execute({
      action: inputAction,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
      value: SECRET_INPUT,
    }),
    { committed: false },
  );
});

test("过期 deadline 在 stdin/进程调用前明确零提交", async () => {
  const fake = snapshotThenInputExec();
  const adapter = await createAdapter(fake.execFile);
  const { observation, resolved } = await observeAndRoute(adapter);

  assert.deepEqual(
    await adapter.executor.execute({
      action: inputAction,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T11:59:59.000Z",
      value: SECRET_INPUT,
    }),
    { committed: false },
  );
  assert.equal(fake.calls.length, 1);
});

test("Runner input 通过 stdin adapter 完成 post observation 和 cleanup", async () => {
  const fake = snapshotThenInputExec();
  const lifecycleFixture = lifecycle();
  const fixture = await makeExecutable();
  const adapter = await createAactlScenarioPorts(
    {
      executable: fixture.executable,
      repositoryRoot,
      serial: SERIAL,
      observationTtlMs: 30_000,
      maxDepth: 64,
      conditionCatalog,
      systemPackages: SYSTEM_PACKAGES,
    },
    {
      execFile: fake.execFile,
      lifecycle: lifecycleFixture.port,
      values: {
        resolve: async () => SECRET_INPUT,
      },
      artifacts: {
        collect: async () => ({ retained: false, errorCode: null }),
      },
      now: () => "2026-07-25T12:00:00.000Z",
    },
  );
  const base = oneStepScenario({ action: inputAction });
  const scenario = {
    ...base,
    steps: [{
      ...base.steps[0],
      preconditions: [{ id: "click-present", kind: "node-present" }],
      postconditions: [{ id: "click-present", kind: "node-present" }],
    }],
  };

  const result = await runScenario({
    scenario,
    iteration: 1,
    device: DEVICE,
    ports: adapter,
    clock: deterministicClock(),
    runId: RUN_ID,
  });

  assert.equal(result.report.status, "passed");
  assert.equal(result.report.actionCommits, 1);
  assert.deepEqual(
    fake.calls.map(({ argv }) => argv.slice(0, 2)),
    [
      ["bridge", "snapshot"],
      ["bridge", "action"],
      ["bridge", "snapshot"],
    ],
  );
  assert.deepEqual(
    lifecycleFixture.calls.map(([name]) => name),
    ["stopScenario", "closeBridge", "clearAppData", "restoreSnapshot"],
  );
});
