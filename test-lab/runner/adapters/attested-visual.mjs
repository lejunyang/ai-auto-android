// 功能用途：在既有 N47 端口上提供无坐标 attested visual route，并约束一次性提交语义。
import { createHash } from "node:crypto";
import { spawn as nodeSpawn } from "node:child_process";
import {
  lstat,
  readFile,
  realpath,
} from "node:fs/promises";
import path from "node:path";

const OUTPUT_BYTES = 1024 * 1024;
const MAXIMUM_EXECUTABLE_BYTES = 128 * 1024 * 1024;
const protocolVersion = "2025-06-18";
const toolName = "android_visual_action_execute";
const packageName =
  /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const safeSerial = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const stableId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const fingerprint = /^[a-f0-9]{64}$/u;
const supportedActions = new Set(["tap", "long-click", "swipe"]);
const basePortMethods = Object.freeze({
  observer: ["observe"],
  conditions: ["verify"],
  router: ["resolve"],
  executor: ["execute"],
  values: ["resolve"],
  artifacts: ["collect"],
  lifecycle: [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ],
});

export class AttestedVisualAdapterError extends Error {
  constructor(code) {
    super(code);
    this.name = "AttestedVisualAdapterError";
    this.code = code;
  }
}

const fail = (code) => {
  throw new AttestedVisualAdapterError(code);
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
      fail("VISUAL_AACTL_PATH_INVALID");
    }
    if (info.isSymbolicLink()) fail("VISUAL_AACTL_PATH_UNSAFE");
  }
};

const hashFile = async (file) =>
  createHash("sha256").update(await readFile(file)).digest("hex");

const bindExecutable = async (executable, repositoryRoot) => {
  if (
    typeof executable !== "string"
    || !path.isAbsolute(executable)
    || executable !== path.resolve(executable)
    || path.basename(executable) !== "aactl"
    || typeof repositoryRoot !== "string"
    || !path.isAbsolute(repositoryRoot)
    || repositoryRoot !== path.resolve(repositoryRoot)
  ) {
    fail("VISUAL_AACTL_PATH_INVALID");
  }
  await assertNoSymlinkChain(executable);
  let info;
  let canonical;
  let repository;
  try {
    info = await lstat(executable);
    canonical = await realpath(executable);
    repository = await realpath(repositoryRoot);
  } catch {
    fail("VISUAL_AACTL_PATH_INVALID");
  }
  if (
    !info.isFile()
    || info.isSymbolicLink()
    || (info.mode & 0o111) === 0
    || info.size < 1
    || info.size > MAXIMUM_EXECUTABLE_BYTES
    || isWithin(repository, canonical)
  ) {
    fail("VISUAL_AACTL_PATH_UNSAFE");
  }
  return Object.freeze({
    path: executable,
    identity: Object.freeze({
      canonical,
      ctimeMs: info.ctimeMs,
      dev: info.dev,
      digest: await hashFile(executable),
      ino: info.ino,
      mode: info.mode,
      mtimeMs: info.mtimeMs,
      size: info.size,
    }),
  });
};

const assertExecutableUnchanged = async (tool) => {
  try {
    await assertNoSymlinkChain(tool.path);
    const info = await lstat(tool.path);
    const canonical = await realpath(tool.path);
    const digest = await hashFile(tool.path);
    const expected = tool.identity;
    if (
      !info.isFile()
      || info.isSymbolicLink()
      || canonical !== expected.canonical
      || info.ctimeMs !== expected.ctimeMs
      || info.dev !== expected.dev
      || digest !== expected.digest
      || info.ino !== expected.ino
      || info.mode !== expected.mode
      || info.mtimeMs !== expected.mtimeMs
      || info.size !== expected.size
    ) {
      fail("VISUAL_AACTL_CHANGED");
    }
  } catch (error) {
    if (
      error instanceof AttestedVisualAdapterError
      && error.code === "VISUAL_AACTL_CHANGED"
    ) {
      throw error;
    }
    fail("VISUAL_AACTL_CHANGED");
  }
};

const assertBasePorts = (ports) => {
  if (!exactKeys(ports, Object.keys(basePortMethods))) {
    fail("VISUAL_ADAPTER_CONFIG_INVALID");
  }
  for (const [port, methods] of Object.entries(basePortMethods)) {
    if (
      ports[port] === null
      || typeof ports[port] !== "object"
      || methods.some((method) => typeof ports[port][method] !== "function")
    ) {
      fail("VISUAL_ADAPTER_CONFIG_INVALID");
    }
  }
};

