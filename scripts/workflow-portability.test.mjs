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
