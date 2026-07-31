// 测试用途：验证离线 APK inspector 只执行可信 Android 工具的固定 argv，并对歧义元数据失败关闭。
import assert from "node:assert/strict";
import {
  chmod,
  mkdir,
  mkdtemp,
  realpath,
  rename,
  symlink,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  APK_INSPECTOR_LIMITS,
  createAndroidApkInspector,
} from "../src/apk-inspector.mjs";
import { ExternalAppError } from "../src/manifest.mjs";
import { verifyExternalAppArtifact } from "../src/verifier.mjs";
import {
  baseManifest,
  writeCacheArtifact,
} from "./helpers.mjs";

const AAPT2_OUTPUT = [
  "package: name='com.example.compatibility' versionCode='30201' versionName='3.2.1' compileSdkVersion='35'",
  "sdkVersion:'26'",
  "targetSdkVersion:'35'",
  "native-code: 'arm64-v8a'",
].join("\n");
const APKSIGNER_OUTPUT = [
  "Signer #1 certificate DN: CN=Compatibility Fixture",
  `Signer #1 certificate SHA-256 digest: ${"AA:".repeat(31)}AA`,
  "Signer #1 certificate SHA-1 digest: 00",
].join("\n");

const rejectsCode = async (operation, code) => {
  await assert.rejects(operation, (error) => {
    assert.ok(error instanceof ExternalAppError);
    assert.equal(error.code, code);
    assert.equal(error.message, code);
    return true;
  });
};

const makeFixture = async () => {
  const root = await realpath(
    await mkdtemp(path.join(os.tmpdir(), "n46-inspector-")),
  );
  const repositoryRoot = path.join(root, "repository");
  const buildToolsDirectory = path.join(root, "sdk", "build-tools", "35.0.0");
  const cacheRoot = path.join(root, "external-cache");
  await mkdir(repositoryRoot);
  await mkdir(buildToolsDirectory, { recursive: true });
  await mkdir(path.join(buildToolsDirectory, "lib"));
  await mkdir(cacheRoot);
  const aapt2Path = path.join(buildToolsDirectory, "aapt2");
  const apksignerJarPath = path.join(
    buildToolsDirectory,
    "lib",
    "apksigner.jar",
  );
  const javaPath = path.join(root, "jdk", "bin", "java");
  await mkdir(path.dirname(javaPath), { recursive: true });
  await writeFile(aapt2Path, "fake aapt2\n");
  await writeFile(apksignerJarPath, "fake apksigner jar\n");
  await writeFile(javaPath, "fake java\n");
  await chmod(aapt2Path, 0o700);
  await chmod(javaPath, 0o700);
  const artifactPath = path.join(cacheRoot, "artifact;--fake-option");
  await writeFile(artifactPath, "not an apk\n");
  return {
    root,
    repositoryRoot,
    buildToolsDirectory,
    cacheRoot,
    aapt2Path,
    apksignerJarPath,
    javaPath,
    artifactPath,
  };
};

const fakeExecFile = ({
  aapt2Output = AAPT2_OUTPUT,
  aapt2Stderr = "",
  apksignerOutput = APKSIGNER_OUTPUT,
  onCall,
} = {}) => {
  const calls = [];
  const execFile = (executable, argv, options, callback) => {
    const call = { executable, argv, options };
    calls.push(call);
    Promise.resolve(onCall?.(call, calls.length))
      .then(() => {
        callback(
          null,
          path.basename(executable) === "aapt2"
            ? aapt2Output
            : apksignerOutput,
          path.basename(executable) === "aapt2" ? aapt2Stderr : "",
        );
      })
      .catch(callback);
    return Object.freeze({ kill: () => true });
  };
  return { calls, execFile };
};

const inspectRequest = (artifactPath) => ({
  artifactPath,
  sizeBytes: 11,
  sha256: "b".repeat(64),
});