const assertConfig = (config, dependencies) => {
  if (
    !exactKeys(config, [
      "basePorts",
      "deviceFingerprint",
      "executable",
      "repositoryRoot",
      "serial",
      "targetPackages",
    ])
    || !safeSerial.test(config.serial ?? "")
    || !fingerprint.test(config.deviceFingerprint ?? "")
    || !Array.isArray(config.targetPackages)
    || config.targetPackages.length < 1
    || config.targetPackages.length > 4
    || config.targetPackages.some((value) => !packageName.test(value))
    || new Set(config.targetPackages).size !== config.targetPackages.length
    || !exactKeys(dependencies, ["executeMcp", "now"])
    || typeof dependencies.executeMcp !== "function"
    || typeof dependencies.now !== "function"
  ) {
    fail("VISUAL_ADAPTER_CONFIG_INVALID");
  }
  assertBasePorts(config.basePorts);
};

const assertAction = (action) => {
  const expected = action?.type === "swipe"
    ? ["direction", "target", "type"]
    : action?.type === "long-click"
      ? ["target", "type"]
      : ["target", "type"];
  if (
    !exactKeys(action, expected)
    || !supportedActions.has(action.type)
    || !exactKeys(action.target, ["kind", "name"])
    || action.target.kind !== "semantic-name"
    || typeof action.target.name !== "string"
    || action.target.name.length < 1
    || action.target.name.length > 128
    || (
      action.type === "swipe"
      && !["up", "down", "left", "right"].includes(action.direction)
    )
  ) {
    fail("VISUAL_ACTION_INVALID");
  }
};

const createMcpInput = (serial, targetPackage, action) => {
  const argumentsValue = {
    device: serial,
    expectedPackage: targetPackage,
    target: {
      label: action.target.name,
    },
    action: {
      type: action.type,
      ...(action.type === "swipe" ? { direction: action.direction } : {}),
    },
  };
  const messages = [
    {
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion,
        capabilities: {},
        clientInfo: {
          name: "n47-production-fixture-runner",
          version: "1.0",
        },
      },
    },
    {
      jsonrpc: "2.0",
      method: "notifications/initialized",
      params: {},
    },
    {
      jsonrpc: "2.0",
      id: 2,
      method: "tools/call",
      params: {
        name: toolName,
        arguments: argumentsValue,
      },
    },
  ];
  return `${messages.map((message) => JSON.stringify(message)).join("\n")}\n`;
};

