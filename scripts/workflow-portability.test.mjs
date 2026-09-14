// 脚本用途：验证 GitHub Actions 的 Windows 格式检查、可移植矩阵和仓库行尾契约不会回退。
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(import.meta.dirname, "..");

test("verify workflow 使用仓库格式脚本并覆盖三平台 portability", async () => {
  const workflow = await readFile(
    path.join(repositoryRoot, ".github", "workflows", "verify.yml"),
    "utf8",
  );

  assert.equal(workflow.includes("sh ./scripts/check-go-format.sh"), true);
  assert.equal(workflow.includes("files=$(gofmt -l .)"), false);
  assert.equal(workflow.includes("name: Portable Node (${{ matrix.os }})"), true);
  for (const platform of ["ubuntu-latest", "windows-latest", "macos-latest"]) {
    assert.equal(workflow.includes(`- ${platform}`), true);
  }
  assert.equal(
    workflow.includes("scripts/toolchain-environment.test.mjs"),
    true,
  );
  assert.equal(workflow.includes("scripts/workflow-portability.test.mjs"), true);
  assert.equal(workflow.includes("scripts/emulator/runner.test.mjs"), true);
  assert.equal(
    workflow.includes("scripts/native-fixture-matrix/run.test.mjs"),
    true,
  );
});

test("需要 JDK 与 Android SDK 的 job 从 osdk.toml 取版本", async () => {
  // 这些 job 曾用 setup-java 加一行手写 sdkmanager 声明同一批版本，与
  // osdk.toml 各说各话；`.tool-versions` 里那个已下架的 Java 版本就是这样长期
  // 无人发现的。断言这批 job 只有一个版本来源。
  for (const name of ["verify.yml", "release.yml"]) {
    const workflow = await readFile(
      path.join(repositoryRoot, ".github", "workflows", name),
      "utf8",
    );

    assert.equal(workflow.includes("osdk --yes trust ."), true, name);
    assert.equal(workflow.includes("osdk --yes install"), true, name);
    // 不得再出现第二处版本声明。只看真实的 YAML 步骤，不看注释——注释里正需要
    // 提到这些旧写法来说明为什么弃用。
    const steps = workflow
      .split(/\r?\n/u)
      .filter((line) => !line.trim().startsWith("#"))
      .join("\n");
    assert.equal(steps.includes("actions/setup-java"), false, name);
    assert.equal(steps.includes("android-actions/setup-android"), false, name);
    assert.equal(steps.includes("sdkmanager"), false, name);
    // Gradle wrapper 的 SHA-256 校验比任何 pin 都强，必须保留 wrapper 自身；
    // setup-gradle 仅用于依赖缓存。
    assert.equal(workflow.includes("gradle/actions/setup-gradle"), true, name);
    assert.match(workflow, /osdk\.toml/u, name);
  }
});

test("Android 构建经 osdk exec 注入 SDK 环境", async () => {
  // osdk 的 pin 只决定装哪个版本，不会把 ANDROID_HOME / JAVA_HOME 写进 shell；
  // 直接调 gradlew 会报 SDK location not found。故这些 job 必须走 make 目标，
  // 由 Makefile 统一套 osdk exec。
  const workflow = await readFile(
    path.join(repositoryRoot, ".github", "workflows", "verify.yml"),
    "utf8",
  );
  assert.equal(workflow.includes("make android-verify"), true);
  assert.equal(
    workflow.includes("./android/gradlew -p android --no-daemon"),
    false,
  );
});

test("仓库不再保留与 osdk.toml 重复的版本文件", async () => {
  // `.tool-versions` 曾同时声明 golang / java / gradle，其中 java 指向一个远端
  // 已下架的构建。重复的版本事实迟早会漂移，这里锁住"只有一个来源"。
  const { access } = await import("node:fs/promises");
  await assert.rejects(() =>
    access(path.join(repositoryRoot, ".tool-versions")));

  const script = await readFile(
    path.join(repositoryRoot, "scripts", "verify-toolchains.sh"),
    "utf8",
  );
  assert.equal(script.includes(".tool-versions"), true); // 仅存历史说明注释
  assert.equal(script.includes('assert_contains ".tool-versions"'), false);
  assert.equal(script.includes('assert_contains "osdk.toml"'), true);
});

test("gitattributes 固定源码 LF 和 Windows batch CRLF", async () => {
  const attributes = await readFile(
    path.join(repositoryRoot, ".gitattributes"),
    "utf8",
  );

  for (const pattern of [
    "*.go text eol=lf",
    "*.mjs text eol=lf",
    "*.sh text eol=lf",
    "*.yml text eol=lf",
    "android/gradlew text eol=lf",
  ]) {
    assert.equal(attributes.includes(pattern), true, pattern);
  }
  assert.equal(attributes.includes("*.bat text eol=crlf"), true);
  assert.equal(attributes.includes("*.cmd text eol=crlf"), true);
});

test("local matrix 在 Windows 保留 N47 核心且隔离 Unix executable 测试", async () => {
  const workflow = await readFile(
    path.join(repositoryRoot, ".github", "workflows", "local-api-matrix.yml"),
    "utf8",
  );
  const unixStep = workflow.match(
    /- name: Validate N47 Unix executable adapter\n(?<body>[\s\S]*?)(?=\n      - name:)/u,
  )?.groups?.body ?? "";

  assert.equal(workflow.includes("- windows-latest"), true);
  for (const portableTest of [
    "test-lab/runner/test/aggregation.test.mjs",
    "test-lab/runner/test/fixture-contract.test.mjs",
    "test-lab/runner/test/runner.test.mjs",
    "test-lab/runner/test/schema.test.mjs",
  ]) {
    assert.equal(workflow.includes(portableTest), true, portableTest);
  }
  assert.equal(
    unixStep.includes("if: runner.os != 'Windows'"),
    true,
  );
  for (const unixAdapterTest of [
    "test-lab/runner/test/aactl-adapter.test.mjs",
    "test-lab/runner/test/aactl-lifecycle.test.mjs",
    "test-lab/runner/test/aactl-semantic-ports.test.mjs",
    "test-lab/runner/test/aactl-stdin-input.test.mjs",
  ]) {
    assert.equal(unixStep.includes(unixAdapterTest), true, unixAdapterTest);
  }
  assert.equal(
    workflow.includes("npm run smoke --prefix test-lab/runner"),
    false,
  );
});