test("build-tools 目录只产生 aapt2 与 apksigner 的固定 argv", async () => {
  const fixture = await makeFixture();
  const fake = fakeExecFile();
  const inspector = await createAndroidApkInspector(
    {
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      javaPath: fixture.javaPath,
    },
    { execFile: fake.execFile },
  );

  const result = await inspector.inspect(inspectRequest(fixture.artifactPath));

  assert.deepEqual(result, {
    package: "com.example.compatibility",
    version: "3.2.1",
    versionCode: 30201,
    abi: ["arm64-v8a"],
    minSdk: 26,
    signingCertificateSha256: "a".repeat(64),
  });
  assert.ok(Object.isFrozen(result));
  assert.deepEqual(
    fake.calls.map(({ executable, argv }) => [
      executable,
      argv,
    ]),
    [
      [
        fixture.aapt2Path,
        ["dump", "badging", fixture.artifactPath],
      ],
      [
        fixture.javaPath,
        [
          "-Xmx256M",
          "-jar",
          fixture.apksignerJarPath,
          "verify",
          "--print-certs",
          fixture.artifactPath,
        ],
      ],
    ],
  );
  for (const { options } of fake.calls) {
    assert.deepEqual(options, {
      encoding: "utf8",
      maxBuffer: APK_INSPECTOR_LIMITS.outputBytes,
      shell: false,
      timeout: APK_INSPECTOR_LIMITS.timeoutMs,
      windowsHide: true,
    });
  }
});

test("现代 minSdkVersion badging 与旧 sdkVersion 严格等价", async () => {
  const fixture = await makeFixture();
  const modern = fakeExecFile({
    aapt2Output: AAPT2_OUTPUT.replace(
      "sdkVersion:'26'",
      "minSdkVersion:'26'",
    ),
  });
  const inspector = await createAndroidApkInspector(
    {
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      javaPath: fixture.javaPath,
    },
    { execFile: modern.execFile },
  );

  const result = await inspector.inspect(inspectRequest(fixture.artifactPath));

  assert.equal(result.minSdk, 26);
});

test("有界 aapt2 资源 warning 不掩盖严格 badging 元数据", async () => {
  const fixture = await makeFixture();
  const fake = fakeExecFile({
    aapt2Stderr: "warning: missing optional resource\n".repeat(15_000),
  });
  const inspector = await createAndroidApkInspector(
    {
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      javaPath: fixture.javaPath,
    },
    { execFile: fake.execFile },
  );

  const result = await inspector.inspect(inspectRequest(fixture.artifactPath));

  assert.equal(result.package, "com.example.compatibility");
});

test("固定工具路径模式不接受任意 executable、argv 或混合配置", async () => {
  const fixture = await makeFixture();
  const fake = fakeExecFile();
  const inspector = await createAndroidApkInspector(
    {
      repositoryRoot: fixture.repositoryRoot,
      aapt2Path: fixture.aapt2Path,
      apksignerJarPath: fixture.apksignerJarPath,
      javaPath: fixture.javaPath,
    },
    { execFile: fake.execFile },
  );
  await inspector.inspect(inspectRequest(fixture.artifactPath));
  assert.equal(fake.calls.length, 2);

  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      executable: fixture.aapt2Path,
      argv: ["dump", "badging"],
    }),
    "APK_INSPECTOR_CONFIG_INVALID",
  );
  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      aapt2Path: fixture.aapt2Path,
      apksignerJarPath: fixture.apksignerJarPath,
      javaPath: fixture.javaPath,
    }),
    "APK_INSPECTOR_CONFIG_INVALID",
  );
});

test("拒绝相对路径、仓库内工具、错误工具名和 symlink", async () => {
  const fixture = await makeFixture();
  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: "relative/build-tools",
      javaPath: fixture.javaPath,
    }),
    "APK_INSPECTOR_TOOL_PATH_INVALID",
  );

  const repositoryTools = path.join(fixture.repositoryRoot, "tools");
  await mkdir(repositoryTools);
  for (const name of ["aapt2", "apksigner.jar", "java"]) {
    const toolPath = path.join(repositoryTools, name);
    await writeFile(toolPath, "repository executable\n");
    await chmod(toolPath, 0o700);
  }
  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      aapt2Path: path.join(repositoryTools, "aapt2"),
      apksignerJarPath: path.join(repositoryTools, "apksigner.jar"),
      javaPath: path.join(repositoryTools, "java"),
    }),
    "APK_INSPECTOR_TOOL_UNSAFE",
  );

  const renamedTool = path.join(path.dirname(fixture.aapt2Path), "custom-tool");
  await writeFile(renamedTool, "renamed executable\n");
  await chmod(renamedTool, 0o700);
  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      aapt2Path: renamedTool,
      apksignerJarPath: fixture.apksignerJarPath,
      javaPath: fixture.javaPath,
    }),
    "APK_INSPECTOR_TOOL_PATH_INVALID",
  );

  const realAapt2 = path.join(path.dirname(fixture.aapt2Path), "real-aapt2");
  await rename(fixture.aapt2Path, realAapt2);
  await symlink(realAapt2, fixture.aapt2Path);
  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      javaPath: fixture.javaPath,
    }),
    "APK_INSPECTOR_TOOL_UNSAFE",
  );

  const linkedSdk = path.join(fixture.root, "linked-sdk");
  await symlink(path.join(fixture.root, "sdk"), linkedSdk, "dir");
  await rejectsCode(
    () => createAndroidApkInspector({
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: path.join(
        linkedSdk,
        "build-tools",
        "35.0.0",
      ),
      javaPath: fixture.javaPath,
    }),
    "APK_INSPECTOR_TOOL_UNSAFE",
  );
});

