// 功能用途：将 N47 Runner 安全接到固定身份 aactl、语义 Bridge 和注入式设备生命周期。
import { createHash } from "node:crypto";
import { execFile as nodeExecFile } from "node:child_process";
import {
  lstat,
  readFile,
  realpath,
} from "node:fs/promises";
import path from "node:path";

export const AACTL_ADAPTER_LIMITS = Object.freeze({
  outputBytes: 1024 * 1024,
  timeoutMs: 30_000,
});

const configKeys = [
  "conditionCatalog",
  "executable",
  "maxDepth",
  "observationTtlMs",
  "repositoryRoot",
  "serial",
  "systemPackages",
];
const dependencyKeys = [
  "artifacts",
  "execFile",
  "lifecycle",
  "now",
  "values",
];
const lifecycleMethods = [
  "stopScenario",
  "closeBridge",
  "clearAppData",
  "restoreSnapshot",
];
const safeSerial = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const packageName =
  /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const stableId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const safeUuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const safeErrorCode = /^[A-Z][A-Z0-9_]{2,95}$/u;
const actionTypes = new Set([
  "launch",
  "tap",
  "input",
  "long-click",
  "scroll",
  "swipe",
  "back",
  "home",
  "recents",
  "switch-app",
]);
const nodeActions = new Set([
  "click",
  "longClick",
  "setText",
  "scrollForward",
  "scrollBackward",
  "scrollUp",
  "scrollDown",
  "scrollLeft",
  "scrollRight",
]);
const conditionKinds = new Set([
  "foreground-package",
  "semantic-state",
  "node-present",
  "node-absent",
  "visual-state",
]);
const conditionOperators = new Set(["equals", "contains"]);
const pagePatterns = Object.freeze([
  ["advertisement", /\b(?:advertisement|sponsored|ad choices)\b/iu],
  ["update-prompt", /\b(?:update available|new version|upgrade now)\b/iu],
  ["ab-variant", /\b(?:a\/b variant|experiment variant|experimental layout)\b/iu],
  ["login-wall", /\b(?:sign in|log in|login required)\b/iu],
  ["emulator-detected", /\b(?:emulator|virtual device).*(?:not supported|detected)\b/iu],
  ["network-failure", /\b(?:network unavailable|no internet|connection failed)\b/iu],
]);
const maximumExecutableBytes = 128 * 1024 * 1024;

export class AactlAdapterError extends Error {
  constructor(code) {
    super(code);
    this.name = "AactlAdapterError";
    this.code = code;
  }
}

