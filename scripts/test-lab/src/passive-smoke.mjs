// 脚本用途：对已验证 App 执行固定的只启动、只观察场景，并将 hierarchy 压缩为无文本摘要。
import { createHash } from "node:crypto";
import path from "node:path";

import { ExternalAppError } from "./manifest.mjs";

const serialPattern = /^emulator-[0-9]{4,5}$/u;
const packagePattern =
  /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const sha256Pattern = /^[0-9a-f]{64}$/u;
const maxHierarchyBytes = 8 * 1024 * 1024;
const signalPatterns = Object.freeze({
  consentPrompt: /(?:隐私|用户协议|服务协议|同意并继续|privacy|terms|agree and continue)/iu,
  loginPrompt: /(?:登录|注册|手机号|验证码|sign in|log in|login|verification code)/iu,
  permissionPrompt: /(?:权限|允许|仅在使用中|permission|allow while using)/iu,
  updatePrompt: /(?:更新|升级|新版本|update available|new version|upgrade)/iu,
  advertisement: /(?:广告|赞助|advertisement|sponsored)/iu,
  networkFailure: /(?:网络|连接失败|离线|重试|network|offline|connection failed|retry)/iu,
  emulatorNotice: /(?:模拟器|虚拟设备|emulator|virtual device)/iu,
});

const fail = (code) => {
  throw new ExternalAppError(code);
};

const exactKeys = (value, keys) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...keys].sort());

const runAactl = async (command, executable, args, timeoutMs, maxBuffer) => {
  let result;
  try {
    result = await command(executable, Object.freeze([...args]), {
      maxBuffer,
      timeoutMs,
    });
  } catch {
    fail("PASSIVE_COMMAND_FAILED");
  }
  if (
    result === null
    || typeof result !== "object"
    || result.code !== 0
    || result.timedOut === true
    || typeof result.stdout !== "string"
    || typeof result.stderr !== "string"
    || result.stderr !== ""
  ) {
    fail("PASSIVE_COMMAND_FAILED");
  }
  let envelope;
  try {
    envelope = JSON.parse(result.stdout);
  } catch {
    fail("PASSIVE_OUTPUT_INVALID");
  }
  if (
    !exactKeys(
      envelope,
      ["schemaVersion", "requestId", "ok", "data", "error", "meta"],
    )
    || envelope.schemaVersion !== "1.0"
    || envelope.ok !== true
    || envelope.error !== null
  ) {
    fail("PASSIVE_OUTPUT_INVALID");
  }
  return envelope.data;
};

const extractAttributeValues = (xml, name) => {
  const values = [];
  const expression = new RegExp(`\\s${name}="([^"]*)"`, "gu");
  for (const match of xml.matchAll(expression)) values.push(match[1]);
  return values;
};

export const summarizeHierarchy = ({
  xml,
  sizeBytes,
  sha256,
  targetPackage,
}) => {
  if (
    typeof xml !== "string"
    || !Number.isInteger(sizeBytes)
    || sizeBytes < 1
    || sizeBytes > maxHierarchyBytes
    || !sha256Pattern.test(sha256 ?? "")
    || !packagePattern.test(targetPackage ?? "")
  ) {
    fail("PASSIVE_HIERARCHY_INVALID");
  }
  const actualBytes = Buffer.byteLength(xml, "utf8");
  const actualSha256 = createHash("sha256").update(xml, "utf8").digest("hex");
  if (actualBytes !== sizeBytes || actualSha256 !== sha256) {
    fail("PASSIVE_HIERARCHY_INVALID");
  }
  const packages = [...new Set(
    extractAttributeValues(xml, "package")
      .filter((value) => packagePattern.test(value)),
  )].sort();
  const visibleText = [
    ...extractAttributeValues(xml, "text"),
    ...extractAttributeValues(xml, "content-desc"),
  ].join("\n");
  const signals = {};
  for (const [name, pattern] of Object.entries(signalPatterns)) {
    signals[name] = pattern.test(visibleText);
  }
  return Object.freeze({
    format: "uiautomator-xml",
    sizeBytes,
    sha256,
    nodeCount: [...xml.matchAll(/<node(?:\s|>)/gu)].length,
    packages: Object.freeze(packages),
    targetPackageVisible: packages.includes(targetPackage),
    signals: Object.freeze(signals),
  });
};

export const createPassiveSmokeScenario = ({
  command,
  aactlPath,
  serial,
  apiLevel,
  targetPackage,
  settle = () => new Promise((resolve) => setTimeout(resolve, 3_000)),
  onStage = () => {},
}) => {
  if (
    typeof command !== "function"
    || typeof settle !== "function"
    || typeof onStage !== "function"
    || typeof aactlPath !== "string"
    || !path.isAbsolute(aactlPath)
    || !serialPattern.test(serial ?? "")
    || ![30, 33, 34].includes(apiLevel)
    || !packagePattern.test(targetPackage ?? "")
  ) {
    fail("PASSIVE_RUNNER_INVALID");
  }
  let used = false;
  return async ({ serial: contextSerial, descriptor, installed }) => {
    if (
      used
      || contextSerial !== serial
      || descriptor?.package !== targetPackage
      || installed?.package !== targetPackage
    ) {
      fail("PASSIVE_CONTEXT_DRIFT");
    }
    used = true;
    onStage("device-info");
    const device = await runAactl(
      command,
      aactlPath,
      ["device", "info", "--device", serial, "--json"],
      30_000,
      1024 * 1024,
    );
    if (
      device?.serial !== serial
      || device?.state !== "device"
      || device?.transport !== "emulator"
      || device?.apiLevel !== apiLevel
      || !Array.isArray(device.capabilities)
    ) {
      fail("PASSIVE_DEVICE_MISMATCH");
    }
    onStage("launch");
    const launch = await runAactl(
      command,
      aactlPath,
      [
        "action",
        "launch",
        "--device",
        serial,
        "--package",
        targetPackage,
        "--json",
      ],
      30_000,
      1024 * 1024,
    );
    if (
      !exactKeys(launch, ["device", "action"])
      || launch.device !== serial
      || launch.action !== "app.launch"
    ) {
      fail("PASSIVE_LAUNCH_INVALID");
    }
    await settle();
    onStage("hierarchy");
    const hierarchy = await runAactl(
      command,
      aactlPath,
      ["observe", "hierarchy", "--device", serial, "--json"],
      30_000,
      16 * 1024 * 1024,
    );
    if (
      !exactKeys(
        hierarchy,
        ["device", "format", "xml", "sizeBytes", "sha256"],
      )
      || hierarchy.device !== serial
      || hierarchy.format !== "uiautomator-xml"
    ) {
      fail("PASSIVE_HIERARCHY_INVALID");
    }
    return summarizeHierarchy({
      xml: hierarchy.xml,
      sizeBytes: hierarchy.sizeBytes,
      sha256: hierarchy.sha256,
      targetPackage,
    });
  };
};
