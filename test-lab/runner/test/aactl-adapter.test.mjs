// 测试用途：验证 aactl adapter 的 executable identity、严格信封和固定进程调用边界。
import assert from "node:assert/strict";
import {
  chmod,
  mkdir,
  rename,
  symlink,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  AACTL_ADAPTER_LIMITS,
  AactlAdapterError,
  createAactlScenarioPorts,
} from "../adapters/aactl.mjs";
import {
  conditionCatalog,
  envelope,
  fakeExecFile,
  lifecycle,
  makeExecutable,
  semanticSnapshot,
  SERIAL,
  SYSTEM_PACKAGES,
  TARGET_PACKAGE,
} from "./adapter-helpers.mjs";

const rejectsCode = async (operation, code) => {
  await assert.rejects(operation, (error) => {
    assert.ok(error instanceof AactlAdapterError);
    assert.equal(error.code, code);
    assert.equal(error.message, code);
    return true;
  });
};

const makeAdapter = async ({
  executable,
  execFile,
  repositoryRoot = path.resolve(import.meta.dirname, "..", "..", ".."),
  lifecyclePort = lifecycle().port,
} = {}) => createAactlScenarioPorts(
  {
    executable,
    repositoryRoot,
    serial: SERIAL,
    observationTtlMs: 30_000,
    maxDepth: 64,
    conditionCatalog,
    systemPackages: SYSTEM_PACKAGES,
  },
  {
    execFile,
    lifecycle: lifecyclePort,
    values: {
      resolve: async () => "fixture input",
    },
    artifacts: {
      collect: async () => ({ retained: false, errorCode: null }),
    },
    now: () => "2026-07-25T12:00:00.000Z",
  },
);

test("只接受仓库外绝对普通可执行 aactl 且固定进程选项", async () => {
  const fixture = await makeExecutable();
  const fake = fakeExecFile();
  const adapter = await makeAdapter({
    executable: fixture.executable,
    execFile: fake.execFile,
  });

  await adapter.observer.observe({
    targetPackage: TARGET_PACKAGE,
    timeoutMs: 3000,
    dynamicRegions: [],
  });

  assert.equal(fake.calls.length, 1);
  assert.deepEqual(fake.calls[0], {
    executable: fixture.executable,
    argv: [
      "bridge",
      "snapshot",
      "--device",
      SERIAL,
      "--max-depth",
      "64",
      "--json",
    ],
    options: {
      encoding: "utf8",
      maxBuffer: AACTL_ADAPTER_LIMITS.outputBytes,
      shell: false,
      timeout: 3000,
      windowsHide: true,
    },
  });
});

test("拒绝相对路径、仓库内 executable、错误文件名和 symlink", async () => {
  const fixture = await makeExecutable();
  const fake = fakeExecFile();

  await rejectsCode(
    () => makeAdapter({ executable: "relative/aactl", execFile: fake.execFile }),
    "AACTL_PATH_INVALID",
  );

  const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..");
  const repositoryToolDirectory = path.join(repositoryRoot, ".tmp-n47-adapter");
  const repositoryTool = path.join(repositoryToolDirectory, "aactl");
  await mkdir(repositoryToolDirectory, { recursive: true });
  await writeFile(repositoryTool, "repository tool\n");
  await chmod(repositoryTool, 0o700);
  try {
    await rejectsCode(
      () => makeAdapter({
        executable: repositoryTool,
        execFile: fake.execFile,
      }),
      "AACTL_PATH_UNSAFE",
    );
  } finally {
    await import("node:fs/promises").then(({ rm }) =>
      rm(repositoryToolDirectory, { recursive: true, force: true }));
  }

  const renamed = path.join(fixture.root, "custom-tool");
  await writeFile(renamed, "custom\n");
  await chmod(renamed, 0o700);
  await rejectsCode(
    () => makeAdapter({ executable: renamed, execFile: fake.execFile }),
    "AACTL_PATH_INVALID",
  );

  const realTool = path.join(fixture.root, "real-aactl");
  await rename(fixture.executable, realTool);
  await symlink(realTool, fixture.executable);
  await rejectsCode(
    () => makeAdapter({
      executable: fixture.executable,
      execFile: fake.execFile,
    }),
    "AACTL_PATH_UNSAFE",
  );
});

