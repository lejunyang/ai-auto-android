// 测试用途：验证 APK 忽略规则及工作区和 Git 历史扫描会拒绝扩展名伪装的提交制品。
import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  assertApkIgnored,
  scanRepositoryForApk,
} from "../src/repository-scan.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../..",
);

test("根 .gitignore 明确覆盖 APK 且测试 fixture 不使用 APK 扩展名", async () => {
  await assert.doesNotReject(() => assertApkIgnored(repositoryRoot));
  const gitignore = await readFile(path.join(repositoryRoot, ".gitignore"), "utf8");
  assert.match(gitignore, /(?:^|\n)\*\.apk(?:\n|$)/u);
});

test("扫描当前工作区与全部 Git 历史时没有 APK 路径", async () => {
  const result = await scanRepositoryForApk({ repositoryRoot });
  assert.deepEqual(result, {
    workspaceMatches: [],
    historyMatches: [],
  });
});

test("工作区扫描包含被 gitignore 忽略的 APK 路径", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "n46-scan-"));
  await writeFile(path.join(root, ".gitignore"), "*.apk\n", "utf8");
  const result = await scanRepositoryForApk({
    repositoryRoot: root,
    readHistory: async () => "",
    readWorkspace: async () => ["safe.input", "ignored.apk"],
  });
  assert.deepEqual(result.workspaceMatches, ["ignored.apk"]);
});

test("历史扫描大小写不敏感地拒绝 APK、APKS、AAB 和 XAPK", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "n46-scan-"));
  await writeFile(path.join(root, ".gitignore"), "*.apk\n", "utf8");
  const result = await scanRepositoryForApk({
    repositoryRoot: root,
    readWorkspace: async () => [],
    readHistory: async () =>
      "safe.txt\0legacy/App.APK\0bundle.apks\0release.aab\0vendor.xapk\0",
  });
  assert.deepEqual(result.historyMatches, [
    "bundle.apks",
    "legacy/App.APK",
    "release.aab",
    "vendor.xapk",
  ]);
});
