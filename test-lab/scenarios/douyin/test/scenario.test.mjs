// 测试用途：验证 N49 场景只绑定固定抖音身份、固定 10 轮和无副作用能力目录。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadDouyinSchemas,
  validateDouyinScenario,
} from "../src/contract-validator.mjs";
import {
  DOUYIN_IDENTITY,
  makeScenario,
  PROTECTED_NODE_TYPES,
} from "./helpers.mjs";

const validate = async (scenario) =>
  validateDouyinScenario((await loadDouyinSchemas()).scenario, scenario);

test("固定场景绑定 manifest 身份、10 轮和七类零提交节点", async () => {
  const scenario = makeScenario();

  assert.deepEqual(await validate(scenario), []);
  assert.deepEqual(scenario.manifest, DOUYIN_IDENTITY);
  assert.equal(scenario.requiredRealRuns, 10);
  assert.deepEqual(
    scenario.protectedNodes.map(({ type }) => type),
    PROTECTED_NODE_TYPES,
  );
  assert.equal(
    scenario.protectedNodes.every(({ maxActionCommits }) =>
      maxActionCommits === 0),
    true,
  );
});

test("场景拒绝任意 package、APK、action、坐标、secret 和路径", async () => {
  const cases = [
    (scenario) => {
      scenario.manifest.packageName = "com.example.other";
    },
    (scenario) => {
      scenario.manifest.apkSha256 = "0".repeat(64);
    },
    (scenario) => {
      scenario.apkPath = "/private/tmp/douyin.apk";
    },
    (scenario) => {
      scenario.action = { type: "tap", x: 100, y: 200 };
    },
    (scenario) => {
      scenario.secret = "private-token";
    },
    (scenario) => {
      scenario.requiredRealRuns = 9;
    },
  ];

  for (const mutate of cases) {
    const scenario = makeScenario();
    mutate(scenario);
    assert.notDeepEqual(await validate(scenario), []);
  }
});

test("长按固定为互动菜单风险 unsupported，能力目录不携带动作参数", async () => {
  const scenario = makeScenario();
  const longPress = scenario.capabilities.find(({ type }) =>
    type === "long-press");

  assert.deepEqual(longPress, {
    type: "long-press",
    policy: "unsupported-interaction-menu-risk",
  });

  const parameterized = structuredClone(scenario);
  parameterized.capabilities.at(-1).target = {
    selector: "private",
    x: 0.5,
    y: 0.5,
  };
  assert.notDeepEqual(await validate(parameterized), []);
});
