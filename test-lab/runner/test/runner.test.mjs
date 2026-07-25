// 测试用途：验证场景 Runner 的包、页面、route、后置条件、产物和清理安全边界。
import assert from "node:assert/strict";
import test from "node:test";

import {
  runScenario,
  ScenarioRunnerError,
} from "../src/runner.mjs";
import {
  deterministicClock,
  DEVICE,
  fakePorts,
  loadFixture,
  NATIVE_PACKAGE,
  oneStepScenario,
  RUN_ID,
  SECRET_INPUT,
  WEB_PACKAGE,
} from "./helpers.mjs";

const execute = async ({
  scenario = oneStepScenario(),
  portOptions = {},
  iteration = 1,
} = {}) => {
  const clock = deterministicClock();
  const fixture = fakePorts(portOptions);
  const result = await runScenario({
    scenario,
    iteration,
    device: DEVICE,
    ports: fixture.ports,
    clock,
    runId: RUN_ID,
  });
  return {
    ...result,
    calls: fixture.calls,
  };
};

test("成功步骤记录 route score attempts 与双 observation 且执行全部清理", async () => {
  const { report, artifactRun, calls } = await execute();

  assert.equal(report.status, "passed");
  assert.equal(report.actionCommits, 1);
  assert.deepEqual(report.steps[0], {
    sequence: 1,
    stepId: "fixture-step",
    actionType: "tap",
    targetPackage: "dev.aiauto.fixture",
    status: "passed",
    errorCode: null,
    pageClass: "normal",
    route: "semantic",
    score: 1,
    attempts: 1,
    preObservationId: "observation-pre",
    postObservationId: "observation-post",
    actionCommits: 1,
    durationMs: 0,
  });
  assert.equal(artifactRun.outcome.status, "passed");
  assert.deepEqual(calls.slice(-4), [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ]);
});

test("广告更新模拟器检测网络失败登录墙和 unknown 均在动作前停止", async () => {
  const classifications = [
    ["advertisement", "PAGE_ADVERTISEMENT"],
    ["update-prompt", "PAGE_UPDATE_PROMPT"],
    ["emulator-detected", "PAGE_EMULATOR_DETECTED"],
    ["network-failure", "PAGE_NETWORK_FAILURE"],
    ["login-wall", "PAGE_LOGIN_WALL"],
    ["unknown", "PAGE_UNKNOWN"],
  ];

  for (const [pageClass, expectedCode] of classifications) {
    const { report, calls } = await execute({
      portOptions: { pageClass },
    });

    assert.equal(report.status, "failed", pageClass);
    assert.equal(report.errorCode, expectedCode, pageClass);
    assert.equal(report.actionCommits, 0, pageClass);
    assert.equal(calls.includes("route"), false, pageClass);
    assert.equal(calls.includes("execute"), false, pageClass);
    assert.equal(calls.includes("collect"), true, pageClass);
  }
});

test("A-B variant 只有步骤显式接受时才能执行", async () => {
  const rejected = await execute({
    portOptions: { pageClass: "ab-variant" },
  });
  assert.equal(rejected.report.errorCode, "PAGE_AB_VARIANT");
  assert.equal(rejected.report.actionCommits, 0);

  const accepted = await execute({
    scenario: oneStepScenario({
      acceptedPageClasses: ["normal", "ab-variant"],
    }),
    portOptions: { pageClass: "ab-variant" },
  });
  assert.equal(accepted.report.status, "passed");
  assert.equal(accepted.report.actionCommits, 1);
});

test("包漂移 route 不允许 score 过低和 observation 漂移均零提交", async () => {
  const cases = [
    {
      portOptions: { prePackage: "com.example.drift" },
      code: "FOREGROUND_PACKAGE_MISMATCH",
    },
    {
      portOptions: { route: "visual" },
      code: "ROUTE_NOT_ALLOWED",
    },
    {
      portOptions: { score: 0.5 },
      code: "ROUTE_SCORE_TOO_LOW",
    },
  ];
  for (const entry of cases) {
    const { report, calls } = await execute(entry);
    assert.equal(report.errorCode, entry.code);
    assert.equal(report.actionCommits, 0);
    assert.equal(calls.includes("execute"), false);
  }

  const fixture = fakePorts();
  fixture.ports.router.resolve = async () => ({
    route: "semantic",
    score: 1,
    attempts: 1,
    observationId: "stale-observation",
    token: {},
  });
  const result = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: fixture.ports,
    clock: deterministicClock(),
    runId: RUN_ID,
  });
  assert.equal(result.report.errorCode, "ROUTE_OBSERVATION_MISMATCH");
  assert.equal(result.report.actionCommits, 0);
  assert.equal(fixture.calls.includes("execute"), false);
});