test("工具在检查后被替换时停止第二个工具且不泄露路径", async () => {
  const fixture = await makeFixture();
  const replacement = path.join(path.dirname(fixture.aapt2Path), "replacement");
  await writeFile(replacement, "replacement aapt2\n");
  await chmod(replacement, 0o700);
  const fake = fakeExecFile({
    onCall: async (_call, number) => {
      if (number === 1) {
        await rename(replacement, fixture.aapt2Path);
      }
    },
  });
  const inspector = await createAndroidApkInspector(
    {
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      javaPath: fixture.javaPath,
    },
    { execFile: fake.execFile },
  );

  await rejectsCode(
    () => inspector.inspect(inspectRequest(fixture.artifactPath)),
    "APK_INSPECTOR_TOOL_CHANGED",
  );
  assert.equal(fake.calls.length, 1);
});

test("固定 Java 或 apksigner jar 被替换时失败关闭", async () => {
  for (const target of ["java", "jar"]) {
    const fixture = await makeFixture();
    const targetPath = target === "java"
      ? fixture.javaPath
      : fixture.apksignerJarPath;
    const replacement = path.join(
      path.dirname(targetPath),
      `${path.basename(targetPath)}.replacement`,
    );
    await writeFile(replacement, `replacement ${target}\n`);
    if (target === "java") await chmod(replacement, 0o700);
    const fake = fakeExecFile({
      onCall: async (_call, number) => {
        if (number === 1) await rename(replacement, targetPath);
      },
    });
    const inspector = await createAndroidApkInspector(
      {
        repositoryRoot: fixture.repositoryRoot,
        buildToolsDirectory: fixture.buildToolsDirectory,
        javaPath: fixture.javaPath,
      },
      { execFile: fake.execFile },
    );

    await rejectsCode(
      () => inspector.inspect(inspectRequest(fixture.artifactPath)),
      "APK_INSPECTOR_TOOL_CHANGED",
    );
    assert.equal(fake.calls.length, 1);
  }
});

test("timeout、输出预算和工具失败映射为稳定错误码", async () => {
  for (const [failure, code] of [
    [
      Object.assign(new Error("private timeout path"), {
        killed: true,
        signal: "SIGTERM",
      }),
      "APK_INSPECTOR_TIMEOUT",
    ],
    [
      Object.assign(new Error("private max buffer output"), {
        code: "ERR_CHILD_PROCESS_STDIO_MAXBUFFER",
      }),
      "APK_INSPECTOR_OUTPUT_LIMIT",
    ],
    [
      Object.assign(new Error("private tool stderr"), { code: 1 }),
      "APK_INSPECTOR_TOOL_FAILED",
    ],
  ]) {
    const fixture = await makeFixture();
    const execFile = (_executable, _argv, _options, callback) => {
      queueMicrotask(() => callback(failure, "", "private output"));
      return Object.freeze({ kill: () => true });
    };
    const inspector = await createAndroidApkInspector(
      {
        repositoryRoot: fixture.repositoryRoot,
        buildToolsDirectory: fixture.buildToolsDirectory,
        javaPath: fixture.javaPath,
      },
      { execFile },
    );
    await rejectsCode(
      () => inspector.inspect(inspectRequest(fixture.artifactPath)),
      code,
    );
  }

  const fixture = await makeFixture();
  const oversized = fakeExecFile({
    aapt2Output: "x".repeat(APK_INSPECTOR_LIMITS.outputBytes + 1),
  });
  const inspector = await createAndroidApkInspector(
    {
      repositoryRoot: fixture.repositoryRoot,
      buildToolsDirectory: fixture.buildToolsDirectory,
      javaPath: fixture.javaPath,
    },
    { execFile: oversized.execFile },
  );
  await rejectsCode(
    () => inspector.inspect(inspectRequest(fixture.artifactPath)),
    "APK_INSPECTOR_OUTPUT_LIMIT",
  );
});

