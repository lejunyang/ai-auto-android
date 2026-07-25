// 测试用途：验证 N47 场景目标与 native/Web/Canvas fixture 资源一致且不夹带第三方数据。
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  loadFixture,
  NATIVE_PACKAGE,
  WEB_PACKAGE,
} from "./helpers.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..");

const semanticNames = (scenario) =>
  scenario.steps
    .map((step) => step.action.target?.name)
    .filter(Boolean);

test("native 场景语义目标来自固定 strings 资源", async () => {
  const scenario = await loadFixture("native-fixture.scenario.json");
  const strings = await readFile(
    path.join(
      repositoryRoot,
      "android/device-fixture/src/main/res/values/strings.xml",
    ),
    "utf8",
  );

  assert.deepEqual(scenario.targetPackages, [NATIVE_PACKAGE]);
  for (const name of semanticNames(scenario)) {
    assert.equal(strings.includes(`>${name}<`), true, name);
  }
});

test("Web 和 Canvas 场景语义目标来自固定离线资源", async () => {
  const scenario = await loadFixture("web-fixture.scenario.json");
  const assets = await Promise.all(
    ["full.html", "partial.html", "canvas.html"].map((file) =>
      readFile(
        path.join(
          repositoryRoot,
          "android/web-fixture/src/main/assets/web",
          file,
        ),
        "utf8",
      )),
  );
  const resources = assets.join("\n");

  assert.deepEqual(scenario.targetPackages, [WEB_PACKAGE, NATIVE_PACKAGE]);
  for (const name of semanticNames(scenario)) {
    assert.equal(resources.includes(name), true, name);
  }
});

test("fixture 场景不包含第三方包、URL、账号、凭据或命令字段", async () => {
  const scenarios = [
    await loadFixture("native-fixture.scenario.json"),
    await loadFixture("web-fixture.scenario.json"),
  ];
  const encoded = JSON.stringify(scenarios);

  assert.equal(
    scenarios.flatMap((scenario) => scenario.targetPackages)
      .every((value) => value === NATIVE_PACKAGE || value === WEB_PACKAGE),
    true,
  );
  for (const forbidden of [
    "http://",
    "https://",
    "\"account\"",
    "\"password\"",
    "\"token\"",
    "\"command\"",
    "\"shell\"",
  ]) {
    assert.equal(encoded.includes(forbidden), false, forbidden);
  }
});
