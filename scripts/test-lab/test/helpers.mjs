// 测试用途：构造仓库外临时缓存和严格元数据，验证 APK 制品安全边界而不生成真实 APK。
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

export const CONTENT = Buffer.from("not-an-apk-and-never-installed", "utf8");

export const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

export const baseManifest = (overrides = {}) => ({
  schemaVersion: "1.0",
  package: "com.example.compatibility",
  version: "3.2.1",
  versionCode: 30201,
  abi: ["arm64-v8a"],
  minSdk: 26,
  source: {
    type: "user-provided",
    note: "由用户依据其合法授权在仓库外手动提供。",
  },
  licenseNote: "测试操作者负责确认使用与测试许可，不允许重新分发。",
  sizeBytes: CONTENT.length,
  sha256: sha256(CONTENT),
  signingCertificateSha256: "a".repeat(64),
  ...overrides,
});

export const inspectedMetadata = (overrides = {}) => ({
  package: "com.example.compatibility",
  version: "3.2.1",
  versionCode: 30201,
  abi: ["arm64-v8a"],
  minSdk: 26,
  signingCertificateSha256: "a".repeat(64),
  ...overrides,
});

export const fakeInspector = (overrides = {}) => {
  const calls = [];
  return {
    calls,
    inspect: async (request) => {
      calls.push(request);
      return inspectedMetadata(overrides);
    },
  };
};

export const writeCacheArtifact = async (
  cacheRoot,
  digest = sha256(CONTENT),
  content = CONTENT,
) => {
  const directory = path.join(
    cacheRoot,
    "sha256",
    digest.slice(0, 2),
    digest,
  );
  await mkdir(directory, { recursive: true });
  const artifactPath = path.join(directory, "artifact");
  await writeFile(artifactPath, content, { mode: 0o600 });
  return artifactPath;
};
