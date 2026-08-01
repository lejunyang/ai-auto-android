// 测试用途：验证探索报告拒绝未知字段、自由文本 secret、路径、原始内容与任意动作。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadMiniappReportSchema,
  validateMiniappReport,
} from "../src/report-validator.mjs";
import {
  makeReport,
} from "./helpers.mjs";

const validate = async (report) =>
  validateMiniappReport(await loadMiniappReportSchema(), report);

test("严格 Schema 拒绝各层 unknown keys 与自由文本 secrets", async () => {
  const cases = [
    ["rootSecret", "token=private"],
    ["samples.0.host.account", "user@example.invalid"],
    ["samples.0.page.title", "private page title"],
    ["samples.0.observations.hierarchy.rawXml", "<node text='secret'/>"],
    ["samples.0.observations.screenshot.ocrText", "secret"],
    ["samples.0.probes.0.note", "raw exception with secret"],
    ["samples.0.decision.message", "manual override"],
  ];

  for (const [target, value] of cases) {
    const report = makeReport();
    const parts = target.split(".");
    let cursor = report;
    for (const part of parts.slice(0, -1)) cursor = cursor[Number(part) || part];
    cursor[parts.at(-1)] = value;
    const errors = await validate(report);
    assert.equal(
      errors.some((error) => error.includes(parts.at(-1))),
      true,
      target,
    );
  }
});

test("报告拒绝文件路径、URL、原始 hierarchy 与 screenshot 内容", async () => {
  const fields = [
    ["path", "/private/tmp/hierarchy.xml"],
    ["filePath", "screenshots/page.png"],
    ["uri", "file:///tmp/page.png"],
    ["url", "https://example.invalid/miniapp"],
    ["raw", "base64-private-content"],
    ["content", "<hierarchy/>"],
  ];

  for (const [field, value] of fields) {
    const report = makeReport();
    report.samples[0].observations.hierarchy[field] = value;
    assert.equal(
      (await validate(report)).some((error) => error.includes(field)),
      true,
      field,
    );
  }
});

test("报告拒绝 shell、deep-link、tap 坐标和任意动作参数", async () => {
  const actions = [
    { type: "shell", command: "id" },
    { type: "deep-link" },
    { type: "tap", x: 100, y: 200 },
    { type: "input", text: "secret" },
  ];

  for (const action of actions) {
    const report = makeReport();
    report.samples[0].probes[0].action = action;
    assert.equal(
      (await validate(report)).some((error) => error.includes("action")),
      true,
      action.type,
    );
  }
});

test("摘要必须有 sha256、有界计数且 screenshot retainedBytes 恒为零", async () => {
  const cases = [
    (report) => {
      report.samples[0].observations.hierarchy.sha256 = "not-a-sha";
    },
    (report) => {
      report.samples[0].observations.hierarchy.byteCount = 2_000_000;
    },
    (report) => {
      report.samples[0].observations.hierarchy.nodeCount = 20_000;
    },
    (report) => {
      report.samples[0].observations.hierarchy.retainedBytes = 1;
    },
    (report) => {
      report.samples[0].observations.screenshot.byteCount = 20_000_000;
    },
    (report) => {
      report.samples[0].observations.screenshot.width = 10_000;
    },
    (report) => {
      report.samples[0].observations.screenshot.retainedBytes = 1;
    },
  ];

  for (const mutate of cases) {
    const report = makeReport();
    mutate(report);
    assert.notDeepEqual(await validate(report), []);
  }
});

test("hierarchy 子计数不得超过总节点数", async () => {
  const report = makeReport();
  report.samples[0].observations.hierarchy.actionableNodeCount =
    report.samples[0].observations.hierarchy.nodeCount + 1;

  assert.equal(
    (await validate(report)).some((error) =>
      error.includes("subset count exceeds nodes")),
    true,
  );
});

test("JSON object 键顺序变化不影响分类与统计验证", async () => {
  const report = makeReport();
  report.samples[0].decision = Object.fromEntries(
    Object.entries(report.samples[0].decision).reverse(),
  );
  report.statistics.classificationCounts = Object.fromEntries(
    Object.entries(report.statistics.classificationCounts).reverse(),
  );

  assert.deepEqual(await validate(report), []);
});

test("畸形输入仅返回稳定错误且不回显原始内容", async () => {
  const malformed = [
    null,
    [],
    {},
    { samples: [null], statistics: null },
    { samples: [{ probes: [null] }], statistics: {} },
  ];

  for (const report of malformed) {
    let errors;
    await assert.doesNotReject(async () => {
      errors = await validate(report);
    });
    assert.ok(errors.length > 0);
    assert.equal(errors.every((error) => typeof error === "string"), true);
    assert.equal(errors.some((error) => error.includes("private")), false);
  }
});
