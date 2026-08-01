// 测试用途：验证 Canvas visual route 只调用无坐标 MCP 工具并保留一次性提交语义。
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  createAttestedVisualScenarioPorts,
} from "../adapters/attested-visual.mjs";
import {
  fakePorts,
  NATIVE_PACKAGE,
} from "./helpers.mjs";
import {
  makeExecutable,
  SERIAL,
} from "./adapter-helpers.mjs";

const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url))
  .replace(/\/$/u, "");
const fingerprint = "a".repeat(64);
const observation = Object.freeze({
  observationId: "visual-observation-fresh",
  observedAt: "2026-08-01T12:00:00.000Z",
  expiresAt: "2026-08-01T12:00:30.000Z",
  foregroundPackage: NATIVE_PACKAGE,
  pageClass: "normal",
});
const action = Object.freeze({
  type: "tap",
  target: Object.freeze({
    kind: "semantic-name",
    name: "Canvas fixture canvas-result ready",
  }),
});

const mcpOutput = ({
  commitStatus = "committed",
  actionCommits = 1,
  succeeded = true,
  verified = true,
  returnedFingerprint = fingerprint,
  errorCode,
} = {}) => {
  const structuredContent = {
    succeeded,
    observationId: "019fbdf0-0000-7000-8000-000000000047",
    deviceFingerprint: returnedFingerprint,
    screenFingerprint: "b".repeat(64),
    route: "attested-visual",
    verified,
    commitStatus,
    actionCommits,
    ...(errorCode === undefined ? {} : { errorCode }),
  };
  return [
    JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    result: {
      protocolVersion: "2025-06-18",
      capabilities: { tools: {} },
      serverInfo: { name: "aactl", version: "dev" },
    },
  }),
    JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      result: {
        content: [{
          type: "text",
          text: JSON.stringify(structuredContent),
        }],
        structuredContent,
      },
    }),
  ].join("\n") + "\n";
};

const createFixture = async (output = mcpOutput()) => {
  const executableFixture = await makeExecutable();
  const base = fakePorts().ports;
  const calls = [];
  const ports = await createAttestedVisualScenarioPorts({
    basePorts: base,
    executable: executableFixture.executable,
    repositoryRoot,
    serial: SERIAL,
    deviceFingerprint: fingerprint,
    targetPackages: [NATIVE_PACKAGE],
  }, {
    executeMcp: async (request) => {
      calls.push(request);
      return {
        code: 0,
        stdout: output,
        stderr: "",
        timedOut: false,
      };
    },
    now: () => "2026-08-01T12:00:00.000Z",
  });
  return { calls, ports };
};

test("visual route 使用固定 aactl mcp serve argv 与无坐标 NDJSON stdin", async () => {
  const fixture = await createFixture();
  const route = await fixture.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  assert.equal(route.route, "visual");
  assert.equal(route.score, 1);

  const result = await fixture.ports.executor.execute({
    action,
    route: route.route,
    routeToken: route.token,
    observationId: observation.observationId,
    targetPackage: NATIVE_PACKAGE,
    deadlineAt: "2026-08-01T12:00:05.000Z",
  });
  assert.deepEqual(result, { committed: true });
  assert.equal(fixture.calls.length, 1);
  assert.deepEqual(fixture.calls[0].argv, ["mcp", "serve"]);
  assert.deepEqual(fixture.calls[0].options, {
    maxBuffer: 1048576,
    shell: false,
    timeoutMs: 5000,
    windowsHide: true,
  });

  const messages = fixture.calls[0].stdin.trimEnd().split("\n").map(JSON.parse);
  assert.deepEqual(messages.map(({ method }) => method), [
    "initialize",
    "notifications/initialized",
    "tools/call",
  ]);
  assert.deepEqual(messages[2].params, {
    name: "android_visual_action_execute",
    arguments: {
      device: SERIAL,
      expectedPackage: NATIVE_PACKAGE,
      target: {
        label: "Canvas fixture canvas-result ready",
      },
      action: {
        type: "tap",
      },
    },
  });
  const serialized = JSON.stringify(messages[2]);
  for (const forbidden of [
    "\"x\"",
    "\"y\"",
    "coordinate",
    "candidateId",
    "observationId",
    "png",
    "sha256",
  ]) {
    assert.equal(serialized.includes(forbidden), false, forbidden);
  }
});