test("后置条件失败记录一次提交并不重放动作", async () => {
  const { report, calls } = await execute({
    portOptions: { postconditionsPass: false },
  });

  assert.equal(report.errorCode, "POSTCONDITION_FAILED");
  assert.equal(report.actionCommits, 1);
  assert.equal(report.steps[0].actionCommits, 1);
  assert.equal(calls.filter((name) => name === "execute").length, 1);
  assert.equal(calls.filter((name) => name === "observe").length, 2);
});

test("native 与 Web fixture 多步骤实际执行全部十类动作和三种 route", async () => {
  const scenarios = [
    await loadFixture("native-fixture.scenario.json"),
    await loadFixture("web-fixture.scenario.json"),
  ];
  const reports = [];
  const executedActions = [];
  const executedRoutes = [];
  for (const [scenarioIndex, scenario] of scenarios.entries()) {
    let observationSequence = 0;
    let currentPackage = scenario.targetPackages[0];
    let currentObservationId = null;
    const calls = [];
    const ports = {
      observer: {
        observe: async () => {
          observationSequence += 1;
          currentObservationId = `observation-${scenarioIndex}-${observationSequence}`;
          return {
            observationId: currentObservationId,
            observedAt: "2026-07-25T12:00:00.000Z",
            expiresAt: "2026-07-25T12:01:00.000Z",
            foregroundPackage: currentPackage,
            pageClass: "normal",
          };
        },
      },
      conditions: {
        verify: async () => true,
      },
      router: {
        resolve: async ({ allowedRoutes }) => {
          const route = allowedRoutes.includes("visual")
            ? "visual"
            : allowedRoutes.includes("hybrid")
              ? "hybrid"
              : "semantic";
          return {
            route,
            score: 1,
            attempts: 1,
            observationId: currentObservationId,
            token: { observationId: currentObservationId },
          };
        },
      },
      executor: {
        execute: async ({ action, route, value }) => {
          executedActions.push(action.type);
          executedRoutes.push(route);
          if (action.type === "launch" || action.type === "switch-app") {
            currentPackage = action.package;
          }
          if (action.type === "home") {
            currentPackage = "com.android.launcher";
          }
          if (action.type === "recents") {
            currentPackage = "com.android.systemui";
          }
          if (action.type === "input") {
            assert.equal(value, SECRET_INPUT);
          }
          return { committed: true };
        },
      },
      values: {
        resolve: async () => SECRET_INPUT,
      },
      artifacts: {
        collect: async () => ({ retained: false, errorCode: null }),
      },
      lifecycle: {
        stopScenario: async () => calls.push("stopScenario"),
        closeBridge: async () => calls.push("closeBridge"),
        clearAppData: async () => calls.push("clearAppData"),
        restoreSnapshot: async () => calls.push("restoreSnapshot"),
      },
    };
    const result = await runScenario({
      scenario,
      iteration: scenarioIndex + 1,
      device: DEVICE,
      ports,
      clock: deterministicClock(),
      runId: scenarioIndex === 0
        ? "018f47a2-4bc8-7f31-8b9a-1234567890a1"
        : "018f47a2-4bc8-7f31-8b9a-1234567890a2",
    });
    reports.push(result.report);
    assert.equal(result.report.status, "passed");
    assert.equal(result.report.actionCommits, scenario.steps.length);
    assert.deepEqual(calls, [
      "stopScenario",
      "closeBridge",
      "clearAppData",
      "restoreSnapshot",
    ]);
  }

  assert.deepEqual([...new Set(executedActions)].sort(), [
    "back",
    "home",
    "input",
    "launch",
    "long-click",
    "recents",
    "scroll",
    "swipe",
    "switch-app",
    "tap",
  ]);
  assert.deepEqual([...new Set(executedRoutes)].sort(), [
    "hybrid",
    "semantic",
    "visual",
  ]);
  assert.equal(reports.reduce((sum, report) => sum + report.steps.length, 0), 18);
});

