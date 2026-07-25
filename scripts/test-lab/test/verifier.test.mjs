// 测试用途：验证仓库外缓存、文件摘要和 inspector 元数据在安装调用前严格失败关闭。
import assert from "node:assert/strict";
import {
  mkdir,
  mkdtemp,
  realpath,
  symlink,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { ExternalAppError } from "../src/manifest.mjs";
import { verifyExternalAppArtifact } from "../src/verifier.mjs";
import {
  CONTENT,
  baseManifest,
  fakeInspector,
  inspectedMetadata,
  sha256,
  writeCacheArtifact,
} from "./helpers.mjs";

const rejectsCode = async (operation, code) => {
  await assert.rejects(operation, (error) => {
    assert.ok(error instanceof ExternalAppError);
    assert.equal(error.code, code);
    return true;
  });
};

const makeRoots = async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "n46-verifier-"));
  const repositoryRoot = path.join(root, "repository");
  const cacheRoot = path.join(root, "external-cache");
  await mkdir(repositoryRoot);
  await mkdir(cacheRoot);
  return { root, repositoryRoot, cacheRoot };
};

test("正确制品返回冻结 descriptor 且 inspector 仅收到固定验证字段", async () => {
  const { repositoryRoot, cacheRoot } = await makeRoots();
  const manifest = baseManifest();
  const artifactPath = await writeCacheArtifact(cacheRoot);
  const canonicalArtifactPath = await realpath(artifactPath);
  const inspector = fakeInspector();

  const descriptor = await verifyExternalAppArtifact({
    manifest,
    cacheRoot,
    repositoryRoot,
    inspector,
  });

  assert.deepEqual(descriptor, {
    kind: "verified-external-app",
    schemaVersion: "1.0",
    artifactPath: canonicalArtifactPath,
    package: manifest.package,
    version: manifest.version,
    versionCode: manifest.versionCode,
    abi: manifest.abi,
    minSdk: manifest.minSdk,
    source: manifest.source,
    licenseNote: manifest.licenseNote,
    sizeBytes: manifest.sizeBytes,
    sha256: manifest.sha256,
    signingCertificateSha256: manifest.signingCertificateSha256,
  });
  assert.ok(Object.isFrozen(descriptor));
  assert.deepEqual(Object.keys(inspector.calls[0]).sort(), [
    "artifactPath",
    "sha256",
    "sizeBytes",
  ]);
});

test("cache 根必须显式、绝对且位于仓库外", async () => {
  const { repositoryRoot, cacheRoot } = await makeRoots();
  const manifest = baseManifest();
  const inspector = fakeInspector();
  await writeCacheArtifact(cacheRoot);

  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      repositoryRoot,
      inspector,
    }),
    "CACHE_ROOT_REQUIRED",
  );
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot: "relative/cache",
      repositoryRoot,
      inspector,
    }),
    "CACHE_ROOT_NOT_ABSOLUTE",
  );
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot: path.join(repositoryRoot, "cache"),
      repositoryRoot,
      inspector,
    }),
    "CACHE_ROOT_INSIDE_REPOSITORY",
  );
});

test("拒绝 cache 根或摘要目录通过 symlink 逃逸", async () => {
  const { root, repositoryRoot, cacheRoot } = await makeRoots();
  const manifest = baseManifest();
  const inspector = fakeInspector();
  const outside = path.join(root, "outside");
  await mkdir(outside);
  await writeCacheArtifact(outside);

  const linkedRoot = path.join(root, "linked-cache");
  await symlink(outside, linkedRoot, "dir");
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot: linkedRoot,
      repositoryRoot,
      inspector,
    }),
    "CACHE_PATH_UNSAFE",
  );

  const prefixDirectory = path.join(
    cacheRoot,
    "sha256",
    manifest.sha256.slice(0, 2),
  );
  await mkdir(prefixDirectory, { recursive: true });
  await symlink(
    path.join(outside, "sha256", manifest.sha256.slice(0, 2), manifest.sha256),
    path.join(prefixDirectory, manifest.sha256),
    "dir",
  );
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot,
      repositoryRoot,
      inspector,
    }),
    "CACHE_PATH_UNSAFE",
  );
});

test("cache 父路径经系统 symlink 时仍按真实路径检查仓库边界", async () => {
  const { root, repositoryRoot, cacheRoot } = await makeRoots();
  const manifest = baseManifest();
  await writeCacheArtifact(cacheRoot);
  const linkedParent = path.join(root, "linked-parent");
  await symlink(root, linkedParent, "dir");
  const descriptor = await verifyExternalAppArtifact({
    manifest,
    cacheRoot: path.join(linkedParent, "external-cache"),
    repositoryRoot,
    inspector: fakeInspector(),
  });
  assert.equal(descriptor.sha256, manifest.sha256);
});

