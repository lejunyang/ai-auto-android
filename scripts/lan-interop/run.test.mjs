// 脚本用途：测试 LAN 互操作 runner 拒绝参数注入，并严格解析两类 host 的非敏感输出。
import assert from "node:assert/strict";
import test from "node:test";

import {
  parseArguments,
  parseInvitationLine,
  requiredEnvironment,
  validateAuthCompletion,
  validateCliCompletion,
} from "./run.mjs";

const invitation = {
  kind: "ai-auto-lan-invitation",
  payload: "x".repeat(128),
};

const envelope = (data) => JSON.stringify({
  schemaVersion: "1.0",
  requestId: "00000000-0000-4000-8000-000000000000",
  ok: true,
  data,
  error: null,
  meta: { durationMs: 1 },
});

test("只接受固定 profile、接口 ID 和私网 IPv4", () => {
  assert.deepEqual(
    parseArguments([
      "--profile",
      "api-30",
      "--interface",
      "if-18-en1",
      "--address",
      "192.168.31.16",
    ]),
    {
      profile: "api-30",
      interface: "if-18-en1",
      address: "192.168.31.16",
    },
  );
  for (const argv of [
    ["--profile", "api-35", "--interface", "if-18-en1", "--address", "192.168.31.16"],
    ["--profile", "api-30", "--interface", "en1;id", "--address", "192.168.31.16"],
    ["--profile", "api-30", "--interface", "if-18-en1", "--address", "8.8.8.8"],
    ["--profile", "api-30", "--interface", "if-18-en1", "--address", "192.168.999.1"],
  ]) {
    assert.throws(() => parseArguments(argv), { code: "USAGE_ERROR" });
  }
});

test("所有工具链路径必须位于统一外置根", () => {
  const root = "/Volumes/aigo S7 Media/SDK/android-tools";
  assert.equal(requiredEnvironment({
    ANDROID_SDK_ROOT: `${root}/android-sdk`,
    ANDROID_AVD_HOME: `${root}/android-avd`,
    JAVA_HOME: `${root}/jdk/Contents/Home`,
    AACTL_EMULATOR_STATE: `${root}/emulator-state`,
    GOCACHE: `${root}/go-cache`,
    GOMODCACHE: `${root}/go-mod-cache`,
  }).stateRoot, `${root}/emulator-state`);
  assert.throws(
    () => requiredEnvironment({
      ANDROID_SDK_ROOT: "/tmp/android-sdk",
      ANDROID_AVD_HOME: `${root}/android-avd`,
      JAVA_HOME: `${root}/jdk/Contents/Home`,
      AACTL_EMULATOR_STATE: `${root}/emulator-state`,
      GOCACHE: `${root}/go-cache`,
      GOMODCACHE: `${root}/go-mod-cache`,
    }),
    { code: "ENVIRONMENT_INVALID" },
  );
});

test("严格解析 CLI 和 test host 的公开 invitation", () => {
  const cli = parseInvitationLine(envelope({
    phase: "invitation",
    invitationJson: invitation,
    manualCode: "public-manual-code",
    fingerprint: "1111-2222-3333-4444",
    endpoint: {
      host: "192.168.31.16",
      family: "ipv4",
      port: 45000,
      interfaceId: "if-18-en1",
    },
    expiresAt: "2026-08-01T00:00:00Z",
    qrGenerated: false,
    qrFormat: "",
  }), "cli");
  const auth = parseInvitationLine(JSON.stringify({
    phase: "invitation",
    invitationJson: invitation,
    fingerprint: "1111-2222-3333-4444",
  }), "auth");
  assert.equal(cli.argument, auth.argument);
  assert.throws(
    () => parseInvitationLine(JSON.stringify({
      phase: "invitation",
      invitationJson: invitation,
      fingerprint: "1111-2222-3333-4444",
      token: "forbidden",
    }), "auth"),
    { code: "HOST_OUTPUT_INVALID" },
  );
});

test("严格验证三次 device.info 与 close 输出", () => {
  const rpc = [1, 2, 3].map((iteration) => envelope({
    phase: "rpc",
    method: "device.info",
    iteration,
    result: { apiLevel: 30 },
  }));
  const closed = envelope({
    phase: "closed",
    authenticated: true,
    endpoint: {
      host: "192.168.31.16",
      family: "ipv4",
      port: 45000,
      interfaceId: "if-18-en1",
    },
    capabilities: [
      "lan.bridge.mutual-confirmation.v1",
      "lan.bridge.rpc.v1",
    ],
    expiresAt: "2026-08-01T00:00:00Z",
  });
  assert.doesNotThrow(() => validateCliCompletion([...rpc, closed], 30));
  assert.throws(
    () => validateCliCompletion([...rpc, closed], 33),
    { code: "HOST_OUTPUT_INVALID" },
  );
});

test("严格验证 AUTH_INVALID 加密响应后的关闭结果", () => {
  assert.doesNotThrow(() => validateAuthCompletion([
    JSON.stringify({
      phase: "result",
      errorCode: "AUTH_INVALID",
      peerClosed: true,
    }),
  ]));
  assert.throws(
    () => validateAuthCompletion([
      JSON.stringify({
        phase: "result",
        errorCode: "AUTH_INVALID",
        peerClosed: false,
      }),
    ]),
    { code: "HOST_OUTPUT_INVALID" },
  );
});
