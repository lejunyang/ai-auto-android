// 测试用途：构造固定 300 轮矩阵端口与结果，验证编排语义而不启动真实 Emulator。
export const MATRIX_ID = "018f47a2-4bc8-7f31-8b9a-1234567890ab";

export const PROFILES = Object.freeze([
  Object.freeze({ profileId: "api-30", apiLevel: 30 }),
  Object.freeze({ profileId: "api-33", apiLevel: 33 }),
  Object.freeze({ profileId: "api-34", apiLevel: 34 }),
]);

export const SCENARIOS = Object.freeze([
  Object.freeze({
    scenarioId: "native-fixture",
    requiredCapabilities: Object.freeze([
      "fixture.native",
      "semantic.action",
      "system.navigation",
    ]),
  }),
  Object.freeze({
    scenarioId: "webview-fixture",
    requiredCapabilities: Object.freeze([
      "fixture.webview",
      "semantic.action",
    ]),
  }),
  Object.freeze({
    scenarioId: "canvas-fixture",
    requiredCapabilities: Object.freeze([
      "fixture.canvas",
      "visual.action",
    ]),
  }),
  Object.freeze({
    scenarioId: "recording-editor",
    requiredCapabilities: Object.freeze([
      "fixture.editor",
      "compose.test",
    ]),
  }),
  Object.freeze({
    scenarioId: "recording-replay",
    requiredCapabilities: Object.freeze([
      "recording.replay",
      "semantic.action",
    ]),
  }),
]);

export const ALL_CAPABILITIES = Object.freeze([
  ...new Set(SCENARIOS.flatMap((scenario) => scenario.requiredCapabilities)),
].sort());

export const cleanResidue = () => ({
  appData: 0,
  bridgeSessions: 0,
  testServices: 0,
  screenshots: 0,
  logs: 0,
  ownedProcesses: 0,
  runtimeFiles: 0,
  leases: 0,
});

