// 测试用途：验证小黑盒契约拒绝副作用动作、任意输入、坐标、secret、路径与原始内容。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadXiaoheiheSchemas,
  validateXiaoheiheReport,
  validateXiaoheiheScenario,
} from "../src/report-validator.mjs";
import {
  makeReport,
  makeScenario,
} from "./helpers.mjs";

const schemas = await loadXiaoheiheSchemas();

test("长按仅允许无副作用内容，关注收藏评论发帖下载分享均不可表示", () => {
  const forbidden = ["follow", "favorite", "comment", "post", "download", "share"];
  for (const action of forbidden) {
    const scenario = makeScenario();
    const longPress = scenario.steps.find(
      (step) => step.action === "long-press-safe-content",
    );
    longPress.action = action;
    assert.notDeepEqual(
      validateXiaoheiheScenario(schemas.scenario, scenario),
      [],
      action,
    );
  }
});
test("严格 Schema 拒绝任意 package、APK、action 与坐标", () => {
  const cases = [
    (report) => {
      report.manifestBinding.packageName = "com.example.other";
    },
    (report) => {
      report.manifestBinding.apkSha256 = "a".repeat(64);
    },
    (report) => {
      report.runs[0].apkPath = "/private/tmp/app.apk";
    },
    (report) => {
      report.runs[0].steps[0].action = "tap";
    },
    (report) => {
      report.runs[0].steps[0].x = 100;
      report.runs[0].steps[0].y = 200;
    },
  ];
  for (const mutate of cases) {
    const report = makeReport();
    mutate(report);
    assert.notDeepEqual(validateXiaoheiheReport(schemas.runReport, report), []);
  }
});

test("报告拒绝 selector、secret、路径、URL、raw、DOM 与 JavaScript 内容", () => {
  const fields = [
    ["selector", "private-selector"],
    ["secret", "token=private"],
    ["path", "/private/tmp/hierarchy.xml"],
    ["url", "https://example.invalid/private"],
    ["rawContent", "<hierarchy/>"],
    ["dom", "<main>private</main>"],
    ["javascript", "document.body.innerText"],
  ];
  for (const [field, value] of fields) {
    const report = makeReport();
    report.runs[0].observations[0][field] = value;
    const errors = validateXiaoheiheReport(schemas.runReport, report);
    assert.equal(errors.some((error) => error.includes(field)), true, field);
    assert.equal(errors.some((error) => error.includes(value)), false, field);
  }
});

test("场景固定关闭 DOM 与 JavaScript 注入且不接受任意执行参数", () => {
  for (const mutate of [
    (scenario) => {
      scenario.execution.domInjection = true;
    },
    (scenario) => {
      scenario.execution.javascriptInjection = true;
    },
    (scenario) => {
      scenario.steps[0].inputText = "private search";
    },
    (scenario) => {
      scenario.steps[0].command = "shell";
    },
  ]) {
    const scenario = makeScenario();
    mutate(scenario);
    assert.notDeepEqual(
      validateXiaoheiheScenario(schemas.scenario, scenario),
      [],
    );
  }
});

test("畸形输入失败关闭且错误不回显原始内容", () => {
  for (const value of [
    null,
    [],
    {},
    { runs: [null], secret: "private-value" },
  ]) {
    let errors;
    assert.doesNotThrow(() => {
      errors = validateXiaoheiheReport(schemas.runReport, value);
    });
    assert.ok(errors.length > 0);
    assert.equal(errors.some((error) => error.includes("private-value")), false);
  }
});
