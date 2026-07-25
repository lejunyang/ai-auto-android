// 测试用途：构造固定 aactl executable、CLI 信封和语义树，隔离验证 production adapter。
import {
  chmod,
  mkdir,
  mkdtemp,
  realpath,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export const SERIAL = "emulator-5554";
export const TARGET_PACKAGE = "dev.aiauto.fixture";
export const SECRET_INPUT = "private adapter input";
export const SYSTEM_PACKAGES = Object.freeze([
  "com.android.launcher",
  "com.android.systemui",
]);

export const makeExecutable = async () => {
  const root = await realpath(
    await mkdtemp(path.join(os.tmpdir(), "n47-aactl-adapter-")),
  );
  const executable = path.join(root, "aactl");
  await writeFile(executable, "fixed aactl executable\n");
  await chmod(executable, 0o700);
  return { root, executable };
};

const state = (overrides = {}) => ({
  checkable: false,
  checked: false,
  clickable: false,
  enabled: true,
  editable: false,
  focusable: false,
  focused: false,
  longClickable: false,
  password: false,
  scrollable: false,
  selected: false,
  visibleToUser: true,
  sensitive: false,
  ...overrides,
});

const node = ({
  name,
  text,
  actions = [],
  stateOverrides = {},
  children = [],
  packageName = TARGET_PACKAGE,
} = {}) => ({
  packageName,
  className: "android.view.View",
  ...(text === undefined ? {} : { text }),
  ...(name === undefined ? {} : { contentDescription: name }),
  bounds: {
    left: 0,
    top: 0,
    right: 100,
    bottom: 100,
  },
  actions,
  state: state(stateOverrides),
  children,
});

export const semanticSnapshot = ({
  packageName = TARGET_PACKAGE,
  labels = [],
  duplicateClick = false,
  sensitiveClick = false,
} = {}) => ({
  root: node({
    packageName,
    name: "Fixture page state",
    text: "PAGE:MAIN",
    children: [
      node({
        packageName,
        name: "Fixture click target",
        actions: ["click"],
        stateOverrides: {
          clickable: true,
          sensitive: sensitiveClick,
        },
      }),
      ...(duplicateClick
        ? [
          node({
            packageName,
            name: "Fixture click target",
            actions: ["click"],
            stateOverrides: { clickable: true },
          }),
        ]
        : []),
      node({
        packageName,
        name: "Fixture text input",
        actions: ["setText"],
        stateOverrides: { editable: true },
      }),
      node({
        packageName,
        name: "Fixture long press target",
        actions: ["longClick"],
        stateOverrides: { longClickable: true },
      }),
      node({
        packageName,
        name: "Fixture vertical scroll target",
        actions: ["scrollDown", "scrollUp"],
        stateOverrides: { scrollable: true },
      }),
      ...labels.map((label) => node({
        packageName,
        text: label,
      })),
    ],
  }),
  maxDepth: 64,
});

let requestCounter = 0;

export const envelope = ({
  ok = true,
  data = {},
  error = null,
  requestId,
} = {}) => {
  requestCounter += 1;
  return JSON.stringify({
    schemaVersion: "1.0",
    requestId: requestId
      ?? `018f47a2-4bc8-7f31-8b9a-${String(requestCounter).padStart(12, "0")}`,
    ok,
    data: ok ? data : null,
    error: ok
      ? null
      : error ?? {
        code: "ACTION_NOT_ALLOWED",
        message: "Action was rejected.",
        retryable: false,
      },
    meta: {
      durationMs: 1,
    },
  });
};

export const fakeExecFile = ({
  snapshots = [semanticSnapshot()],
  actionData = {
    route: "nodeAction",
    matchedPath: [0],
    matchScore: 1,
  },
  onCall,
} = {}) => {
  const calls = [];
  let snapshotIndex = 0;
  const execFile = (executable, argv, options, callback) => {
    const call = { executable, argv, options };
    calls.push(call);
    Promise.resolve(onCall?.(call, calls.length))
      .then((override) => {
        if (override !== undefined) {
          callback(
            override.error ?? null,
            override.stdout ?? "",
            override.stderr ?? "",
          );
          return;
        }
        const isSnapshot = argv[0] === "bridge" && argv[1] === "snapshot";
        const isLaunch = argv[0] === "action" && argv[1] === "launch";
        const protocolAction = argv[0] === "bridge" && argv[1] === "action"
          ? JSON.parse(argv[5])
          : null;
        const resolvedActionData = protocolAction === null
          ? actionData
          : ["ui.back", "ui.home", "ui.recents"].includes(protocolAction.type)
            ? { route: "globalAction" }
            : actionData;
        callback(
          null,
          `${envelope({
            data: isSnapshot
              ? snapshots[Math.min(snapshotIndex++, snapshots.length - 1)]
              : isLaunch
                ? { device: SERIAL, action: "app.launch" }
                : resolvedActionData,
          })}\n`,
          "",
        );
      })
      .catch(callback);
    return Object.freeze({ kill: () => true });
  };
  return { calls, execFile };
};

export const conditionCatalog = Object.freeze({
  "main-page": Object.freeze({
    kind: "semantic-state",
    package: TARGET_PACKAGE,
    target: Object.freeze({ name: "Fixture page state" }),
    operator: "equals",
    expected: "PAGE:MAIN",
  }),
  "click-present": Object.freeze({
    kind: "node-present",
    package: TARGET_PACKAGE,
    target: Object.freeze({ name: "Fixture click target" }),
  }),
  "missing-node": Object.freeze({
    kind: "node-absent",
    package: TARGET_PACKAGE,
    target: Object.freeze({ name: "Not present" }),
  }),
  "target-foreground": Object.freeze({
    kind: "foreground-package",
    package: TARGET_PACKAGE,
  }),
});

export const lifecycle = () => {
  const calls = [];
  return {
    calls,
    port: {
      stopScenario: async (context) => calls.push(["stopScenario", context]),
      closeBridge: async (context) => calls.push(["closeBridge", context]),
      clearAppData: async (context) => calls.push(["clearAppData", context]),
      restoreSnapshot: async (context) => calls.push(["restoreSnapshot", context]),
    },
  };
};
