// 测试用途：验证高风险页面零提交、公开结果点击门、长按证明和 unknown 不重试。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadBilibiliSchemas,
  recomputeBilibiliStatistics,
  validateBilibiliReport,
} from "../src/report-validator.mjs";
import {
  blockedStep,
  makeReport,
} from "./helpers.mjs";

const schemas = await loadBilibiliSchemas();
const validate = (report) => validateBilibiliReport(schemas.report, report);

test("consent/privacy/permission/login/update/ad/unknown 均零尝试零提交", () => {
  const classes = [
    "consent",
    "privacy",
    "permission",
    "login",
    "update",
    "advertisement",
    "unknown",
  ];

  for (const classification of classes) {
    const report = makeReport();
    report.rounds[0].status = "blocked";
    report.rounds[0].classification = classification;
    report.rounds[0].blockerCode =
      `BILIBILI_${classification.toUpperCase()}_BLOCKED`;
    report.rounds[0].steps = [
      report.rounds[0].steps[0],
      blockedStep({
        sourceStep: report.rounds[0].steps[1],
        classification,
      }),
    ];
    report.statistics = recomputeBilibiliStatistics(report);
    assert.equal(
      validate(report).some((error) => error.includes("zero commit")),
      false,
      classification,
    );

    const committed = structuredClone(report);
    committed.rounds[0].steps[1].attempts = 1;
    committed.rounds[0].steps[1].actionCommits = 1;
    committed.rounds[0].steps[1].postObserved = true;
    assert.equal(
      validate(committed).some((error) => error.includes("zero commit")),
      true,
      classification,
    );
  }
});

test("公开搜索 input 不进入报告，只有固定公开结果 click 允许内容点击", () => {
  const report = makeReport();
  const encoded = JSON.stringify(report);
  for (const forbidden of ["query", "inputValue", "searchText", "keyword"]) {
    assert.equal(encoded.includes(`"${forbidden}"`), false, forbidden);
  }

  const input = structuredClone(report);
  input.rounds[0].steps[2].inputValue = "private query";
  assert.equal(
    validate(input).some((error) => error.includes("inputValue")),
    true,
  );

  const arbitraryClick = structuredClone(report);
  arbitraryClick.rounds[0].steps[3].actionId = "click-content";
  assert.notDeepEqual(validate(arbitraryClick), []);
  assert.equal(
    report.rounds[0].steps[3].actionId,
    "click-public-result",
  );
});

test("长按只有四类副作用均证明不存在时才允许，否则 unsupported 零提交", () => {
  const fields = ["noLike", "noFavorite", "noDownload", "noShare"];
  for (const field of fields) {
    const report = makeReport();
    const step = report.rounds[0].steps[6];
    step.sideEffectProof[field] = false;
    step.status = "unsupported";
    step.classification = "long-press-side-effect-unproven";
    step.route = null;
    step.attempts = 0;
    step.actionCommits = 0;
    step.postObserved = false;
    step.postForegroundPackage = null;
    report.rounds[0].status = "unsupported";
    report.rounds[0].classification = "long-press-side-effect-unproven";
    report.rounds[0].blockerCode = "BILIBILI_LONG_PRESS_UNSUPPORTED";
    report.rounds[0].steps = report.rounds[0].steps.slice(0, 7);
    report.statistics = recomputeBilibiliStatistics(report);

    assert.equal(
      validate(report).some((error) => error.includes("long press proof")),
      false,
      field,
    );

    const unsafe = structuredClone(report);
    unsafe.rounds[0].steps[6].attempts = 1;
    unsafe.rounds[0].steps[6].actionCommits = 1;
    unsafe.rounds[0].steps[6].postObserved = true;
    unsafe.rounds[0].steps[6].postForegroundPackage = "tv.danmaku.bili";
    assert.equal(
      validate(unsafe).some((error) => error.includes("long press proof")),
      true,
      field,
    );
  }
});

test("动作提交后必须观察，unknown commit 保留 null 且不重试后续步骤", () => {
  const missingPost = makeReport();
  missingPost.rounds[0].steps[4].postObserved = false;
  assert.equal(
    validate(missingPost).some((error) => error.includes("post observation")),
    true,
  );

  const unknown = makeReport();
  const step = unknown.rounds[0].steps[4];
  step.status = "unknown-commit";
  step.classification = "unknown";
  step.route = "semantic";
  step.attempts = 1;
  step.actionCommits = null;
  step.postObserved = true;
  unknown.rounds[0].steps = unknown.rounds[0].steps.slice(0, 5);
  unknown.rounds[0].status = "failed";
  unknown.rounds[0].classification = "unknown-commit";
  unknown.rounds[0].blockerCode = "ACTION_COMMIT_UNKNOWN";
  unknown.statistics = recomputeBilibiliStatistics(unknown);
  assert.equal(
    validate(unknown).some((error) => error.includes("unknown commit")),
    false,
  );

  const retried = structuredClone(unknown);
  retried.rounds[0].steps[4].attempts = 2;
  assert.equal(
    validate(retried).some((error) => error.includes("unknown commit")),
    true,
  );

  const continued = structuredClone(unknown);
  continued.rounds[0].steps.push(makeReport().rounds[0].steps[5]);
  assert.equal(
    validate(continued).some((error) => error.includes("unknown commit")),
    true,
  );

  const packageUnverified = structuredClone(unknown);
  packageUnverified.rounds[0].steps[4].postForegroundPackage = null;
  assert.equal(
    validate(packageUnverified).some((error) => error.includes("unknown commit")),
    true,
  );
});

test("Back/Home/Recents 与跨 fixture 返回逐步复核固定前台包", () => {
  const indexes = [7, 8, 9, 10, 11, 12, 13, 14];
  for (const index of indexes) {
    const report = makeReport();
    report.rounds[0].steps[index].postForegroundPackage =
      "dev.aiauto.webfixture";
    assert.equal(
      validate(report).some((error) => error.includes("foreground package")),
      true,
      report.rounds[0].steps[index].stepId,
    );
  }
});

test("round status、classification 与 blocker 只接受固定安全组合", () => {
  const cases = [
    ["blocked", "compatible", null],
    ["passed", "consent", null],
    ["unsupported", "compatible", "BILIBILI_LONG_PRESS_UNSUPPORTED"],
    ["failed", "unknown-commit", "BILIBILI_UNKNOWN_BLOCKED"],
  ];

  for (const [status, classification, blockerCode] of cases) {
    const report = makeReport();
    report.rounds[0].status = status;
    report.rounds[0].classification = classification;
    report.rounds[0].blockerCode = blockerCode;
    assert.equal(
      validate(report).some((error) =>
        error.includes("status, classification and blocker")),
      true,
      `${status}/${classification}`,
    );
  }
});