test("input 明文只传给 executor 且不进入报告或 N34 结果", async () => {
  const scenario = oneStepScenario({
    action: {
      type: "input",
      target: {
        kind: "semantic-name",
        name: "Fixture text input",
      },
      valueRef: "fixture-input",
    },
  });
  const fixture = fakePorts();
  let observedValue = null;
  fixture.ports.executor.execute = async ({ value }) => {
    fixture.calls.push("execute");
    observedValue = value;
    return { committed: true };
  };

  const result = await runScenario({
    scenario,
    iteration: 1,
    device: DEVICE,
    ports: fixture.ports,
    clock: deterministicClock(),
    runId: RUN_ID,
  });
  const encoded = JSON.stringify(result);

  assert.equal(observedValue, SECRET_INPUT);
  assert.equal(encoded.includes(SECRET_INPUT), false);
  assert.equal(encoded.includes("fixture-input"), false);
});

test("deadline 前后失败分别保持零提交和一次提交且不重放", async () => {
  const beforeClock = deterministicClock();
  const beforeFixture = fakePorts();
  beforeFixture.ports.conditions.verify = async ({ phase }) => {
    if (phase === "pre") beforeClock.advance(4000);
    return true;
  };
  const before = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: beforeFixture.ports,
    clock: beforeClock,
    runId: RUN_ID,
  });
  assert.equal(before.report.errorCode, "STEP_TIMEOUT");
  assert.equal(before.report.actionCommits, 0);
  assert.equal(beforeFixture.calls.includes("execute"), false);

  const afterClock = deterministicClock();
  const afterFixture = fakePorts();
  afterFixture.ports.executor.execute = async () => {
    afterFixture.calls.push("execute");
    afterClock.advance(4000);
    return { committed: true };
  };
  const after = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: afterFixture.ports,
    clock: afterClock,
    runId: RUN_ID,
  });
  assert.equal(after.report.errorCode, "STEP_TIMEOUT");
  assert.equal(after.report.actionCommits, 1);
  assert.equal(afterFixture.calls.filter((name) => name === "execute").length, 1);
});

test("executor 异常只输出稳定错误码且不暴露自由消息", async () => {
  const privateMessage = "private executor details";
  const { report, artifactRun } = await execute({
    portOptions: { executorFailure: privateMessage },
  });
  const encoded = JSON.stringify({ report, artifactRun });

  assert.equal(report.errorCode, "ACTION_COMMIT_UNKNOWN");
  assert.equal(report.actionCommits, null);
  assert.equal(report.steps[0].actionCommits, null);
  assert.equal(encoded.includes(privateMessage), false);
});

test("executor 明确拒绝才能记录零提交", async () => {
  const fixture = fakePorts();
  fixture.ports.executor.execute = async () => {
    fixture.calls.push("execute");
    return { committed: false };
  };

  const result = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: fixture.ports,
    clock: deterministicClock(),
    runId: RUN_ID,
  });

  assert.equal(result.report.errorCode, "ACTION_REJECTED");
  assert.equal(result.report.actionCommits, 0);
  assert.equal(result.report.steps[0].actionCommits, 0);
  assert.equal(fixture.calls.filter((name) => name === "execute").length, 1);
});

test("取消在动作前零提交并仍采集稳定失败和执行清理", async () => {
  const fixture = fakePorts();
  fixture.ports.observer.observe = async () => {
    fixture.calls.push("observe");
    const error = new Error("private cancel details");
    error.name = "AbortError";
    throw error;
  };

  const result = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: fixture.ports,
    clock: deterministicClock(),
    runId: RUN_ID,
  });

  assert.equal(result.report.status, "cancelled");
  assert.equal(result.report.errorCode, "SCENARIO_CANCELLED");
  assert.equal(result.report.actionCommits, 0);
  assert.equal(result.artifactRun.outcome.status, "failed");
  assert.equal(result.artifactRun.outcome.errorCode, "SCENARIO_CANCELLED");
  assert.equal(JSON.stringify(result).includes("private cancel details"), false);
  assert.deepEqual(fixture.calls.slice(-4), [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ]);
});

