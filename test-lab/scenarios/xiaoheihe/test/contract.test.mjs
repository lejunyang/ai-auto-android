// 测试用途：验证小黑盒固定身份、总计十轮、三 API 安全阻塞、route 与后置观察契约。
import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyXiaoheiheRun,
  loadXiaoheiheSchemas,
  recomputeXiaoheiheStatistics,
  validateXiaoheiheReport,
  validateXiaoheiheScenario,
} from "../src/report-validator.mjs";
import {
  makeBlockedRun,
  makeReport,
  makeScenario,
} from "./helpers.mjs";

const schemas = await loadXiaoheiheSchemas();
const validateReport = (report) =>
  validateXiaoheiheReport(schemas.runReport, report);

test("固定场景绑定 manifest 身份、支持三 API 且报告总计十轮", () => {
  const scenario = makeScenario();
  const report = makeReport();

  assert.deepEqual(validateXiaoheiheScenario(schemas.scenario, scenario), []);
  assert.deepEqual(validateReport(report), []);
  assert.equal(report.runs.length, 10);
  assert.deepEqual(
    report.runs.map((run) => run.binding.iteration),
    [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
  );
  assert.deepEqual(
    [...new Set(report.runs.map((run) => run.binding.apiLevel))].sort(),
    [30, 33, 34],
  );
});
test("API 30、33、34 的 consent/permission 阻塞均零提交且不兼容", () => {
  for (const apiLevel of [30, 33, 34]) {
    const report = makeReport({
      runFactory: makeBlockedRun,
      runApiLevels: Array(10).fill(apiLevel),
    });
    assert.deepEqual(validateReport(report), []);
    for (const run of report.runs) {
      assert.deepEqual(classifyXiaoheiheRun(run), run.decision);
      assert.equal(run.decision.compatible, false);
      assert.equal(run.decision.actionCommits, 0);
      assert.deepEqual(run.decision.reasonCodes, [
        "CONSENT_BLOCKED",
        "PERMISSION_BLOCKED",
        "STEP_SEQUENCE_INCOMPLETE",
      ]);
    }
  }

  const report = makeReport({
    runFactory: makeBlockedRun,
    runApiLevels: Array(10).fill(30),
  });
  const committed = structuredClone(report);
  const sourceStep = makeReport().runs[0].steps[0];
  committed.runs[0].steps = [sourceStep];
  committed.runs[0].decision = classifyXiaoheiheRun(committed.runs[0]);
  committed.statistics = recomputeXiaoheiheStatistics(committed.runs);
  assert.equal(
    validateReport(committed).some((error) =>
      error.includes("blocked run committed action")),
    true,
  );
});

test("native 与 WebView route 独立绑定 surface，系统导航不混入内容 route", () => {
  const report = makeReport();
  const decision = report.runs[0].decision;
  assert.deepEqual(decision.routeCounts, {
    nativeAccessibilitySteps: 3,
    webviewAccessibilitySteps: 3,
    systemNavigationSteps: 5,
  });

  report.runs[0].observations[0].surface = "webview";
  report.runs[0].decision = classifyXiaoheiheRun(report.runs[0]);
  report.statistics = recomputeXiaoheiheStatistics(report.runs);
  assert.equal(
    report.runs[0].decision.reasonCodes.includes("ROUTE_SURFACE_MISMATCH"),
    true,
  );
  assert.deepEqual(validateReport(report), []);

  const hybrid = makeReport();
  hybrid.runs[0].steps[0].route = "hybrid";
  assert.equal(
    validateReport(hybrid).some((error) => error.includes("route")),
    true,
  );

  const wrongPolicy = makeReport();
  wrongPolicy.runs[0].steps[0].route = "system-navigation";
  wrongPolicy.runs[0].decision = classifyXiaoheiheRun(wrongPolicy.runs[0]);
  wrongPolicy.statistics = recomputeXiaoheiheStatistics(wrongPolicy.runs);
  assert.equal(
    wrongPolicy.runs[0].decision.reasonCodes.includes("ROUTE_POLICY_MISMATCH"),
    true,
  );
  assert.deepEqual(validateReport(wrongPolicy), []);
});

test("公开搜索、详情、Back、Home、Recents 与跨 App 返回必须 post observe", () => {
  const report = makeReport();
  const requiredActions = new Set([
    "public-search-input",
    "public-search-submit",
    "public-detail-open",
    "back",
    "home",
    "recents",
    "cross-app-return",
  ]);
  assert.equal(
    report.runs[0].steps
      .filter((step) => requiredActions.has(step.action))
      .every((step) => step.postObservationId !== null),
    true,
  );

  const detail = report.runs[0].steps.find(
    (step) => step.action === "public-detail-open",
  );
  detail.postObservationId = null;
  report.runs[0].decision = classifyXiaoheiheRun(report.runs[0]);
  report.statistics = recomputeXiaoheiheStatistics(report.runs);
  assert.equal(
    report.runs[0].decision.reasonCodes.includes("POST_OBSERVATION_MISSING"),
    true,
  );
  assert.equal(
    validateReport(report).some((error) =>
      error.includes("passed step contract mismatch")),
    true,
  );
});

test("缺失、重复、额外 iteration 或未知 API 均不能冒充固定十轮", () => {
  for (const mutate of [
    (report) => report.runs.pop(),
    (report) => {
      report.runs[1].binding.iteration = 1;
    },
    (report) => {
      report.runs.push(structuredClone(report.runs.at(-1)));
      report.runs.at(-1).runId = "fixture-extra-iteration-11";
      report.runs.at(-1).binding.iteration = 11;
    },
    (report) => {
      report.runs[0].binding.apiLevel = 31;
    },
  ]) {
    const report = makeReport();
    mutate(report);
    assert.notDeepEqual(validateReport(report), []);
  }
});

test("跨轮 observation ID 复用会被拒绝", () => {
  const report = makeReport();
  report.runs[1].observations[0].observationId =
    report.runs[0].observations[0].observationId;
  report.runs[1].steps[0].preObservationId =
    report.runs[0].observations[0].observationId;
  report.runs[1].decision = classifyXiaoheiheRun(report.runs[1]);
  report.statistics = recomputeXiaoheiheStatistics(report.runs);

  assert.equal(
    validateReport(report).some((error) =>
      error.includes("reused across runs")),
    true,
  );
});