const parseMcpOutput = (result, expectedFingerprint) => {
  if (
    !exactKeys(result, ["code", "stderr", "stdout", "timedOut"])
    || result.code !== 0
    || result.timedOut !== false
    || result.stderr !== ""
    || typeof result.stdout !== "string"
    || Buffer.byteLength(result.stdout, "utf8") > OUTPUT_BYTES
    || !result.stdout.endsWith("\n")
  ) {
    fail("VISUAL_ACTION_COMMIT_UNKNOWN");
  }
  const lines = result.stdout.trimEnd().split("\n");
  if (lines.length !== 2) fail("VISUAL_ACTION_COMMIT_UNKNOWN");
  let initialized;
  let called;
  try {
    [initialized, called] = lines.map((line) => JSON.parse(line));
  } catch {
    fail("VISUAL_ACTION_COMMIT_UNKNOWN");
  }
  if (
    !exactKeys(initialized, ["id", "jsonrpc", "result"])
    || initialized.jsonrpc !== "2.0"
    || initialized.id !== 1
    || !exactKeys(
      initialized.result,
      ["capabilities", "protocolVersion", "serverInfo"],
    )
    || initialized.result.protocolVersion !== protocolVersion
    || !exactKeys(initialized.result.capabilities, ["tools"])
    || !exactKeys(initialized.result.capabilities.tools, [])
    || !exactKeys(initialized.result.serverInfo, ["name", "version"])
    || initialized.result.serverInfo.name !== "aactl"
    || !exactKeys(called, ["id", "jsonrpc", "result"])
    || called.jsonrpc !== "2.0"
    || called.id !== 2
    || !exactKeys(called.result, ["content", "structuredContent"])
    || (
      called.result.content !== null
      && (
        !Array.isArray(called.result.content)
        || called.result.content.length > 1
      )
    )
  ) {
    fail("VISUAL_ACTION_COMMIT_UNKNOWN");
  }
  const value = called.result.structuredContent;
  const required = [
    "actionCommits",
    "commitStatus",
    "succeeded",
    "verified",
  ];
  const optional = [
    "deviceFingerprint",
    "errorCode",
    "observationId",
    "route",
    "screenFingerprint",
  ];
  if (
    value === null
    || typeof value !== "object"
    || Array.isArray(value)
    || required.some((key) => !Object.hasOwn(value, key))
    || Object.keys(value).some(
      (key) => !required.includes(key) && !optional.includes(key),
    )
    || typeof value.succeeded !== "boolean"
    || typeof value.verified !== "boolean"
    || !["not_committed", "committed", "unknown"].includes(value.commitStatus)
    || (
      value.commitStatus === "committed"
      && (
        value.succeeded !== true
        || value.verified !== true
        || value.actionCommits !== 1
        || !stableId.test(value.route ?? "")
        || !stableId.test(value.observationId ?? "")
        || !fingerprint.test(value.deviceFingerprint ?? "")
        || !fingerprint.test(value.screenFingerprint ?? "")
        || Object.hasOwn(value, "errorCode")
      )
    )
    || (
      value.commitStatus === "not_committed"
      && (
        value.succeeded !== false
        || value.actionCommits !== 0
        || typeof value.errorCode !== "string"
      )
    )
    || (
      value.commitStatus === "unknown"
      && (
        value.succeeded !== false
        || value.actionCommits !== null
        || typeof value.errorCode !== "string"
      )
    )
  ) {
    fail("VISUAL_ACTION_COMMIT_UNKNOWN");
  }
  if (Array.isArray(called.result.content) && called.result.content.length === 1) {
    const content = called.result.content[0];
    if (
      !exactKeys(content, ["text", "type"])
      || content.type !== "text"
      || typeof content.text !== "string"
    ) {
      fail("VISUAL_ACTION_COMMIT_UNKNOWN");
    }
    let unstructured;
    try {
      unstructured = JSON.parse(content.text);
    } catch {
      fail("VISUAL_ACTION_COMMIT_UNKNOWN");
    }
    if (JSON.stringify(unstructured) !== JSON.stringify(value)) {
      fail("VISUAL_ACTION_COMMIT_UNKNOWN");
    }
  }
  if (
    value.deviceFingerprint !== undefined
    && value.deviceFingerprint !== expectedFingerprint
  ) {
    fail("VISUAL_RESULT_IDENTITY_DRIFT");
  }
  return value;
};

export const executeMcpProcess = async ({
  executable,
  argv,
  stdin,
  options,
}) => new Promise((resolve, reject) => {
  const child = nodeSpawn(executable, argv, {
    shell: options.shell,
    windowsHide: options.windowsHide,
    stdio: ["pipe", "pipe", "pipe"],
  });
  const stdout = [];
  const stderr = [];
  let stdoutBytes = 0;
  let stderrBytes = 0;
  let timedOut = false;
  let settled = false;
  let timer = null;
  const finish = (operation) => {
    if (settled) return;
    settled = true;
    if (timer !== null) clearTimeout(timer);
    operation();
  };
  const collect = (chunks, chunk, current) => {
    const next = current + chunk.length;
    if (next > options.maxBuffer) {
      child.kill();
      finish(() => reject(
        new AttestedVisualAdapterError("VISUAL_ACTION_COMMIT_UNKNOWN"),
      ));
      return current;
    }
    chunks.push(Buffer.from(chunk));
    return next;
  };
  child.stdout.on("data", (chunk) => {
    stdoutBytes = collect(stdout, chunk, stdoutBytes);
  });
  child.stderr.on("data", (chunk) => {
    stderrBytes = collect(stderr, chunk, stderrBytes);
  });
  child.once("error", () => {
    finish(() => reject(
      new AttestedVisualAdapterError("VISUAL_ACTION_COMMIT_UNKNOWN"),
    ));
  });
  child.once("close", (code) => {
    finish(() => resolve({
      code,
      stdout: Buffer.concat(stdout).toString("utf8"),
      stderr: Buffer.concat(stderr).toString("utf8"),
      timedOut,
    }));
  });
  timer = setTimeout(() => {
    timedOut = true;
    child.kill();
  }, options.timeoutMs);
  child.stdin.once("error", () => {
    child.kill();
    finish(() => reject(
      new AttestedVisualAdapterError("VISUAL_ACTION_COMMIT_UNKNOWN"),
    ));
  });
  child.stdin.end(stdin);
});