test("失败产物在清理前采集且 collector 异常不泄露或阻止清理", async () => {
  const fixture = fakePorts({ pageClass: "unknown" });
  fixture.ports.artifacts.collect = async () => {
    fixture.calls.push("collect");
    throw new Error("private artifact details");
  };

  const result = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: fixture.ports,
    clock: deterministicClock(),
    runId: RUN_ID,
  });

  assert.equal(result.report.errorCode, "PAGE_UNKNOWN");
  assert.deepEqual(result.artifact, {
    retained: false,
    errorCode: "ARTIFACT_COLLECTION_FAILED",
  });
  assert.ok(
    fixture.calls.indexOf("collect") < fixture.calls.indexOf("stopScenario"),
  );
  assert.equal(JSON.stringify(result).includes("private artifact details"), false);
  assert.deepEqual(fixture.calls.slice(-4), [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ]);
});

test("清理任一步失败仍尝试四步并覆盖成功状态", async () => {
  const { report, calls } = await execute({
    portOptions: { failCleanup: "closeBridge" },
  });

  assert.equal(report.status, "failed");
  assert.equal(report.errorCode, "SCENARIO_CLEANUP_FAILED");
  assert.deepEqual(report.cleanup, {
    attempted: 4,
    completed: 3,
    errorCode: "SCENARIO_CLEANUP_FAILED",
  });
  assert.deepEqual(calls.filter((name) => [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ].includes(name)), [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ]);
});

test("业务失败后清理再失败时 collector 保留原错误且最终报告标记清理风险", async () => {
  const fixture = fakePorts({
    pageClass: "advertisement",
    failCleanup: "closeBridge",
  });
  let collectedRun = null;
  fixture.ports.artifacts.collect = async ({ run }) => {
    fixture.calls.push("collect");
    collectedRun = run;
    return { retained: true, errorCode: run.outcome.errorCode };
  };

  const result = await runScenario({
    scenario: oneStepScenario(),
    iteration: 1,
    device: DEVICE,
    ports: fixture.ports,
    clock: deterministicClock(),
    runId: RUN_ID,
  });

  assert.equal(collectedRun.outcome.errorCode, "PAGE_ADVERTISEMENT");
  assert.equal(result.artifactRun.outcome.errorCode, "PAGE_ADVERTISEMENT");
  assert.equal(result.report.errorCode, "SCENARIO_CLEANUP_FAILED");
  assert.equal(result.report.cleanup.errorCode, "SCENARIO_CLEANUP_FAILED");
  assert.equal(fixture.calls.filter((name) => name === "collect").length, 1);
});

test("无效端口和越权页面 allowlist 在任何观察前拒绝", async () => {
  const fixture = fakePorts();
  const invalid = oneStepScenario({
    acceptedPageClasses: ["normal", "advertisement"],
  });

  await assert.rejects(
    () => runScenario({
      scenario: invalid,
      iteration: 1,
      device: DEVICE,
      ports: fixture.ports,
      clock: deterministicClock(),
      runId: RUN_ID,
    }),
    (error) => {
      assert.ok(error instanceof ScenarioRunnerError);
      assert.equal(error.code, "SCENARIO_SCHEMA_INVALID");
      return true;
    },
  );
  assert.deepEqual(fixture.calls, []);

  await assert.rejects(
    () => runScenario({
      scenario: oneStepScenario(),
      iteration: 1,
      device: DEVICE,
      ports: {},
      clock: deterministicClock(),
      runId: RUN_ID,
    }),
    (error) => error.code === "RUNNER_PORT_INVALID",
  );
});

test("非法 N34 设备元数据在任何观察前拒绝", async () => {
  const fixture = fakePorts();

  await assert.rejects(
    () => runScenario({
      scenario: oneStepScenario(),
      iteration: 1,
      device: {
        ...DEVICE,
        serial: "../unsafe-device",
      },
      ports: fixture.ports,
      clock: deterministicClock(),
      runId: RUN_ID,
    }),
    (error) => error.code === "RUNNER_INPUT_INVALID"
      || error.code === "ARTIFACT_RUN_INVALID",
  );
  assert.deepEqual(fixture.calls, []);
});