test("旧 route token、observation 和 fingerprint drift 均不产生第二次动作", async () => {
  const fixture = await createFixture();
  const route = await fixture.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  const request = {
    action,
    route: route.route,
    routeToken: route.token,
    observationId: observation.observationId,
    targetPackage: NATIVE_PACKAGE,
    deadlineAt: "2026-08-01T12:00:05.000Z",
  };
  assert.deepEqual(await fixture.ports.executor.execute(request), {
    committed: true,
  });
  await assert.rejects(
    () => fixture.ports.executor.execute(request),
    (error) => error.code === "VISUAL_ROUTE_TOKEN_CONSUMED",
  );
  assert.equal(fixture.calls.length, 1);

  const stale = await createFixture();
  const staleRoute = await stale.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  await assert.rejects(
    () => stale.ports.executor.execute({
      ...request,
      routeToken: staleRoute.token,
      observationId: "visual-observation-stale",
    }),
    (error) => error.code === "VISUAL_ROUTE_TOKEN_INVALID",
  );
  assert.equal(stale.calls.length, 0);

  const drift = await createFixture(mcpOutput({
    returnedFingerprint: "c".repeat(64),
  }));
  const driftRoute = await drift.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  await assert.rejects(
    () => drift.ports.executor.execute({
      ...request,
      routeToken: driftRoute.token,
    }),
    (error) => error.code === "VISUAL_RESULT_IDENTITY_DRIFT",
  );
  assert.equal(drift.calls.length, 1);
});

test("明确拒绝映射零提交，unknown 或进程异常不重试", async () => {
  const rejected = await createFixture(mcpOutput({
    commitStatus: "not_committed",
    actionCommits: 0,
    succeeded: false,
    verified: false,
    errorCode: "ACTION_NOT_AUTHORIZED",
  }));
  const rejectedRoute = await rejected.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  assert.deepEqual(
    await rejected.ports.executor.execute({
      action,
      route: "visual",
      routeToken: rejectedRoute.token,
      observationId: observation.observationId,
      targetPackage: NATIVE_PACKAGE,
      deadlineAt: "2026-08-01T12:00:05.000Z",
    }),
    { committed: false },
  );

  const unknown = await createFixture(mcpOutput({
    commitStatus: "unknown",
    actionCommits: null,
    succeeded: false,
    verified: false,
    errorCode: "BRIDGE_RESPONSE_UNAVAILABLE",
  }));
  const unknownRoute = await unknown.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  await assert.rejects(
    () => unknown.ports.executor.execute({
      action,
      route: "visual",
      routeToken: unknownRoute.token,
      observationId: observation.observationId,
      targetPackage: NATIVE_PACKAGE,
      deadlineAt: "2026-08-01T12:00:05.000Z",
    }),
    (error) => error.code === "VISUAL_ACTION_COMMIT_UNKNOWN",
  );
  assert.equal(unknown.calls.length, 1);

  const executableFixture = await makeExecutable();
  let processCalls = 0;
  const processFailure = await createAttestedVisualScenarioPorts({
    basePorts: fakePorts().ports,
    executable: executableFixture.executable,
    repositoryRoot,
    serial: SERIAL,
    deviceFingerprint: fingerprint,
    targetPackages: [NATIVE_PACKAGE],
  }, {
    executeMcp: async () => {
      processCalls += 1;
      throw new Error("private process failure");
    },
    now: () => "2026-08-01T12:00:00.000Z",
  });
  const failureRoute = await processFailure.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  await assert.rejects(
    () => processFailure.executor.execute({
      action,
      route: "visual",
      routeToken: failureRoute.token,
      observationId: observation.observationId,
      targetPackage: NATIVE_PACKAGE,
      deadlineAt: "2026-08-01T12:00:05.000Z",
    }),
    (error) => error.code === "VISUAL_ACTION_COMMIT_UNKNOWN",
  );
  assert.equal(processCalls, 1);
});

test("semantic/input route 继续委托既有 aactl 端口且 lifecycle 使 visual token 失效", async () => {
  const fixture = await createFixture();
  const semantic = await fixture.ports.router.resolve({
    action: {
      type: "tap",
      target: { kind: "semantic-name", name: "Fixture click target" },
    },
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["semantic"],
    minimumScore: 0.9,
    dynamicRegions: [],
  });
  assert.equal(semantic.route, "semantic");

  const visual = await fixture.ports.router.resolve({
    action,
    observation,
    targetPackage: NATIVE_PACKAGE,
    allowedRoutes: ["visual"],
    minimumScore: 0.95,
    dynamicRegions: [],
  });
  await fixture.ports.lifecycle.stopScenario({
    runId: "019fbdf0-0000-7000-8000-000000000047",
    scenarioId: "production-canvas-fixture",
    iteration: 1,
    targetPackages: [NATIVE_PACKAGE],
  });
  await assert.rejects(
    () => fixture.ports.executor.execute({
      action,
      route: "visual",
      routeToken: visual.token,
      observationId: observation.observationId,
      targetPackage: NATIVE_PACKAGE,
      deadlineAt: "2026-08-01T12:00:05.000Z",
    }),
    (error) => error.code === "VISUAL_ROUTE_TOKEN_INVALID",
  );
  assert.equal(fixture.calls.length, 0);
});