const fail = (code) => {
  throw new AactlAdapterError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const isWithin = (root, candidate) => {
  const relative = path.relative(root, candidate);
  return relative === ""
    || (!relative.startsWith(`..${path.sep}`) && relative !== "..");
};

const assertAbsolutePath = (candidate, code) => {
  if (
    typeof candidate !== "string"
    || !path.isAbsolute(candidate)
    || candidate !== path.resolve(candidate)
    || candidate.includes("\u0000")
  ) {
    fail(code);
  }
};

const assertNoSymlinkChain = async (candidate) => {
  const parsed = path.parse(candidate);
  const segments = candidate
    .slice(parsed.root.length)
    .split(path.sep)
    .filter(Boolean);
  let current = parsed.root;
  for (const segment of segments) {
    current = path.join(current, segment);
    let info;
    try {
      info = await lstat(current);
    } catch {
      fail("AACTL_PATH_INVALID");
    }
    if (info.isSymbolicLink()) fail("AACTL_PATH_UNSAFE");
  }
};

const sha256File = async (file) =>
  createHash("sha256").update(await readFile(file)).digest("hex");

const toolIdentity = (info, digest, canonicalPath) =>
  Object.freeze({
    canonicalPath,
    ctimeMs: info.ctimeMs,
    dev: info.dev,
    digest,
    ino: info.ino,
    mode: info.mode,
    mtimeMs: info.mtimeMs,
    size: info.size,
  });

const readExecutable = async (executable, repositoryRoot) => {
  assertAbsolutePath(executable, "AACTL_PATH_INVALID");
  assertAbsolutePath(repositoryRoot, "AACTL_ADAPTER_CONFIG_INVALID");
  if (path.basename(executable) !== "aactl") fail("AACTL_PATH_INVALID");
  await assertNoSymlinkChain(executable);
  let info;
  let canonicalPath;
  let repositoryReal;
  try {
    info = await lstat(executable);
    canonicalPath = await realpath(executable);
    repositoryReal = await realpath(repositoryRoot);
  } catch {
    fail("AACTL_PATH_INVALID");
  }
  if (
    !info.isFile()
    || info.isSymbolicLink()
    || (info.mode & 0o111) === 0
    || info.size < 1
    || info.size > maximumExecutableBytes
    || isWithin(repositoryReal, canonicalPath)
  ) {
    fail("AACTL_PATH_UNSAFE");
  }
  return Object.freeze({
    path: executable,
    identity: toolIdentity(
      info,
      await sha256File(executable),
      canonicalPath,
    ),
  });
};

const assertExecutableUnchanged = async (tool) => {
  try {
    await assertNoSymlinkChain(tool.path);
    const info = await lstat(tool.path);
    const canonicalPath = await realpath(tool.path);
    const digest = await sha256File(tool.path);
    const expected = tool.identity;
    if (
      !info.isFile()
      || info.isSymbolicLink()
      || canonicalPath !== expected.canonicalPath
      || info.ctimeMs !== expected.ctimeMs
      || info.dev !== expected.dev
      || digest !== expected.digest
      || info.ino !== expected.ino
      || info.mode !== expected.mode
      || info.mtimeMs !== expected.mtimeMs
      || info.size !== expected.size
    ) {
      fail("AACTL_CHANGED");
    }
  } catch (error) {
    if (error instanceof AactlAdapterError && error.code === "AACTL_CHANGED") {
      throw error;
    }
    fail("AACTL_CHANGED");
  }
};

const assertDependencies = (dependencies) => {
  if (
    !exactKeys(dependencies, dependencyKeys)
    || typeof dependencies.execFile !== "function"
    || typeof dependencies.now !== "function"
    || dependencies.values === null
    || typeof dependencies.values !== "object"
    || !exactKeys(dependencies.values, ["resolve"])
    || typeof dependencies.values.resolve !== "function"
    || dependencies.artifacts === null
    || typeof dependencies.artifacts !== "object"
    || !exactKeys(dependencies.artifacts, ["collect"])
    || typeof dependencies.artifacts.collect !== "function"
    || dependencies.lifecycle === null
    || typeof dependencies.lifecycle !== "object"
    || !exactKeys(dependencies.lifecycle, lifecycleMethods)
    || lifecycleMethods.some(
      (method) => typeof dependencies.lifecycle[method] !== "function",
    )
  ) {
    fail("AACTL_ADAPTER_DEPENDENCY_INVALID");
  }
};

const assertCatalog = (catalog) => {
  if (
    catalog === null
    || typeof catalog !== "object"
    || Array.isArray(catalog)
    || Object.keys(catalog).length < 1
    || Object.keys(catalog).length > 512
  ) {
    fail("AACTL_ADAPTER_CONFIG_INVALID");
  }
  for (const [id, condition] of Object.entries(catalog)) {
    if (!stableId.test(id) || condition === null || typeof condition !== "object") {
      fail("AACTL_ADAPTER_CONFIG_INVALID");
    }
    const keys = Object.keys(condition).sort();
    if (!conditionKinds.has(condition.kind) || !packageName.test(condition.package ?? "")) {
      fail("AACTL_ADAPTER_CONFIG_INVALID");
    }
    if (condition.kind === "foreground-package" || condition.kind === "visual-state") {
      if (JSON.stringify(keys) !== JSON.stringify(["kind", "package"])) {
        fail("AACTL_ADAPTER_CONFIG_INVALID");
      }
      continue;
    }
    if (
      condition.target === null
      || typeof condition.target !== "object"
      || !exactKeys(condition.target, ["name"])
      || typeof condition.target.name !== "string"
      || condition.target.name.length < 1
      || condition.target.name.length > 128
    ) {
      fail("AACTL_ADAPTER_CONFIG_INVALID");
    }
    if (condition.kind === "node-present" || condition.kind === "node-absent") {
      if (JSON.stringify(keys) !== JSON.stringify(["kind", "package", "target"])) {
        fail("AACTL_ADAPTER_CONFIG_INVALID");
      }
      continue;
    }
    if (
      JSON.stringify(keys)
        !== JSON.stringify(["expected", "kind", "operator", "package", "target"])
      || !conditionOperators.has(condition.operator)
      || typeof condition.expected !== "string"
      || condition.expected.length > 1024
    ) {
      fail("AACTL_ADAPTER_CONFIG_INVALID");
    }
  }
};

const assertConfig = (config) => {
  if (
    !exactKeys(config, configKeys)
    || !safeSerial.test(config.serial ?? "")
    || !Number.isInteger(config.observationTtlMs)
    || config.observationTtlMs < 1000
    || config.observationTtlMs > 120_000
    || !Number.isInteger(config.maxDepth)
    || config.maxDepth < 1
    || config.maxDepth > 100
    || !Array.isArray(config.systemPackages)
    || config.systemPackages.length < 1
    || config.systemPackages.length > 8
    || config.systemPackages.some((value) => !packageName.test(value))
    || new Set(config.systemPackages).size !== config.systemPackages.length
  ) {
    fail("AACTL_ADAPTER_CONFIG_INVALID");
  }
  assertCatalog(config.conditionCatalog);
};

const duplicateJsonKeys = (source) => {
  let index = 0;
  const whitespace = new Set([" ", "\t", "\r", "\n"]);
  const skip = () => {
    while (whitespace.has(source[index])) index += 1;
  };
  const parseString = () => {
    if (source[index++] !== "\"") fail("AACTL_ENVELOPE_INVALID");
    let value = "";
    while (index < source.length) {
      const character = source[index++];
      if (character === "\"") return value;
      if (character === "\\") {
        const escaped = source[index++];
        if (escaped === "u") {
          const raw = source.slice(index, index + 4);
          if (!/^[0-9a-f]{4}$/iu.test(raw)) fail("AACTL_ENVELOPE_INVALID");
          value += String.fromCharCode(Number.parseInt(raw, 16));
          index += 4;
        } else {
          const mapping = {
            "\"": "\"",
            "\\": "\\",
            "/": "/",
            b: "\b",
            f: "\f",
            n: "\n",
            r: "\r",
            t: "\t",
          };
          if (!Object.hasOwn(mapping, escaped)) fail("AACTL_ENVELOPE_INVALID");
          value += mapping[escaped];
        }
      } else {
        if (character.charCodeAt(0) < 0x20) fail("AACTL_ENVELOPE_INVALID");
        value += character;
      }
    }
    fail("AACTL_ENVELOPE_INVALID");
  };
  const parsePrimitive = () => {
    const start = index;
    while (
      index < source.length
      && !whitespace.has(source[index])
      && ![",", "]", "}"].includes(source[index])
    ) {
      index += 1;
    }
    if (start === index) fail("AACTL_ENVELOPE_INVALID");
  };
  const parseValue = () => {
    skip();
    if (source[index] === "{") {
      index += 1;
      skip();
      const keys = new Set();
      if (source[index] === "}") {
        index += 1;
        return;
      }
      while (index < source.length) {
        skip();
        const key = parseString();
        if (keys.has(key)) fail("AACTL_ENVELOPE_INVALID");
        keys.add(key);
        skip();
        if (source[index++] !== ":") fail("AACTL_ENVELOPE_INVALID");
        parseValue();
        skip();
        const delimiter = source[index++];
        if (delimiter === "}") return;
        if (delimiter !== ",") fail("AACTL_ENVELOPE_INVALID");
      }
      fail("AACTL_ENVELOPE_INVALID");
    }
    if (source[index] === "[") {
      index += 1;
      skip();
      if (source[index] === "]") {
        index += 1;
        return;
      }
      while (index < source.length) {
        parseValue();
        skip();
        const delimiter = source[index++];
        if (delimiter === "]") return;
        if (delimiter !== ",") fail("AACTL_ENVELOPE_INVALID");
      }
      fail("AACTL_ENVELOPE_INVALID");
    }
    if (source[index] === "\"") {
      parseString();
      return;
    }
    parsePrimitive();
  };
  parseValue();
  skip();
  if (index !== source.length) fail("AACTL_ENVELOPE_INVALID");
};

const parseEnvelope = (stdout, stderr) => {
  if (
    typeof stdout !== "string"
    || typeof stderr !== "string"
    || Buffer.byteLength(stdout, "utf8") > AACTL_ADAPTER_LIMITS.outputBytes
    || stderr !== ""
  ) {
    fail("AACTL_ENVELOPE_INVALID");
  }
  const source = stdout.endsWith("\n") ? stdout.slice(0, -1) : stdout;
  if (source.length === 0 || source.includes("\n")) fail("AACTL_ENVELOPE_INVALID");
  duplicateJsonKeys(source);
  let envelope;
  try {
    envelope = JSON.parse(source);
  } catch {
    fail("AACTL_ENVELOPE_INVALID");
  }
  if (
    !exactKeys(envelope, [
      "data",
      "error",
      "meta",
      "ok",
      "requestId",
      "schemaVersion",
    ])
    || envelope.schemaVersion !== "1.0"
    || !safeUuid.test(envelope.requestId ?? "")
    || typeof envelope.ok !== "boolean"
    || !exactKeys(envelope.meta, ["durationMs"])
    || !Number.isInteger(envelope.meta.durationMs)
    || envelope.meta.durationMs < 0
    || envelope.meta.durationMs > 86400000
    || (
      envelope.ok
        ? envelope.error !== null
          || envelope.data === null
          || typeof envelope.data !== "object"
          || Array.isArray(envelope.data)
        : envelope.data !== null
          || (
            !exactKeys(envelope.error, ["code", "message", "retryable"])
            && !exactKeys(
              envelope.error,
              ["code", "details", "message", "retryable"],
            )
          )
          || !safeErrorCode.test(envelope.error.code ?? "")
          || typeof envelope.error.message !== "string"
          || typeof envelope.error.retryable !== "boolean"
          || (
            Object.hasOwn(envelope.error, "details")
            && (
              envelope.error.details === null
              || typeof envelope.error.details !== "object"
              || Array.isArray(envelope.error.details)
            )
          )
    )
  ) {
    fail("AACTL_ENVELOPE_INVALID");
  }
  return envelope;
};

const runExecFile = (execFile, executable, argv, timeoutMs) =>
  new Promise((resolve, reject) => {
    execFile(
      executable,
      argv,
      {
        encoding: "utf8",
        maxBuffer: AACTL_ADAPTER_LIMITS.outputBytes,
        shell: false,
        timeout: timeoutMs,
        windowsHide: true,
      },
      (error, stdout, stderr) => {
        try {
          const envelope = parseEnvelope(stdout, stderr);
          if (error !== null && envelope.ok) {
            reject(new AactlAdapterError("AACTL_EXEC_FAILED"));
            return;
          }
          resolve(envelope);
        } catch (parseError) {
          reject(
            error === null
              ? parseError
              : new AactlAdapterError("AACTL_EXEC_FAILED"),
          );
        }
      },
    );
  });

const flattenNodes = (root) => {
  const output = [];
  const visit = (node, depth) => {
    if (depth > 100) fail("SNAPSHOT_INVALID");
    output.push(node);
    if (output.length > 10000) fail("SNAPSHOT_INVALID");
    for (const child of node.children ?? []) visit(child, depth + 1);
  };
  visit(root, 1);
  return output;
};

const assertState = (state) =>
  exactKeys(state, [
    "checkable",
    "checked",
    "clickable",
    "editable",
    "enabled",
    "focusable",
    "focused",
    "longClickable",
    "password",
    "scrollable",
    "selected",
    "sensitive",
    "visibleToUser",
  ])
  && Object.values(state).every((value) => typeof value === "boolean");

const assertBounds = (bounds) =>
  exactKeys(bounds, ["bottom", "left", "right", "top"])
  && Object.values(bounds).every(Number.isInteger)
  && bounds.left >= 0
  && bounds.top >= 0
  && bounds.right > bounds.left
  && bounds.bottom > bounds.top;

const assertNode = (node, expectedPackage) => {
  const requiredKeys = [
    "actions",
    "bounds",
    "children",
    "state",
  ];
  const optionalKeys = [
    "className",
    "contentDescription",
    "packageName",
    "resourceId",
    "text",
  ];
  if (
    node === null
    || typeof node !== "object"
    || Array.isArray(node)
    || requiredKeys.some((key) => !Object.hasOwn(node, key))
    || Object.keys(node).some(
      (key) => !requiredKeys.includes(key) && !optionalKeys.includes(key),
    )
    || (
      Object.hasOwn(node, "packageName")
      && (
        !packageName.test(node.packageName ?? "")
        || node.packageName !== expectedPackage
      )
    )
    || (
      Object.hasOwn(node, "className")
      && (
        typeof node.className !== "string"
        || node.className.length > 255
      )
    )
    || !assertBounds(node.bounds)
    || !Array.isArray(node.actions)
    || node.actions.length > 16
    || node.actions.some((action) => !nodeActions.has(action))
    || new Set(node.actions).size !== node.actions.length
    || !assertState(node.state)
    || !Array.isArray(node.children)
    || node.children.length > 1024
  ) {
    fail("SNAPSHOT_INVALID");
  }
  for (const name of ["text", "contentDescription", "resourceId"]) {
    if (
      Object.hasOwn(node, name)
      && (typeof node[name] !== "string" || node[name].length > 4096)
    ) {
      fail("SNAPSHOT_INVALID");
    }
  }
};

const assertSnapshot = (data, expectedDepth) => {
  if (
    !exactKeys(data, ["maxDepth", "root"])
    || !Number.isInteger(data.maxDepth)
    || data.maxDepth < 1
    || data.maxDepth > 100
    || data.maxDepth !== expectedDepth
    || data.root === null
    || typeof data.root !== "object"
    || !packageName.test(data.root.packageName ?? "")
  ) {
    fail("SNAPSHOT_INVALID");
  }
  const nodes = flattenNodes(data.root);
  if (nodes.length < 1 || nodes.length > 10000) fail("SNAPSHOT_INVALID");
  for (const node of nodes) assertNode(node, data.root.packageName);
  return Object.freeze({
    packageName: data.root.packageName,
    nodes: Object.freeze(nodes),
    snapshot: data,
  });
};

const visibleText = (node) =>
  [node.text, node.contentDescription]
    .filter((value) => typeof value === "string" && value.length > 0);

const classifyPage = (snapshot, systemPackages) => {
  if (systemPackages.has(snapshot.packageName)) return "normal";
  const nodes = snapshot.nodes;
  const texts = nodes
    .filter((node) => !node.state.password && !node.state.sensitive)
    .flatMap(visibleText);
  if (texts.length === 0) return "unknown";
  const joined = texts.join("\n");
  for (const [classification, pattern] of pagePatterns) {
    if (pattern.test(joined)) return classification;
  }
  return "normal";
};

const findNodes = (snapshot, name) =>
  snapshot.nodes.filter((node) =>
    node.contentDescription === name || node.text === name);

const conditionMatches = (catalog, condition, snapshot, targetPackage) => {
  if (
    !exactKeys(condition, ["id", "kind"])
    || !stableId.test(condition.id ?? "")
    || !conditionKinds.has(condition.kind)
  ) {
    fail("CONDITION_INVALID");
  }
  const configured = catalog[condition.id];
  if (
    configured === undefined
    || configured.kind !== condition.kind
  ) {
    return false;
  }
  if (condition.kind === "visual-state") fail("VISUAL_ADAPTER_UNAVAILABLE");
  if (condition.kind === "foreground-package") {
    return snapshot.packageName === configured.package;
  }
  if (configured.package !== targetPackage) return false;
  const rawMatches = findNodes(snapshot, configured.target.name);
  const matches = rawMatches
    .filter((node) =>
      node.state.visibleToUser
      && !node.state.password
      && !node.state.sensitive);
  if (condition.kind === "node-present") return matches.length === 1;
  if (condition.kind === "node-absent") return rawMatches.length === 0;
  if (matches.length !== 1) return false;
  const values = visibleText(matches[0]);
  return configured.operator === "equals"
    ? values.includes(configured.expected)
    : values.some((value) => value.includes(configured.expected));
};

const requiredNodeAction = (action) => {
  switch (action.type) {
  case "tap":
    return "click";
  case "long-click":
    return "longClick";
  case "scroll":
    return {
      up: "scrollUp",
      down: "scrollDown",
      left: "scrollLeft",
      right: "scrollRight",
    }[action.direction];
  default:
    return null;
  }
};

const selectorAction = (action, packageName, strategy) => {
  if (!["tap", "long-click", "scroll"].includes(action.type)) {
    return null;
  }
  const target = {
    packageName,
    selectorCandidates: [{
      strategy,
      value: action.target.name,
      weight: 1,
      required: true,
    }],
  };
  switch (action.type) {
  case "tap":
    return { type: "ui.click", params: { target } };
  case "long-click":
    return { type: "ui.longClick", params: { target } };
  case "scroll":
    return {
      type: "ui.scroll",
      params: {
        target,
        direction: action.direction,
      },
    };
  default:
    return null;
  }
};

const systemAction = (type) => ({
  type: {
    back: "ui.back",
    home: "ui.home",
    recents: "ui.recents",
  }[type],
  params: {},
});

const assertAction = (action) => {
  if (
    action === null
    || typeof action !== "object"
    || Array.isArray(action)
    || !actionTypes.has(action.type)
  ) {
    fail("ACTION_INVALID");
  }
  if (action.type === "launch" || action.type === "switch-app") {
    if (!exactKeys(action, ["package", "type"]) || !packageName.test(action.package)) {
      fail("ACTION_INVALID");
    }
    return;
  }
  if (["back", "home", "recents"].includes(action.type)) {
    if (!exactKeys(action, ["type"])) fail("ACTION_INVALID");
    return;
  }
  const expected = action.type === "input"
    ? ["target", "type", "valueRef"]
    : ["scroll", "swipe"].includes(action.type)
      ? ["direction", "target", "type"]
      : ["target", "type"];
  if (
    !exactKeys(action, expected)
    || !exactKeys(action.target, ["kind", "name"])
    || action.target.kind !== "semantic-name"
    || typeof action.target.name !== "string"
    || action.target.name.length < 1
    || action.target.name.length > 128
    || (
      action.type === "input"
      && !stableId.test(action.valueRef ?? "")
    )
    || (
      ["scroll", "swipe"].includes(action.type)
      && !["up", "down", "left", "right"].includes(action.direction)
    )
  ) {
    fail("ACTION_INVALID");
  }
};

const safeError = (code) =>
  new AactlAdapterError(`AACTL_${safeErrorCode.test(code ?? "") ? code : "FAILED"}`);

const remainingTimeout = (deadlineAt, now) => {
  const remaining = Date.parse(deadlineAt) - Date.parse(now);
  if (!Number.isFinite(remaining) || remaining < 1) {
    fail("AACTL_DEADLINE_EXCEEDED");
  }
  return Math.min(remaining, AACTL_ADAPTER_LIMITS.timeoutMs);
};

const assertBridgeActionResult = (data, action) => {
  const required = ["route"];
  const optional = ["matchScore", "matchedPath"];
  if (
    data === null
    || typeof data !== "object"
    || Array.isArray(data)
    || required.some((key) => !Object.hasOwn(data, key))
    || Object.keys(data).some(
      (key) => !required.includes(key) && !optional.includes(key),
    )
    || ![
      "nodeAction",
      "nodeGesture",
      "coordinateGesture",
      "screenGesture",
      "globalAction",
    ].includes(data.route)
    || (
      Object.hasOwn(data, "matchScore")
      && (
        typeof data.matchScore !== "number"
        || !Number.isFinite(data.matchScore)
        || data.matchScore < 0
        || data.matchScore > 1
      )
    )
    || (
      Object.hasOwn(data, "matchedPath")
      && (
        !Array.isArray(data.matchedPath)
        || data.matchedPath.length > 100
        || data.matchedPath.some(
          (value) => !Number.isInteger(value) || value < 0 || value > 10000,
        )
      )
    )
  ) {
    fail("AACTL_ACTION_RESULT_INVALID");
  }
  if (
    ["back", "home", "recents"].includes(action.type)
      ? data.route !== "globalAction"
      : !["nodeAction", "nodeGesture"].includes(data.route)
  ) {
    fail("AACTL_ACTION_RESULT_INVALID");
  }
};

const assertLaunchResult = (data, serial) => {
  if (
    !exactKeys(data, ["action", "device"])
    || data.device !== serial
    || data.action !== "app.launch"
  ) {
    fail("AACTL_ACTION_RESULT_INVALID");
  }
};

export const createAactlScenarioPorts = async (
  config,
  dependencies = {
    execFile: nodeExecFile,
  },
) => {
  assertConfig(config);
  assertDependencies(dependencies);
  const tool = await readExecutable(config.executable, config.repositoryRoot);
  const observations = new Map();
  const systemPackages = new Set(config.systemPackages);
  const tokens = new WeakMap();
  const consumedTokens = new WeakSet();
  let observationSequence = 0;
  let generation = 0;

  const callAactl = async (argv, timeoutMs) => {
    await assertExecutableUnchanged(tool);
    const envelope = await runExecFile(
      dependencies.execFile,
      tool.path,
      argv,
      timeoutMs,
    );
    if (!envelope.ok) throw safeError(envelope.error.code);
    return envelope.data;
  };

  const observationRecord = (publicObservation) => {
    const record = observations.get(publicObservation?.observationId);
    if (
      record === undefined
      || publicObservation.foregroundPackage !== record.public.foregroundPackage
      || publicObservation.expiresAt !== record.public.expiresAt
      || publicObservation.observedAt !== record.public.observedAt
      || publicObservation.pageClass !== record.public.pageClass
      || Date.parse(record.public.expiresAt) <= Date.parse(dependencies.now())
    ) {
      fail("OBSERVATION_UNAVAILABLE");
    }
    return record;
  };

  const observer = {
    observe: async ({
      targetPackage,
      timeoutMs,
      dynamicRegions,
      ...unknown
    }) => {
      if (
        Object.keys(unknown).length > 0
        || !packageName.test(targetPackage ?? "")
        || !Number.isInteger(timeoutMs)
        || timeoutMs < 100
        || timeoutMs > 120000
        || !Array.isArray(dynamicRegions)
      ) {
        fail("OBSERVATION_REQUEST_INVALID");
      }
      const data = await callAactl([
        "bridge",
        "snapshot",
        "--device",
        config.serial,
        "--max-depth",
        String(config.maxDepth),
        "--json",
      ], Math.min(timeoutMs, AACTL_ADAPTER_LIMITS.timeoutMs));
      const snapshot = assertSnapshot(data, config.maxDepth);
      const observedAt = dependencies.now();
      observationSequence += 1;
      const digest = createHash("sha256")
        .update(JSON.stringify(data))
        .update("\0")
        .update(observedAt)
        .update("\0")
        .update(String(observationSequence))
        .digest("hex");
      const publicObservation = Object.freeze({
        observationId: `aactl-observation-${digest}`,
        observedAt,
        expiresAt: new Date(
          Date.parse(observedAt) + config.observationTtlMs,
        ).toISOString(),
        foregroundPackage: snapshot.packageName,
        pageClass: classifyPage(snapshot, systemPackages),
      });
      observations.set(publicObservation.observationId, Object.freeze({
        public: publicObservation,
        snapshot,
      }));
      while (observations.size > 2) {
        observations.delete(observations.keys().next().value);
      }
      return publicObservation;
    },
  };

  const conditions = {
    verify: async ({
      phase,
      conditions: requested,
      observation,
      targetPackage,
      deadlineAt,
      previousObservationId,
      ...unknown
    }) => {
      if (
        Object.keys(unknown).length > 0
        || !["pre", "post"].includes(phase)
        || !Array.isArray(requested)
        || requested.length < 1
        || requested.length > 16
        || !packageName.test(targetPackage ?? "")
        || !Number.isFinite(Date.parse(deadlineAt))
        || (
          phase === "post"
          && (
            !stableId.test(previousObservationId ?? "")
            || previousObservationId === observation?.observationId
            || !observations.has(previousObservationId)
          )
        )
        || (phase === "pre" && previousObservationId !== undefined)
      ) {
        fail("CONDITION_INVALID");
      }
      const record = observationRecord(observation);
      return requested.every((condition) =>
        conditionMatches(
          config.conditionCatalog,
          condition,
          record.snapshot,
          targetPackage,
        ));
    },
  };

  const router = {
    resolve: async ({
      action,
      observation,
      targetPackage,
      allowedRoutes,
      minimumScore,
      dynamicRegions,
      ...unknown
    }) => {
      if (
        Object.keys(unknown).length > 0
        || !packageName.test(targetPackage ?? "")
        || !Array.isArray(allowedRoutes)
        || !allowedRoutes.includes("semantic")
        || typeof minimumScore !== "number"
        || minimumScore < 0
        || minimumScore > 1
        || !Array.isArray(dynamicRegions)
      ) {
        fail("ROUTE_UNAVAILABLE");
      }
      assertAction(action);
      if (action.type === "input") fail("INPUT_ADAPTER_UNAVAILABLE");
      const record = observationRecord(observation);
      if (
        record.snapshot.packageName !== targetPackage
        && !["launch", "switch-app", "home", "recents"].includes(action.type)
      ) {
        fail("FOREGROUND_PACKAGE_MISMATCH");
      }
      if (record.public.pageClass !== "normal") fail("PAGE_NOT_ACTIONABLE");
      if (action.type === "swipe") fail("ROUTE_UNAVAILABLE");

      let node = null;
      const requiredAction = requiredNodeAction(action);
      let selectorStrategy = null;
      if (requiredAction !== null) {
        const matches = findNodes(record.snapshot, action.target.name);
        if (matches.length === 0) fail("SELECTOR_NOT_FOUND");
        if (matches.length > 1) fail("SELECTOR_AMBIGUOUS");
        node = matches[0];
        if (node.packageName !== targetPackage) fail("FOREGROUND_PACKAGE_MISMATCH");
        if (node.state.password || node.state.sensitive) fail("TARGET_SENSITIVE");
        if (!node.state.visibleToUser || !node.state.enabled) {
          fail("TARGET_NOT_ACTIONABLE");
        }
        if (!node.actions.includes(requiredAction)) fail("ACTION_NOT_SUPPORTED");
        selectorStrategy = node.contentDescription === action.target.name
          ? "contentDescription"
          : "text";
      }
      const token = Object.freeze({});
      tokens.set(token, Object.freeze({
        action,
        observationId: observation.observationId,
        packageName: targetPackage,
        generation,
        protocolAction: selectorAction(
          action,
          targetPackage,
          selectorStrategy,
        )
          ?? (["back", "home", "recents"].includes(action.type)
            ? systemAction(action.type)
            : null),
        node,
      }));
      return Object.freeze({
        route: "semantic",
        score: 1,
        attempts: 1,
        observationId: observation.observationId,
        token,
      });
    },
  };

  const executor = {
    execute: async ({
      action,
      route,
      routeToken,
      observationId,
      targetPackage,
      deadlineAt,
      value,
      ...unknown
    }) => {
      if (
        Object.keys(unknown).length > 0
        || route !== "semantic"
        || !packageName.test(targetPackage ?? "")
        || !Number.isFinite(Date.parse(deadlineAt))
        || routeToken === null
        || typeof routeToken !== "object"
      ) {
        fail("EXECUTION_REQUEST_INVALID");
      }
      if (consumedTokens.has(routeToken)) fail("ROUTE_TOKEN_CONSUMED");
      const record = tokens.get(routeToken);
      if (
        record === undefined
        || record.generation !== generation
        || record.observationId !== observationId
        || record.packageName !== targetPackage
        || JSON.stringify(record.action) !== JSON.stringify(action)
      ) {
        fail("ROUTE_TOKEN_INVALID");
      }
      consumedTokens.add(routeToken);
      tokens.delete(routeToken);
      if (Date.parse(deadlineAt) <= Date.parse(dependencies.now())) {
        return Object.freeze({ committed: false });
      }
      let argv;
      if (action.type === "launch" || action.type === "switch-app") {
        argv = [
          "action",
          "launch",
          "--device",
          config.serial,
          "--package",
          action.package,
          "--json",
        ];
      } else {
        const protocolAction = structuredClone(record.protocolAction);
        if (value !== undefined) {
          fail("EXECUTION_REQUEST_INVALID");
        }
        argv = [
          "bridge",
          "action",
          "--device",
          config.serial,
          "--action",
          JSON.stringify(protocolAction),
          "--json",
        ];
      }
      try {
        const data = await callAactl(
          argv,
          remainingTimeout(deadlineAt, dependencies.now()),
        );
        if (action.type === "launch" || action.type === "switch-app") {
          assertLaunchResult(data, config.serial);
        } else {
          assertBridgeActionResult(data, action);
        }
        return Object.freeze({ committed: true });
      } catch (error) {
        if (
          error instanceof AactlAdapterError
          && [
            "AACTL_ACTION_NOT_ALLOWED",
            "AACTL_CAPABILITY_UNAVAILABLE",
            "AACTL_SELECTOR_NOT_FOUND",
            "AACTL_SELECTOR_AMBIGUOUS",
          ].includes(error.code)
        ) {
          return Object.freeze({ committed: false });
        }
        throw error;
      }
    },
  };

  const lifecycle = Object.freeze(Object.fromEntries(
    lifecycleMethods.map((method) => [
      method,
      async (context) => {
        generation += 1;
        observations.clear();
        return dependencies.lifecycle[method](context);
      },
    ]),
  ));

  return Object.freeze({
    observer: Object.freeze(observer),
    conditions: Object.freeze(conditions),
    router: Object.freeze(router),
    executor: Object.freeze(executor),
    values: dependencies.values,
    artifacts: dependencies.artifacts,
    lifecycle,
  });
};