test("创建后 executable 原子替换或修改时调用数保持零", async () => {
  for (const replacement of ["inode", "content"]) {
    const fixture = await makeExecutable();
    const fake = fakeExecFile();
    const adapter = await makeAdapter({
      executable: fixture.executable,
      execFile: fake.execFile,
    });
    if (replacement === "inode") {
      const next = path.join(fixture.root, "replacement");
      await writeFile(next, "fixed aactl executable\n");
      await chmod(next, 0o700);
      await rename(next, fixture.executable);
    } else {
      await writeFile(fixture.executable, "changed executable bytes\n");
      await chmod(fixture.executable, 0o700);
    }

    await rejectsCode(
      () => adapter.observer.observe({
        targetPackage: TARGET_PACKAGE,
        timeoutMs: 3000,
        dynamicRegions: [],
      }),
      "AACTL_CHANGED",
    );
    assert.equal(fake.calls.length, 0);
  }
});

test("严格拒绝重复 key、尾随 JSON、成功 stderr、超限和信封矛盾", async () => {
  const cases = [
    {
      stdout: `{"schemaVersion":"1.0","requestId":"a","ok":true,"ok":false,"data":{},"error":null,"meta":{"durationMs":1}}\n`,
      stderr: "",
    },
    {
      stdout: `${envelope({ data: semanticSnapshot() })}\n${envelope()}\n`,
      stderr: "",
    },
    {
      stdout: `${envelope({ data: semanticSnapshot() })}\n`,
      stderr: "unexpected warning",
    },
    {
      stdout: "x".repeat(AACTL_ADAPTER_LIMITS.outputBytes + 1),
      stderr: "",
    },
    {
      stdout: `${JSON.stringify({
        schemaVersion: "1.0",
        requestId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
        ok: true,
        data: {},
        error: {
          code: "INTERNAL_ERROR",
          message: "contradiction",
          retryable: false,
        },
        meta: { durationMs: 1 },
      })}\n`,
      stderr: "",
    },
  ];

  for (const output of cases) {
    const fixture = await makeExecutable();
    const fake = fakeExecFile({
      onCall: () => output,
    });
    const adapter = await makeAdapter({
      executable: fixture.executable,
      execFile: fake.execFile,
    });
    await rejectsCode(
      () => adapter.observer.observe({
        targetPackage: TARGET_PACKAGE,
        timeoutMs: 3000,
        dynamicRegions: [],
      }),
      "AACTL_ENVELOPE_INVALID",
    );
  }
});

test("CLI 明确失败保留稳定 code 而不暴露自由消息", async () => {
  const fixture = await makeExecutable();
  const fake = fakeExecFile({
    onCall: () => ({
      stdout: `${envelope({
        ok: false,
        error: {
          code: "AUTH_REQUIRED",
          message: "private bridge details",
          retryable: false,
        },
      })}\n`,
      stderr: "",
    }),
  });
  const adapter = await makeAdapter({
    executable: fixture.executable,
    execFile: fake.execFile,
  });

  await rejectsCode(
    () => adapter.observer.observe({
      targetPackage: TARGET_PACKAGE,
      timeoutMs: 3000,
      dynamicRegions: [],
    }),
    "AACTL_AUTH_REQUIRED",
  );
});

test("CLI 非零退出但带合法失败信封时保留明确拒绝", async () => {
  const fixture = await makeExecutable();
  const fake = fakeExecFile({
    onCall: () => ({
      error: Object.assign(new Error("exit 6"), { code: 6 }),
      stdout: `${envelope({
        ok: false,
        error: {
          code: "ACTION_NOT_ALLOWED",
          message: "private action details",
          retryable: false,
        },
      })}\n`,
      stderr: "",
    }),
  });
  const adapter = await makeAdapter({
    executable: fixture.executable,
    execFile: fake.execFile,
  });

  await rejectsCode(
    () => adapter.observer.observe({
      targetPackage: TARGET_PACKAGE,
      timeoutMs: 3000,
      dynamicRegions: [],
    }),
    "AACTL_ACTION_NOT_ALLOWED",
  );
});