export const createAttestedVisualScenarioPorts = async (
  config,
  dependencies = {
    executeMcp: executeMcpProcess,
    now: () => new Date().toISOString(),
  },
) => {
  assertConfig(config, dependencies);
  const tool = await bindExecutable(config.executable, config.repositoryRoot);
  const allowedPackages = new Set(config.targetPackages);
  const tokens = new WeakMap();
  const consumed = new WeakSet();
  let generation = 0;

  const router = {
    resolve: async (request) => {
      if (!request.allowedRoutes?.includes("visual")) {
        return config.basePorts.router.resolve(request);
      }
      if (
        !exactKeys(request, [
          "action",
          "allowedRoutes",
          "dynamicRegions",
          "minimumScore",
          "observation",
          "targetPackage",
        ])
        || !allowedPackages.has(request.targetPackage)
        || !Array.isArray(request.allowedRoutes)
        || request.allowedRoutes.length !== 1
        || request.allowedRoutes[0] !== "visual"
        || !Array.isArray(request.dynamicRegions)
        || request.dynamicRegions.length !== 0
        || typeof request.minimumScore !== "number"
        || request.minimumScore > 1
        || request.minimumScore < 0
        || request.minimumScore > 1
        || !exactKeys(request.observation, [
          "expiresAt",
          "foregroundPackage",
          "observationId",
          "observedAt",
          "pageClass",
        ])
        || request.observation.foregroundPackage !== request.targetPackage
        || request.observation.pageClass !== "normal"
        || !stableId.test(request.observation.observationId ?? "")
        || Date.parse(request.observation.expiresAt)
          <= Date.parse(dependencies.now())
      ) {
        fail("VISUAL_ROUTE_UNAVAILABLE");
      }
      assertAction(request.action);
      const token = Object.freeze({});
      tokens.set(token, Object.freeze({
        action: request.action,
        generation,
        observationId: request.observation.observationId,
        targetPackage: request.targetPackage,
      }));
      return Object.freeze({
        route: "visual",
        score: 1,
        attempts: 1,
        observationId: request.observation.observationId,
        token,
      });
    },
  };

  const executor = {
    execute: async (request) => {
      if (request.route !== "visual") {
        return config.basePorts.executor.execute(request);
      }
      if (
        !exactKeys(request, [
          "action",
          "deadlineAt",
          "observationId",
          "route",
          "routeToken",
          "targetPackage",
          "value",
        ].filter((key) => key !== "value" || Object.hasOwn(request, key)))
        || request.routeToken === null
        || typeof request.routeToken !== "object"
        || !allowedPackages.has(request.targetPackage)
        || request.value !== undefined
      ) {
        fail("VISUAL_EXECUTION_REQUEST_INVALID");
      }
      if (consumed.has(request.routeToken)) {
        fail("VISUAL_ROUTE_TOKEN_CONSUMED");
      }
      const record = tokens.get(request.routeToken);
      if (
        record === undefined
        || record.generation !== generation
        || record.observationId !== request.observationId
        || record.targetPackage !== request.targetPackage
        || JSON.stringify(record.action) !== JSON.stringify(request.action)
      ) {
        fail("VISUAL_ROUTE_TOKEN_INVALID");
      }
      consumed.add(request.routeToken);
      tokens.delete(request.routeToken);
      const timeoutMs = Date.parse(request.deadlineAt)
        - Date.parse(dependencies.now());
      if (!Number.isFinite(timeoutMs) || timeoutMs < 1) {
        return Object.freeze({ committed: false });
      }
      await assertExecutableUnchanged(tool);
      const stdin = createMcpInput(
        config.serial,
        request.targetPackage,
        request.action,
      );
      let result;
      try {
        result = await dependencies.executeMcp({
          executable: tool.path,
          argv: ["mcp", "serve"],
          stdin,
          options: {
            maxBuffer: OUTPUT_BYTES,
            shell: false,
            timeoutMs: Math.min(timeoutMs, 30_000),
            windowsHide: true,
          },
        });
      } catch {
        fail("VISUAL_ACTION_COMMIT_UNKNOWN");
      }
      const output = parseMcpOutput(result, config.deviceFingerprint);
      if (output.commitStatus === "unknown") {
        fail("VISUAL_ACTION_COMMIT_UNKNOWN");
      }
      return Object.freeze({
        committed: output.commitStatus === "committed",
      });
    },
  };

  const lifecycle = Object.freeze(Object.fromEntries(
    basePortMethods.lifecycle.map((method) => [
      method,
      async (context) => {
        generation += 1;
        return config.basePorts.lifecycle[method](context);
      },
    ]),
  ));

  return Object.freeze({
    ...config.basePorts,
    router: Object.freeze(router),
    executor: Object.freeze(executor),
    lifecycle,
  });
};
