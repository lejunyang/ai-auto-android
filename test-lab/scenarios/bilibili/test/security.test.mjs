// 测试用途：验证 B站报告拒绝任意 package/APK/action、坐标、secret、路径与原始产物。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadBilibiliSchemas,
  validateBilibiliReport,
} from "../src/report-validator.mjs";
import {
  makeReport,
} from "./helpers.mjs";

const schemas = await loadBilibiliSchemas();
const validate = (report) => validateBilibiliReport(schemas.report, report);

test("严格 Schema 拒绝各层 unknown keys 和自由文本 secret", () => {
  const cases = [
    ["rootSecret", "token=private"],
    ["identity.apkPath", "/private/tmp/app.apk"],
    ["profile.serial", "emulator-5554"],
    ["rounds.0.account", "user@example.invalid"],
    ["rounds.0.steps.0.note", "raw exception"],
    ["statistics.message", "manual override"],
  ];

  for (const [target, value] of cases) {
    const report = makeReport();
    const parts = target.split(".");
    let cursor = report;
    for (const part of parts.slice(0, -1)) cursor = cursor[Number(part) || part];
    cursor[parts.at(-1)] = value;
    assert.equal(
      validate(report).some((error) => error.includes(parts.at(-1))),
      true,
      target,
    );
  }
});

test("报告拒绝 APK、路径、URL、raw hierarchy 与 screenshot", () => {
  const fields = [
    ["apk", "base64-apk"],
    ["path", "/private/tmp/report.json"],
    ["filePath", "artifacts/page.png"],
    ["url", "https://example.invalid/app.apk"],
    ["rawHierarchy", "<hierarchy/>"],
    ["xml", "<node/>"],
    ["screenshot", "base64-image"],
    ["imageBytes", "private"],
  ];

  for (const [field, value] of fields) {
    const report = makeReport();
    report.rounds[0][field] = value;
    assert.equal(
      validate(report).some((error) => error.includes(field)),
      true,
      field,
    );
  }
});

test("报告拒绝坐标、selector、任意动作和输入内容", () => {
  const cases = [
    ["x", 100],
    ["y", 200],
    ["bounds", "[0,0][100,100]"],
    ["selector", "text=private"],
    ["action", { type: "shell", command: "id" }],
    ["params", { target: "private" }],
    ["query", "public but not retained"],
    ["value", "secret"],
  ];

  for (const [field, value] of cases) {
    const report = makeReport();
    report.rounds[0].steps[0][field] = value;
    assert.equal(
      validate(report).some((error) => error.includes(field)),
      true,
      field,
    );
  }
});

test("畸形输入只返回稳定错误且不回显原始内容", () => {
  const malformed = [
    null,
    [],
    {},
    { rounds: [null], statistics: null },
    { rounds: [{ steps: [null] }], statistics: {} },
  ];

  for (const report of malformed) {
    let errors;
    assert.doesNotThrow(() => {
      errors = validate(report);
    });
    assert.ok(errors.length > 0);
    assert.equal(errors.every((error) => typeof error === "string"), true);
    assert.equal(errors.some((error) => error.includes("private")), false);
  }
});