test("缺失制品以及错误 size 和 hash 均在 inspector 前失败", async () => {
  const { repositoryRoot, cacheRoot } = await makeRoots();
  const inspector = fakeInspector();

  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest: baseManifest(),
      cacheRoot,
      repositoryRoot,
      inspector,
    }),
    "CACHE_ENTRY_MISSING",
  );

  await writeCacheArtifact(cacheRoot);
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest: baseManifest({ sizeBytes: CONTENT.length + 1 }),
      cacheRoot,
      repositoryRoot,
      inspector,
    }),
    "ARTIFACT_SIZE_MISMATCH",
  );

  const wrongContent = Buffer.from("same-length-but-wrong-contents!", "utf8");
  const wrongManifest = baseManifest({
    sizeBytes: wrongContent.length,
    sha256: sha256(Buffer.alloc(wrongContent.length, 0x78)),
  });
  await writeCacheArtifact(cacheRoot, wrongManifest.sha256, wrongContent);
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest: wrongManifest,
      cacheRoot,
      repositoryRoot,
      inspector,
    }),
    "ARTIFACT_HASH_MISMATCH",
  );
  assert.equal(inspector.calls.length, 0);
});

test("拒绝 artifact symlink、目录和摘要路径之外的输入", async () => {
  const { root, repositoryRoot, cacheRoot } = await makeRoots();
  const manifest = baseManifest();
  const inspector = fakeInspector();
  const target = path.join(root, "outside-bytes");
  await writeFile(target, CONTENT);
  const artifactDirectory = path.join(
    cacheRoot,
    "sha256",
    manifest.sha256.slice(0, 2),
    manifest.sha256,
  );
  await mkdir(artifactDirectory, { recursive: true });
  await symlink(target, path.join(artifactDirectory, "artifact"));
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot,
      repositoryRoot,
      inspector,
    }),
    "CACHE_ENTRY_UNSAFE",
  );
});

test("inspector 缺失、抛错和返回未知字段均失败关闭", async () => {
  const { repositoryRoot, cacheRoot } = await makeRoots();
  await writeCacheArtifact(cacheRoot);
  const manifest = baseManifest();

  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot,
      repositoryRoot,
    }),
    "INSPECTOR_REQUIRED",
  );
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot,
      repositoryRoot,
      inspector: {
        inspect: async () => {
          throw new Error("sensitive tool output");
        },
      },
    }),
    "INSPECTION_FAILED",
  );
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest,
      cacheRoot,
      repositoryRoot,
      inspector: fakeInspector({ command: "adb install" }),
    }),
    "INSPECTOR_RESULT_INVALID",
  );
});

test("inspector 期间替换 artifact 会被第二次 hash 校验拒绝", async () => {
  const { repositoryRoot, cacheRoot } = await makeRoots();
  await writeCacheArtifact(cacheRoot);
  const replacement = Buffer.alloc(CONTENT.length, 0x7a);
  await rejectsCode(
    () => verifyExternalAppArtifact({
      manifest: baseManifest(),
      cacheRoot,
      repositoryRoot,
      inspector: {
        inspect: async ({ artifactPath }) => {
          await writeFile(artifactPath, replacement);
          return inspectedMetadata();
        },
      },
    }),
    "ARTIFACT_HASH_MISMATCH",
  );
});

test("package、version、versionCode、ABI、minSdk 和签名错误分别失败", async () => {
  const mismatchCases = [
    ["package", "other.example", "ARTIFACT_PACKAGE_MISMATCH"],
    ["version", "9.9.9", "ARTIFACT_VERSION_MISMATCH"],
    ["versionCode", 999, "ARTIFACT_VERSION_CODE_MISMATCH"],
    ["abi", ["x86_64"], "ARTIFACT_ABI_MISMATCH"],
    ["minSdk", 30, "ARTIFACT_MIN_SDK_MISMATCH"],
    ["signingCertificateSha256", "b".repeat(64), "ARTIFACT_SIGNING_MISMATCH"],
  ];

  for (const [field, value, code] of mismatchCases) {
    const { repositoryRoot, cacheRoot } = await makeRoots();
    await writeCacheArtifact(cacheRoot);
    await rejectsCode(
      () => verifyExternalAppArtifact({
        manifest: baseManifest(),
        cacheRoot,
        repositoryRoot,
        inspector: fakeInspector({ [field]: value }),
      }),
      code,
    );
  }
});
