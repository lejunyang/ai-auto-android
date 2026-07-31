// 测试用途：验证被动场景恰好执行三条固定 aactl 命令，并只输出无文本 hierarchy 摘要。
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  createPassiveSmokeScenario,
  summarizeHierarchy,
} from "../src/passive-smoke.mjs";

const serial = "emulator-5554";
const targetPackage = "com.example.fixture";
const xml =
  `<?xml version="1.0"?><hierarchy rotation="0">` +
  `<node package="${targetPackage}" text="请阅读隐私协议并同意" ` +
  `content-desc="" bounds="[0,0][10,10]"/>` +
  `<node package="com.android.permissioncontroller" text="允许" ` +
  `content-desc="permission" bounds="[0,10][10,20]"/>` +
  `</hierarchy>`;
const hierarchy = Object.freeze({
  device: serial,
  format: "uiautomator-xml",
  xml,
  sizeBytes: Buffer.byteLength(xml, "utf8"),
  sha256: createHash("sha256").update(xml, "utf8").digest("hex"),
});

const envelope = (data) => ({
  code: 0,
  stdout: `${JSON.stringify({
    schemaVersion: "1.0",
    requestId: "00000000-0000-4000-8000-000000000000",
    ok: true,
    data,
    error: null,
    meta: { durationMs: 1 },
  })}\n`,
  stderr: "",
});

test("被动场景仅执行 device info、package launch 和一次 hierarchy", async () => {
  const calls = [];
  const command = async (executable, args, options) => {
    calls.push({ executable, args: [...args], options });
    if (args[0] === "device") {
      return envelope({
        serial,
        state: "device",
        transport: "emulator",
        apiLevel: 34,
        capabilities: [],
      });
    }
    if (args[0] === "action") {
      return envelope({ device: serial, action: "app.launch" });
    }
    if (args[0] === "observe") return envelope(hierarchy);
    throw new Error("unexpected command");
  };
  const scenario = createPassiveSmokeScenario({
    command,
    aactlPath: "/external/bin/aactl",
    serial,
    apiLevel: 34,
    targetPackage,
    settle: async () => calls.push({ settle: true }),
  });
  const summary = await scenario({
    serial,
    descriptor: { package: targetPackage },
    installed: { package: targetPackage },
  });

  assert.deepEqual(
    calls.filter((call) => call.args).map((call) => call.args),
    [
      ["device", "info", "--device", serial, "--json"],
      [
        "action",
        "launch",
        "--device",
        serial,
        "--package",
        targetPackage,
        "--json",
      ],
      ["observe", "hierarchy", "--device", serial, "--json"],
    ],
  );
  assert.equal(calls.filter((call) => call.settle).length, 1);
  assert.deepEqual(summary, {
    format: "uiautomator-xml",
    sizeBytes: hierarchy.sizeBytes,
    sha256: hierarchy.sha256,
    nodeCount: 2,
    packages: [targetPackage, "com.android.permissioncontroller"].sort(),
    targetPackageVisible: true,
    signals: {
      consentPrompt: true,
      loginPrompt: false,
      permissionPrompt: true,
      updatePrompt: false,
      advertisement: false,
      networkFailure: false,
      emulatorNotice: false,
    },
  });
  assert.equal(JSON.stringify(summary).includes("隐私协议"), false);
  assert.equal(JSON.stringify(summary).includes("permission"), true);
  await assert.rejects(
    () => scenario({
      serial,
      descriptor: { package: targetPackage },
      installed: { package: targetPackage },
    }),
    (error) => error.code === "PASSIVE_CONTEXT_DRIFT",
  );
});

test("hierarchy 字节、摘要或字段歧义失败关闭", async () => {
  assert.throws(
    () => summarizeHierarchy({
      ...hierarchy,
      targetPackage,
      sizeBytes: hierarchy.sizeBytes + 1,
    }),
    (error) => error.code === "PASSIVE_HIERARCHY_INVALID",
  );
  const command = async (_executable, args) => {
    if (args[0] === "device") {
      return envelope({
        serial,
        state: "device",
        transport: "emulator",
        apiLevel: 34,
        capabilities: [],
      });
    }
    if (args[0] === "action") {
      return envelope({ device: serial, action: "app.launch", extra: true });
    }
    throw new Error("observe must not run after ambiguous launch");
  };
  const scenario = createPassiveSmokeScenario({
    command,
    aactlPath: "/external/bin/aactl",
    serial,
    apiLevel: 34,
    targetPackage,
    settle: async () => {},
  });
  await assert.rejects(
    () => scenario({
      serial,
      descriptor: { package: targetPackage },
      installed: { package: targetPackage },
    }),
    (error) => error.code === "PASSIVE_LAUNCH_INVALID",
  );
});

test("runner 拒绝外部 serial、package 和不受支持 API", () => {
  for (const options of [
    {
      command: async () => {},
      aactlPath: "relative/aactl",
      serial,
      apiLevel: 34,
      targetPackage,
    },
    {
      command: async () => {},
      aactlPath: "/external/bin/aactl",
      serial: "device-1",
      apiLevel: 34,
      targetPackage,
    },
    {
      command: async () => {},
      aactlPath: "/external/bin/aactl",
      serial,
      apiLevel: 35,
      targetPackage,
    },
  ]) {
    assert.throws(
      () => createPassiveSmokeScenario(options),
      (error) => error.code === "PASSIVE_RUNNER_INVALID",
    );
  }
});