export const fakePorts = ({
  missingCapability = null,
  extraProbeCapability = null,
  scenarioFailure = null,
  startFailureAt = null,
  unsafeStartAt = null,
  stopFailureAt = null,
  residueFailureAt = null,
  unknownCommitAt = null,
  fingerprintDriftAt = null,
  extraStartCapabilityAt = null,
  duplicateStartCapabilityAt = null,
  scenarioPortFailure = null,
  invalidScenarioReport = null,
  cancelledScenario = null,
  deviceFailure = null,
} = {}) => {
  const calls = [];
  let startCount = 0;
  let runCount = 0;
  let stopCount = 0;
  let residueCount = 0;
  return {
    calls,
    ports: {
      capabilities: {
        probe: async (profile) => {
          calls.push(`probe:${profile.profileId}`);
          return {
            profileId: profile.profileId,
            apiLevel: profile.apiLevel,
            capabilities: [
              ...ALL_CAPABILITIES.filter(
                (capability) => capability !== missingCapability,
              ),
              ...(extraProbeCapability === null ? [] : [extraProbeCapability]),
            ],
          };
        },
      },
      lifecycle: {
        startClean: async ({ profile, scenario, iteration }) => {
          startCount += 1;
          calls.push(`start:${profile.profileId}:${scenario.scenarioId}:${iteration}`);
          if (startCount === startFailureAt) throw new Error("private start error");
          if (startCount === unsafeStartAt) return null;
          return {
            profileId: profile.profileId,
            apiLevel: profile.apiLevel,
            serial: `emulator-${5552 + (startCount % 16) * 2}`,
            deviceFingerprint: startCount === fingerprintDriftAt
              ? "f".repeat(64)
              : profile.apiLevel.toString(16).padStart(64, "0"),
            snapshot: "clean",
            capabilities: startCount === extraStartCapabilityAt
              ? [...ALL_CAPABILITIES, "unexpected.capability"]
              : startCount === duplicateStartCapabilityAt
                ? ALL_CAPABILITIES.map((capability) =>
                    capability === "visual.action" ? "compose.test" : capability)
                : ALL_CAPABILITIES,
          };
        },
        stop: async ({ profileId, serial }) => {
          stopCount += 1;
          calls.push(`stop:${profileId}:${serial}`);
          if (stopCount === stopFailureAt) throw new Error("private stop error");
          return { stopped: true };
        },
      },
      scenario: {
        run: async ({ profile, scenario, iteration }) => {
          runCount += 1;
          calls.push(`run:${profile.profileId}:${scenario.scenarioId}:${iteration}`);
          const identity = `${profile.profileId}:${scenario.scenarioId}:${iteration}`;
          if (scenarioPortFailure === identity) {
            throw new Error("private scenario port failure");
          }
          const productFailed = Array.isArray(scenarioFailure)
            ? scenarioFailure.includes(identity)
            : scenarioFailure === identity;
          const deviceFailed = deviceFailure === identity;
          const failed = productFailed || deviceFailed;
          const unknownCommit = unknownCommitAt === identity;
          const cancelled = cancelledScenario === identity;
          const result = {
            schemaVersion: "1.0",
            runId: `018f47a2-4bc8-7f31-8b9a-${String(runCount).padStart(12, "0")}`,
            scenarioId: scenario.scenarioId,
            iteration,
            startedAt: "2026-07-25T12:00:00.000Z",
            finishedAt: "2026-07-25T12:00:00.100Z",
            status: cancelled
              ? "cancelled"
              : failed || unknownCommit
                ? "failed"
                : "passed",
            errorCode: cancelled
              ? "SCENARIO_CANCELLED"
              : unknownCommit
              ? "ACTION_COMMIT_UNKNOWN"
              : deviceFailed
                ? "PAGE_EMULATOR_DETECTED"
              : failed
                ? "POSTCONDITION_FAILED"
                : null,
            durationMs: 100 + iteration,
            actionCommits: unknownCommit
              ? null
              : failed || cancelled
                ? 0
                : 1,
            steps: [
              {
                sequence: 1,
                stepId: "matrix-step",
                actionType: "tap",
                targetPackage: "dev.aiauto.fixture",
                status: cancelled
                  ? "cancelled"
                  : failed || unknownCommit
                    ? "failed"
                    : "passed",
                errorCode: cancelled
                  ? "SCENARIO_CANCELLED"
                  : unknownCommit
                  ? "ACTION_COMMIT_UNKNOWN"
                  : deviceFailed
                    ? "PAGE_EMULATOR_DETECTED"
                  : failed
                    ? "POSTCONDITION_FAILED"
                    : null,
                pageClass: deviceFailed ? "emulator-detected" : "normal",
                route: cancelled || deviceFailed
                  ? null
                  : scenario.scenarioId === "canvas-fixture"
                    ? "visual"
                    : "semantic",
                score: cancelled || deviceFailed ? null : 1,
                attempts: cancelled || deviceFailed ? 0 : 1,
                preObservationId: "observation-pre",
                postObservationId: failed || unknownCommit || cancelled
                  ? null
                  : "observation-post",
                actionCommits: unknownCommit ? null : failed || cancelled ? 0 : 1,
                durationMs: 100 + iteration,
              },
            ],
            cleanup: {
              attempted: 4,
              completed: 4,
              errorCode: null,
            },
          };
          if (invalidScenarioReport === identity) {
            result.privateMessage = "must not pass";
          }
          return result;
        },
      },
      residue: {
        inspect: async ({ profileId, serial }) => {
          residueCount += 1;
          calls.push(`residue:${profileId}:${serial}`);
          const residue = cleanResidue();
          if (residueCount === residueFailureAt) residue.leases = 1;
          return residue;
        },
      },
    },
  };
};

export const fixedClock = () => {
  let now = Date.parse("2026-07-25T12:00:00.000Z");
  return {
    now: () => new Date(now).toISOString(),
    tick: () => {
      now += 1;
    },
  };
};