test("未知、重复、缺失 badging 字段和多签名歧义均失败关闭", async () => {
  const cases = [
    {
      name: "unknown ABI",
      aapt2Output: AAPT2_OUTPUT.replace("arm64-v8a", "mips"),
    },
    {
      name: "duplicate package",
      aapt2Output: `${AAPT2_OUTPUT}\n${AAPT2_OUTPUT.split("\n")[0]}`,
    },
    {
      name: "missing minSdk",
      aapt2Output: AAPT2_OUTPUT
        .split("\n")
        .filter((line) => !line.startsWith("sdkVersion:"))
        .join("\n"),
    },
    {
      name: "ambiguous legacy and modern minSdk",
      aapt2Output: `${AAPT2_OUTPUT}\nminSdkVersion:'26'`,
    },
    {
      name: "duplicate signing digest",
      apksignerOutput: `${APKSIGNER_OUTPUT}\nSigner #1 certificate SHA-256 digest: ${"b".repeat(64)}`,
    },
    {
      name: "multiple signers",
      apksignerOutput: `${APKSIGNER_OUTPUT}\nSigner #2 certificate SHA-256 digest: ${"b".repeat(64)}`,
    },
  ];

  for (const fixtureCase of cases) {
    const fixture = await makeFixture();
    const fake = fakeExecFile(fixtureCase);
    const inspector = await createAndroidApkInspector(
      {
        repositoryRoot: fixture.repositoryRoot,
        buildToolsDirectory: fixture.buildToolsDirectory,
        javaPath: fixture.javaPath,
      },
      { execFile: fake.execFile },
    );
    await rejectsCode(
      () => inspector.inspect(inspectRequest(fixture.artifactPath)),
      "APK_INSPECTOR_OUTPUT_INVALID",
    );
  }
});

test("六项 inspector metadata mismatch 均在 descriptor 和安装前拒绝", async () => {
  const mismatchCases = [
    {
      aapt2Output: AAPT2_OUTPUT.replace(
        "com.example.compatibility",
        "com.example.unexpected",
      ),
      code: "ARTIFACT_PACKAGE_MISMATCH",
    },
    {
      aapt2Output: AAPT2_OUTPUT.replace("3.2.1", "9.9.9"),
      code: "ARTIFACT_VERSION_MISMATCH",
    },
    {
      aapt2Output: AAPT2_OUTPUT.replace("30201", "999"),
      code: "ARTIFACT_VERSION_CODE_MISMATCH",
    },
    {
      aapt2Output: AAPT2_OUTPUT.replace("arm64-v8a", "x86_64"),
      code: "ARTIFACT_ABI_MISMATCH",
    },
    {
      aapt2Output: AAPT2_OUTPUT.replace("sdkVersion:'26'", "sdkVersion:'30'"),
      code: "ARTIFACT_MIN_SDK_MISMATCH",
    },
    {
      apksignerOutput: APKSIGNER_OUTPUT.replace(
        `${"AA:".repeat(31)}AA`,
        `${"BB:".repeat(31)}BB`,
      ),
      code: "ARTIFACT_SIGNING_MISMATCH",
    },
  ];

  for (const mismatch of mismatchCases) {
    const fixture = await makeFixture();
    await writeCacheArtifact(fixture.cacheRoot);
    const fake = fakeExecFile(mismatch);
    const inspector = await createAndroidApkInspector(
      {
        repositoryRoot: fixture.repositoryRoot,
        buildToolsDirectory: fixture.buildToolsDirectory,
        javaPath: fixture.javaPath,
      },
      { execFile: fake.execFile },
    );

    await rejectsCode(
      () => verifyExternalAppArtifact({
        manifest: baseManifest(),
        cacheRoot: fixture.cacheRoot,
        repositoryRoot: fixture.repositoryRoot,
        inspector,
      }),
      mismatch.code,
    );
    assert.equal(fake.calls.length, 2);
  }
});
