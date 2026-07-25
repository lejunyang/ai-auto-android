// 测试用途：验证 aactl adapter 的语义观察、条件、route、一次性动作和页面分类边界。
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  AactlAdapterError,
  createAactlScenarioPorts,
} from "../adapters/aactl.mjs";
import { runScenario } from "../src/runner.mjs";
import {
  conditionCatalog,
  fakeExecFile,
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

const rejectsCode = async (operation, code) => {
  await assert.rejects(operation, (error) => {
    assert.ok(error instanceof AactlAdapterError);
    assert.equal(error.code, code);
    return true;
  });
};

const makeAdapter = async ({
  fake = fakeExecFile(),
  catalog = conditionCatalog,
  now = () => "2026-07-25T12:00:00.000Z",
  lifecyclePort = lifecycle().port,
} = {}) => {
  const fixture = await makeExecutable();
  const adapter = await createAactlScenarioPorts(
    {
      executable: fixture.executable,
      repositoryRoot,
      serial: SERIAL,
      observationTtlMs: 30_000,
      maxDepth: 64,
      conditionCatalog: catalog,
      systemPackages: SYSTEM_PACKAGES,
    },
    {
      execFile: fake.execFile,
      lifecycle: lifecyclePort,
      values: {
        resolve: async () => SECRET_INPUT,
      },
      artifacts: {
        collect: async ({ run }) => ({
          retained: run.outcome.status === "failed",
          errorCode: run.outcome.errorCode,
        }),
      },
      now,
    },
  );
  return { adapter, fake, fixture };
};

const observe = (adapter, targetPackage = TARGET_PACKAGE) =>
  adapter.observer.observe({
    targetPackage,
    timeoutMs: 3000,
    dynamicRegions: [],
  });

const route = (adapter, observation, action, allowedRoutes = ["semantic"]) =>
  adapter.router.resolve({
    action,
    observation,
    targetPackage: TARGET_PACKAGE,
    allowedRoutes,
    minimumScore: 1,
    dynamicRegions: [],
  });

test("observer 返回短期 observation、真实前台包和保守 normal 分类", async () => {
  const { adapter } = await makeAdapter();

  const observation = await observe(adapter);

  assert.match(observation.observationId, /^aactl-observation-[a-f0-9]{64}$/u);
  assert.deepEqual(observation, {
    observationId: observation.observationId,
    observedAt: "2026-07-25T12:00:00.000Z",
    expiresAt: "2026-07-25T12:00:30.000Z",
    foregroundPackage: TARGET_PACKAGE,
    pageClass: "normal",
  });
  assert.equal(JSON.stringify(observation).includes("Fixture click target"), false);
});

test("页面标签按广告更新登录墙模拟器网络和 unknown 保守分类", async () => {
  const cases = [
    ["Advertisement sponsored", "advertisement"],
    ["Update available", "update-prompt"],
    ["Sign in to continue", "login-wall"],
    ["Emulator is not supported", "emulator-detected"],
    ["Network unavailable", "network-failure"],
  ];
  for (const [label, expected] of cases) {
    const fake = fakeExecFile({
      snapshots: [semanticSnapshot({ labels: [label] })],
    });
    const { adapter } = await makeAdapter({ fake });
    assert.equal((await observe(adapter)).pageClass, expected);
  }

  const fake = fakeExecFile({
    snapshots: [{
      root: {
        packageName: TARGET_PACKAGE,
        className: "android.view.View",
        bounds: { left: 0, top: 0, right: 1, bottom: 1 },
        actions: [],
        state: {
          checkable: false,
          checked: false,
          clickable: false,
          enabled: true,
          editable: false,
          focusable: false,
          focused: false,
          longClickable: false,
          password: false,
          scrollable: false,
          selected: false,
          visibleToUser: true,
          sensitive: false,
        },
        children: [],
      },
      maxDepth: 64,
    }],
  });
  const { adapter } = await makeAdapter({ fake });
  assert.equal((await observe(adapter)).pageClass, "unknown");
});

test("snapshot maxDepth 漂移和未知节点字段严格拒绝", async () => {
  for (const snapshot of [
    { ...semanticSnapshot(), maxDepth: 63 },
    {
      ...semanticSnapshot(),
      root: {
        ...semanticSnapshot().root,
        privateField: "must-not-pass",
      },
    },
  ]) {
    const fake = fakeExecFile({ snapshots: [snapshot] });
    const { adapter } = await makeAdapter({ fake });
    await rejectsCode(
      () => observe(adapter),
      "SNAPSHOT_INVALID",
    );
  }
});

test("condition catalog 验证前台包、状态、节点存在和不存在", async () => {
  const { adapter } = await makeAdapter();
  const observation = await observe(adapter);

  for (const [id, kind] of [
    ["target-foreground", "foreground-package"],
    ["main-page", "semantic-state"],
    ["click-present", "node-present"],
    ["missing-node", "node-absent"],
  ]) {
    assert.equal(
      await adapter.conditions.verify({
        phase: "pre",
        conditions: [{ id, kind }],
        observation,
        targetPackage: TARGET_PACKAGE,
        deadlineAt: "2026-07-25T12:00:03.000Z",
      }),
      true,
    );
  }
  assert.equal(
    await adapter.conditions.verify({
      phase: "pre",
      conditions: [{ id: "missing-node", kind: "node-present" }],
      observation,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    false,
  );
});

test("node-absent 不把存在但敏感的节点误判为不存在", async () => {
  const catalog = {
    ...conditionCatalog,
    "click-absent": {
      kind: "node-absent",
      package: TARGET_PACKAGE,
      target: { name: "Fixture click target" },
    },
  };
  const fake = fakeExecFile({
    snapshots: [semanticSnapshot({ sensitiveClick: true })],
  });
  const { adapter } = await makeAdapter({ fake, catalog });
  const observation = await observe(adapter);

  assert.equal(
    await adapter.conditions.verify({
      phase: "pre",
      conditions: [{ id: "click-absent", kind: "node-absent" }],
      observation,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    false,
  );
});

test("visual-state 和 visual hybrid route 未接 production port 时失败关闭", async () => {
  const catalog = {
    ...conditionCatalog,
    visual: {
      kind: "visual-state",
      package: TARGET_PACKAGE,
    },
  };
  const { adapter } = await makeAdapter({ catalog });
  const observation = await observe(adapter);

  await rejectsCode(
    () => adapter.conditions.verify({
      phase: "pre",
      conditions: [{ id: "visual", kind: "visual-state" }],
      observation,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    "VISUAL_ADAPTER_UNAVAILABLE",
  );
  for (const allowedRoutes of [["visual"], ["hybrid"]]) {
    await rejectsCode(
      () => route(
        adapter,
        observation,
        {
          type: "tap",
          target: { kind: "semantic-name", name: "Fixture click target" },
        },
        allowedRoutes,
      ),
      "ROUTE_UNAVAILABLE",
    );
  }
});

test("唯一可见启用非敏感节点才能生成 semantic route", async () => {
  for (const [snapshot, code] of [
    [semanticSnapshot({ duplicateClick: true }), "SELECTOR_AMBIGUOUS"],
    [semanticSnapshot({ sensitiveClick: true }), "TARGET_SENSITIVE"],
  ]) {
    const fake = fakeExecFile({ snapshots: [snapshot] });
    const { adapter } = await makeAdapter({ fake });
    const observation = await observe(adapter);
    await rejectsCode(
      () => route(adapter, observation, {
        type: "tap",
        target: { kind: "semantic-name", name: "Fixture click target" },
      }),
      code,
    );
    assert.equal(fake.calls.length, 1);
  }
});

test("仅 text 命中的节点生成 text selector 而非 contentDescription", async () => {
  const snapshot = semanticSnapshot();
  snapshot.root.children[0] = {
    ...snapshot.root.children[0],
    text: "Text-only click target",
  };
  delete snapshot.root.children[0].contentDescription;
  const fake = fakeExecFile({ snapshots: [snapshot] });
  const { adapter } = await makeAdapter({ fake });
  const observation = await observe(adapter);
  const action = {
    type: "tap",
    target: { kind: "semantic-name", name: "Text-only click target" },
  };
  const resolved = await route(adapter, observation, action);

  await adapter.executor.execute({
    action,
    route: resolved.route,
    routeToken: resolved.token,
    observationId: observation.observationId,
    targetPackage: TARGET_PACKAGE,
    deadlineAt: "2026-07-25T12:00:03.000Z",
  });
  assert.equal(
    JSON.parse(fake.calls[1].argv[5])
      .params.target.selectorCandidates[0].strategy,
    "text",
  );
});

test("tap long-click scroll 构造固定 Bridge action 且 token 只能消费一次", async () => {
  const actions = [
    {
      action: {
        type: "tap",
        target: { kind: "semantic-name", name: "Fixture click target" },
      },
      expected: {
        type: "ui.click",
        params: {
          target: {
            packageName: TARGET_PACKAGE,
            selectorCandidates: [{
              strategy: "contentDescription",
              value: "Fixture click target",
              weight: 1,
              required: true,
            }],
          },
        },
      },
    },
    {
      action: {
        type: "long-click",
        target: { kind: "semantic-name", name: "Fixture long press target" },
      },
      expected: {
        type: "ui.longClick",
        params: {
          target: {
            packageName: TARGET_PACKAGE,
            selectorCandidates: [{
              strategy: "contentDescription",
              value: "Fixture long press target",
              weight: 1,
              required: true,
            }],
          },
        },
      },
    },
    {
      action: {
        type: "scroll",
        target: {
          kind: "semantic-name",
          name: "Fixture vertical scroll target",
        },
        direction: "down",
      },
      expected: {
        type: "ui.scroll",
        params: {
          target: {
            packageName: TARGET_PACKAGE,
            selectorCandidates: [{
              strategy: "contentDescription",
              value: "Fixture vertical scroll target",
              weight: 1,
              required: true,
            }],
          },
          direction: "down",
        },
      },
    },
  ];

  for (const entry of actions) {
    const fake = fakeExecFile();
    const { adapter } = await makeAdapter({ fake });
    const observation = await observe(adapter);
    const resolved = await route(adapter, observation, entry.action);
    assert.deepEqual(
      { route: resolved.route, score: resolved.score, attempts: resolved.attempts },
      { route: "semantic", score: 1, attempts: 1 },
    );

    assert.deepEqual(
      await adapter.executor.execute({
        action: entry.action,
        route: resolved.route,
        routeToken: resolved.token,
        observationId: observation.observationId,
        targetPackage: TARGET_PACKAGE,
        deadlineAt: "2026-07-25T12:00:03.000Z",
        value: entry.value,
      }),
      { committed: true },
    );
    const call = fake.calls[1];
    assert.deepEqual(call.argv.slice(0, 5), [
      "bridge",
      "action",
      "--device",
      SERIAL,
      "--action",
    ]);
    assert.deepEqual(JSON.parse(call.argv[5]), entry.expected);
    assert.deepEqual(call.argv.slice(6), ["--json"]);

    await rejectsCode(
      () => adapter.executor.execute({
        action: entry.action,
        route: resolved.route,
        routeToken: resolved.token,
        observationId: observation.observationId,
        targetPackage: TARGET_PACKAGE,
        deadlineAt: "2026-07-25T12:00:03.000Z",
        value: entry.value,
      }),
      "ROUTE_TOKEN_CONSUMED",
    );
    assert.equal(fake.calls.length, 2);
  }
});

test("Back Home Recents 使用固定空 params Bridge action", async () => {
  for (const [type, protocolType] of [
    ["back", "ui.back"],
    ["home", "ui.home"],
    ["recents", "ui.recents"],
  ]) {
    const fake = fakeExecFile();
    const { adapter } = await makeAdapter({ fake });
    const observation = await observe(adapter);
    const action = { type };
    const resolved = await route(adapter, observation, action);
    await adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    });
    assert.deepEqual(JSON.parse(fake.calls[1].argv[5]), {
      type: protocolType,
      params: {},
    });
  }
});

test("launch 与 switch-app 只走固定 direct launch argv", async () => {
  for (const type of ["launch", "switch-app"]) {
    const fake = fakeExecFile();
    const { adapter } = await makeAdapter({ fake });
    const observation = await observe(adapter);
    const action = { type, package: TARGET_PACKAGE };
    const resolved = await route(adapter, observation, action);
    await adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    });
    assert.deepEqual(fake.calls[1].argv, [
      "action",
      "launch",
      "--device",
      SERIAL,
      "--package",
      TARGET_PACKAGE,
      "--json",
    ]);
  }
});

test("明确 system package observation 可经 switch-app 安全返回目标包", async () => {
  const fake = fakeExecFile({
    snapshots: [
      semanticSnapshot({ packageName: "com.android.launcher" }),
      semanticSnapshot({ packageName: TARGET_PACKAGE }),
    ],
  });
  const catalog = {
    ...conditionCatalog,
    launcher: {
      kind: "foreground-package",
      package: "com.android.launcher",
    },
  };
  const { adapter } = await makeAdapter({ fake, catalog });
  const launcherObservation = await observe(adapter, TARGET_PACKAGE);

  assert.equal(launcherObservation.foregroundPackage, "com.android.launcher");
  assert.equal(launcherObservation.pageClass, "normal");
  assert.equal(
    await adapter.conditions.verify({
      phase: "pre",
      conditions: [{ id: "launcher", kind: "foreground-package" }],
      observation: launcherObservation,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    true,
  );
  const action = { type: "switch-app", package: TARGET_PACKAGE };
  const resolved = await route(adapter, launcherObservation, action);
  await adapter.executor.execute({
    action,
    route: resolved.route,
    routeToken: resolved.token,
    observationId: launcherObservation.observationId,
    targetPackage: TARGET_PACKAGE,
    deadlineAt: "2026-07-25T12:00:03.000Z",
  });
  assert.deepEqual(fake.calls[1].argv, [
    "action",
    "launch",
    "--device",
    SERIAL,
    "--package",
    TARGET_PACKAGE,
    "--json",
  ]);
  assert.equal((await observe(adapter)).foregroundPackage, TARGET_PACKAGE);
});

test("CLI 明确动作拒绝映射 committed false，进程异常抛出 unknown", async () => {
  const rejected = fakeExecFile({
    onCall: (_call, index) => index === 2
      ? {
        stdout: `${JSON.stringify({
          schemaVersion: "1.0",
          requestId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
          ok: false,
          data: null,
          error: {
            code: "ACTION_NOT_ALLOWED",
            message: "rejected",
            retryable: false,
          },
          meta: { durationMs: 1 },
        })}\n`,
        stderr: "",
      }
      : undefined,
  });
  const { adapter: rejectedAdapter } = await makeAdapter({ fake: rejected });
  const observation = await observe(rejectedAdapter);
  const action = {
    type: "tap",
    target: { kind: "semantic-name", name: "Fixture click target" },
  };
  const resolved = await route(rejectedAdapter, observation, action);
  assert.deepEqual(
    await rejectedAdapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    { committed: false },
  );

  const failed = fakeExecFile({
    onCall: (_call, index) => index === 2
      ? { error: new Error("private process details"), stdout: "", stderr: "" }
      : undefined,
  });
  const { adapter: failedAdapter } = await makeAdapter({ fake: failed });
  const failedObservation = await observe(failedAdapter);
  const failedRoute = await route(failedAdapter, failedObservation, action);
  await rejectsCode(
    () => failedAdapter.executor.execute({
      action,
      route: failedRoute.route,
      routeToken: failedRoute.token,
      observationId: failedObservation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    "AACTL_EXEC_FAILED",
  );
});

test("动作成功信封 data 畸形时提交状态未知且旧 token 不可重试", async () => {
  const fake = fakeExecFile({
    actionData: {
      route: "not-a-real-route",
      privateField: true,
    },
  });
  const { adapter } = await makeAdapter({ fake });
  const observation = await observe(adapter);
  const action = {
    type: "tap",
    target: { kind: "semantic-name", name: "Fixture click target" },
  };
  const resolved = await route(adapter, observation, action);

  await rejectsCode(
    () => adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    "AACTL_ACTION_RESULT_INVALID",
  );
  await rejectsCode(
    () => adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    "ROUTE_TOKEN_CONSUMED",
  );
  assert.equal(fake.calls.length, 2);
});

test("过期 deadline 在动作进程调用前明确零提交并消费旧 token", async () => {
  const fake = fakeExecFile();
  const { adapter } = await makeAdapter({ fake });
  const observation = await observe(adapter);
  const action = {
    type: "tap",
    target: { kind: "semantic-name", name: "Fixture click target" },
  };
  const resolved = await route(adapter, observation, action);

  assert.deepEqual(
    await adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T11:59:59.000Z",
    }),
    { committed: false },
  );
  await rejectsCode(
    () => adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    "ROUTE_TOKEN_CONSUMED",
  );
  assert.equal(fake.calls.length, 1);
});

test("lifecycle 开始后旧 observation 和 route token 均失效", async () => {
  const fake = fakeExecFile();
  const lifecycleFixture = lifecycle();
  const { adapter } = await makeAdapter({
    fake,
    lifecyclePort: lifecycleFixture.port,
  });
  const observation = await observe(adapter);
  const action = {
    type: "tap",
    target: { kind: "semantic-name", name: "Fixture click target" },
  };
  const resolved = await route(adapter, observation, action);
  const context = Object.freeze({
    runId: RUN_ID,
    scenarioId: "fixture-scenario",
    iteration: 1,
    targetPackages: Object.freeze([TARGET_PACKAGE]),
  });

  await adapter.lifecycle.stopScenario(context);
  await rejectsCode(
    () => route(adapter, observation, action),
    "OBSERVATION_UNAVAILABLE",
  );
  await rejectsCode(
    () => adapter.executor.execute({
      action,
      route: resolved.route,
      routeToken: resolved.token,
      observationId: observation.observationId,
      targetPackage: TARGET_PACKAGE,
      deadlineAt: "2026-07-25T12:00:03.000Z",
    }),
    "ROUTE_TOKEN_INVALID",
  );
  assert.equal(fake.calls.length, 1);
});

test("input 未有安全 stdin 模式时在 route 阶段零进程调用失败关闭", async () => {
  const fake = fakeExecFile();
  const { adapter } = await makeAdapter({ fake });
  const observation = await observe(adapter);
  const action = {
    type: "input",
    target: { kind: "semantic-name", name: "Fixture text input" },
    valueRef: "fixture-input",
  };
  await rejectsCode(
    () => route(adapter, observation, action),
    "INPUT_ADAPTER_UNAVAILABLE",
  );
  assert.equal(fake.calls.length, 1);
  assert.equal(JSON.stringify(observation).includes(SECRET_INPUT), false);
});

test("Runner 通过 production adapter 完成 snapshot action post snapshot 和清理", async () => {
  const fake = fakeExecFile({
    snapshots: [semanticSnapshot(), semanticSnapshot()],
  });
  const lifecycleFixture = lifecycle();
  const { adapter } = await makeAdapter({
    fake,
    lifecyclePort: lifecycleFixture.port,
  });
  const base = oneStepScenario();
  const scenario = {
    ...base,
    steps: [{
      ...base.steps[0],
      preconditions: [{
        id: "click-present",
        kind: "node-present",
      }],
      postconditions: [{
        id: "click-present",
        kind: "node-present",
      }],
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
  assert.notEqual(
    result.report.steps[0].preObservationId,
    result.report.steps[0].postObservationId,
  );
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
