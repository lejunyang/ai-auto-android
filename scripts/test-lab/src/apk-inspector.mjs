// 功能用途：以固定 Android build-tools argv 离线读取 APK 身份，并在工具或输出歧义时失败关闭。
import { execFile as nodeExecFile } from "node:child_process";
import {
  lstat,
  realpath,
} from "node:fs/promises";
import path from "node:path";

import { ExternalAppError } from "./manifest.mjs";

const allowedAbis = new Set([
  "arm64-v8a",
  "armeabi-v7a",
  "x86",
  "x86_64",
]);
const digestPattern = /^[0-9a-f]{64}$/u;
const packageNamePattern =
  /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const requestKeys = [
  "artifactPath",
  "sha256",
  "sizeBytes",
];
const buildToolsConfigKeys = [
  "buildToolsDirectory",
  "javaPath",
  "repositoryRoot",
];
const fixedPathsConfigKeys = [
  "aapt2Path",
  "apksignerJarPath",
  "javaPath",
  "repositoryRoot",
];
const dependencyKeys = ["execFile"];
const buildToolsVersionPattern =
  /^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9._-]+)?$/u;

export const APK_INSPECTOR_LIMITS = Object.freeze({
  outputBytes: 256 * 1024,
  timeoutMs: 15_000,
});

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

const fail = (code) => {
  throw new ExternalAppError(code);
};

const assertNoSymlinkChain = async (candidate) => {
  const parsed = path.parse(candidate);
  const segments = candidate
    .slice(parsed.root.length)
    .split(path.sep)
    .filter((segment) => segment !== "");
  let current = parsed.root;
  for (const segment of segments) {
    current = path.join(current, segment);
    let info;
    try {
      info = await lstat(current);
    } catch {
      fail("APK_INSPECTOR_TOOL_PATH_INVALID");
    }
    if (info.isSymbolicLink()) {
      fail("APK_INSPECTOR_TOOL_UNSAFE");
    }
  }
};

const assertAbsolutePath = (candidate) => {
  if (
    typeof candidate !== "string"
    || !path.isAbsolute(candidate)
    || candidate !== path.resolve(candidate)
    || candidate.includes("\u0000")
  ) {
    fail("APK_INSPECTOR_TOOL_PATH_INVALID");
  }
};

const assertRepositoryRoot = async (repositoryRoot) => {
  assertAbsolutePath(repositoryRoot);
  let info;
  try {
    info = await lstat(repositoryRoot);
  } catch {
    fail("APK_INSPECTOR_TOOL_PATH_INVALID");
  }
  if (!info.isDirectory() || info.isSymbolicLink()) {
    fail("APK_INSPECTOR_TOOL_UNSAFE");
  }
  try {
    return await realpath(repositoryRoot);
  } catch {
    fail("APK_INSPECTOR_TOOL_UNSAFE");
  }
};

const toolIdentity = (info) =>
  Object.freeze({
    ctimeMs: info.ctimeMs,
    dev: info.dev,
    ino: info.ino,
    mode: info.mode,
    mtimeMs: info.mtimeMs,
    size: info.size,
  });

const sameIdentity = (left, right) =>
  left.ctimeMs === right.ctimeMs
  && left.dev === right.dev
  && left.ino === right.ino
  && left.mode === right.mode
  && left.mtimeMs === right.mtimeMs
  && left.size === right.size;

const readInitialTool = async (
  toolPath,
  expectedName,
  repositoryReal,
  requireExecutable = true,
) => {
  assertAbsolutePath(toolPath);
  if (path.basename(toolPath) !== expectedName) {
    fail("APK_INSPECTOR_TOOL_PATH_INVALID");
  }
  await assertNoSymlinkChain(toolPath);
  let info;
  let canonicalPath;
  try {
    info = await lstat(toolPath);
    canonicalPath = await realpath(toolPath);
  } catch {
    fail("APK_INSPECTOR_TOOL_PATH_INVALID");
  }
  if (
    !info.isFile()
    || info.isSymbolicLink()
    || (requireExecutable && (info.mode & 0o111) === 0)
    || isWithin(repositoryReal, canonicalPath)
  ) {
    fail("APK_INSPECTOR_TOOL_UNSAFE");
  }
  return Object.freeze({
    canonicalPath,
    identity: toolIdentity(info),
    path: toolPath,
  });
};

const assertToolUnchanged = async (tool) => {
  await assertNoSymlinkChain(tool.path);
  let info;
  let canonicalPath;
  try {
    info = await lstat(tool.path);
    canonicalPath = await realpath(tool.path);
  } catch {
    fail("APK_INSPECTOR_TOOL_CHANGED");
  }
  if (
    !info.isFile()
    || info.isSymbolicLink()
    || canonicalPath !== tool.canonicalPath
    || !sameIdentity(tool.identity, toolIdentity(info))
  ) {
    fail("APK_INSPECTOR_TOOL_CHANGED");
  }
};

const resolveTools = async (config, repositoryReal) => {
  if (exactKeys(config, buildToolsConfigKeys)) {
    const directory = config.buildToolsDirectory;
    assertAbsolutePath(directory);
    if (
      path.basename(path.dirname(directory)) !== "build-tools"
      || !buildToolsVersionPattern.test(path.basename(directory))
    ) {
      fail("APK_INSPECTOR_TOOL_PATH_INVALID");
    }
    await assertNoSymlinkChain(directory);
    let info;
    let canonicalDirectory;
    try {
      info = await lstat(directory);
      canonicalDirectory = await realpath(directory);
    } catch {
      fail("APK_INSPECTOR_TOOL_PATH_INVALID");
    }
    if (
      !info.isDirectory()
      || info.isSymbolicLink()
      || isWithin(repositoryReal, canonicalDirectory)
    ) {
      fail("APK_INSPECTOR_TOOL_UNSAFE");
    }
    return Object.freeze({
      aapt2: await readInitialTool(
        path.join(directory, "aapt2"),
        "aapt2",
        repositoryReal,
      ),
      apksignerJar: await readInitialTool(
        path.join(directory, "lib", "apksigner.jar"),
        "apksigner.jar",
        repositoryReal,
        false,
      ),
      java: await readInitialTool(config.javaPath, "java", repositoryReal),
    });
  }
  if (exactKeys(config, fixedPathsConfigKeys)) {
    return Object.freeze({
      aapt2: await readInitialTool(
        config.aapt2Path,
        "aapt2",
        repositoryReal,
      ),
      apksignerJar: await readInitialTool(
        config.apksignerJarPath,
        "apksigner.jar",
        repositoryReal,
        false,
      ),
      java: await readInitialTool(config.javaPath, "java", repositoryReal),
    });
  }
  fail("APK_INSPECTOR_CONFIG_INVALID");
};

const assertInspectionRequest = async (request) => {
  if (
    !exactKeys(request, requestKeys)
    || typeof request.artifactPath !== "string"
    || !path.isAbsolute(request.artifactPath)
    || request.artifactPath !== path.resolve(request.artifactPath)
    || request.artifactPath.includes("\u0000")
    || !Number.isSafeInteger(request.sizeBytes)
    || request.sizeBytes < 1
    || !digestPattern.test(request.sha256 ?? "")
  ) {
    fail("APK_INSPECTOR_REQUEST_INVALID");
  }
  let info;
  try {
    info = await lstat(request.artifactPath);
  } catch {
    fail("APK_INSPECTOR_ARTIFACT_UNSAFE");
  }
  if (
    !info.isFile()
    || info.isSymbolicLink()
    || info.size !== request.sizeBytes
  ) {
    fail("APK_INSPECTOR_ARTIFACT_UNSAFE");
  }
};

const mapExecutionError = (error) => {
  if (
    error?.killed === true
    || error?.signal === "SIGTERM"
    || error?.code === "ETIMEDOUT"
  ) {
    return "APK_INSPECTOR_TIMEOUT";
  }
  if (
    error?.code === "ERR_CHILD_PROCESS_STDIO_MAXBUFFER"
    || error?.code === "ENOBUFS"
  ) {
    return "APK_INSPECTOR_OUTPUT_LIMIT";
  }
  return "APK_INSPECTOR_TOOL_FAILED";
};

const runFixedTool = async (execFile, tool, argv) => {
  await assertToolUnchanged(tool);
  let result;
  try {
    result = await new Promise((resolve, reject) => {
      execFile(
        tool.path,
        Object.freeze([...argv]),
        {
          encoding: "utf8",
          maxBuffer: APK_INSPECTOR_LIMITS.outputBytes,
          shell: false,
          timeout: APK_INSPECTOR_LIMITS.timeoutMs,
          windowsHide: true,
        },
        (error, stdout, stderr) => {
          if (error) {
            reject(error);
            return;
          }
          resolve({ stderr, stdout });
        },
      );
    });
  } catch (error) {
    fail(mapExecutionError(error));
  }
  await assertToolUnchanged(tool);
  if (
    typeof result.stdout !== "string"
    || typeof result.stderr !== "string"
    || Buffer.byteLength(result.stdout, "utf8")
      + Buffer.byteLength(result.stderr, "utf8")
      > APK_INSPECTOR_LIMITS.outputBytes
  ) {
    fail("APK_INSPECTOR_OUTPUT_LIMIT");
  }
  return result.stdout;
};

const safeText = (value, maximumLength) =>
  typeof value === "string"
  && value.length >= 1
  && value.length <= maximumLength
  && value.trim() === value
  && !/[\u0000-\u001f\u007f]/u.test(value);

const decodeAaptValue = (value) => {
  if (/\\(?![\\'])/u.test(value)) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  return value.replace(/\\'/gu, "'").replace(/\\\\/gu, "\\");
};

const parsePackageAttributes = (line) => {
  const prefix = "package:";
  if (!line.startsWith(prefix)) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  const attributes = new Map();
  const input = line.slice(prefix.length);
  const pattern = /([A-Za-z][A-Za-z0-9]*)='((?:\\.|[^'])*)'/gu;
  let consumed = 0;
  for (const match of input.matchAll(pattern)) {
    if (input.slice(consumed, match.index).trim() !== "") {
      fail("APK_INSPECTOR_OUTPUT_INVALID");
    }
    if (attributes.has(match[1])) {
      fail("APK_INSPECTOR_OUTPUT_INVALID");
    }
    attributes.set(match[1], decodeAaptValue(match[2]));
    consumed = match.index + match[0].length;
  }
  if (input.slice(consumed).trim() !== "") {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  return attributes;
};

const singleMatchingLine = (lines, predicate) => {
  const matches = lines.filter(predicate);
  if (matches.length !== 1) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  return matches[0];
};

const parsePositiveInteger = (value) => {
  if (!/^(?:0|[1-9][0-9]*)$/u.test(value ?? "")) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  return parsed;
};

const parseBadging = (output) => {
  if (
    typeof output !== "string"
    || output.length === 0
    || output.includes("\u0000")
  ) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  const lines = output.split(/\r?\n/u).filter((line) => line !== "");
  const packageLine = singleMatchingLine(
    lines,
    (line) => line.startsWith("package:"),
  );
  const attributes = parsePackageAttributes(packageLine);
  const packageName = attributes.get("name");
  const version = attributes.get("versionName");
  const versionCode = parsePositiveInteger(attributes.get("versionCode"));
  if (
    !packageNamePattern.test(packageName ?? "")
    || !safeText(version, 128)
  ) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }

  const sdkLine = singleMatchingLine(
    lines,
    (line) => line.startsWith("sdkVersion:"),
  );
  const sdkMatch = sdkLine.match(/^sdkVersion:'([0-9]+)'$/u);
  const minSdk = parsePositiveInteger(sdkMatch?.[1]);
  if (minSdk > 100) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }

  const nativeCodeLine = singleMatchingLine(
    lines,
    (line) => line.startsWith("native-code:"),
  );
  const nativeCodeValues = [
    ...nativeCodeLine.matchAll(/'([^']+)'/gu),
  ].map((match) => match[1]);
  const reconstructed = `native-code:${nativeCodeValues
    .map((abi) => ` '${abi}'`)
    .join("")}`;
  if (
    reconstructed !== nativeCodeLine
    || nativeCodeValues.length < 1
    || nativeCodeValues.length > allowedAbis.size
    || new Set(nativeCodeValues).size !== nativeCodeValues.length
    || nativeCodeValues.some((abi) => !allowedAbis.has(abi))
  ) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }

  return {
    abi: nativeCodeValues,
    minSdk,
    package: packageName,
    version,
    versionCode,
  };
};

const parseSigningCertificate = (output) => {
  if (
    typeof output !== "string"
    || output.length === 0
    || output.includes("\u0000")
  ) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  const lines = output.split(/\r?\n/u).filter((line) => line !== "");
  const signerNumbers = new Set();
  const digests = [];
  for (const line of lines) {
    const signerMatch = line.match(/^Signer #([0-9]+) /u);
    if (signerMatch) {
      signerNumbers.add(parsePositiveInteger(signerMatch[1]));
    }
    const digestMatch = line.match(
      /^Signer #([0-9]+) certificate SHA-256 digest: ([0-9A-Fa-f:]+)$/u,
    );
    if (digestMatch) {
      digests.push({
        signer: parsePositiveInteger(digestMatch[1]),
        value: digestMatch[2],
      });
    }
  }
  if (
    signerNumbers.size !== 1
    || !signerNumbers.has(1)
    || digests.length !== 1
    || digests[0].signer !== 1
  ) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  const rawDigest = digests[0].value;
  if (
    !/^(?:[0-9A-Fa-f]{64}|[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})$/u
      .test(rawDigest)
  ) {
    fail("APK_INSPECTOR_OUTPUT_INVALID");
  }
  return rawDigest.replaceAll(":", "").toLowerCase();
};

export const createAndroidApkInspector = async (
  config,
  dependencies = Object.freeze({ execFile: nodeExecFile }),
) => {
  if (
    config === null
    || typeof config !== "object"
    || Array.isArray(config)
    || !exactKeys(dependencies, dependencyKeys)
    || typeof dependencies.execFile !== "function"
  ) {
    fail("APK_INSPECTOR_CONFIG_INVALID");
  }
  const repositoryReal = await assertRepositoryRoot(config.repositoryRoot);
  const tools = await resolveTools(config, repositoryReal);

  return Object.freeze({
    inspect: async (request) => {
      await assertInspectionRequest(request);
      const badging = await runFixedTool(
        dependencies.execFile,
        tools.aapt2,
        ["dump", "badging", request.artifactPath],
      );
      await assertToolUnchanged(tools.apksignerJar);
      const signing = await runFixedTool(
        dependencies.execFile,
        tools.java,
        [
          "-Xmx256M",
          "-jar",
          tools.apksignerJar.path,
          "verify",
          "--print-certs",
          request.artifactPath,
        ],
      );
      await assertToolUnchanged(tools.apksignerJar);
      const metadata = parseBadging(badging);
      return Object.freeze({
        package: metadata.package,
        version: metadata.version,
        versionCode: metadata.versionCode,
        abi: Object.freeze([...metadata.abi]),
        minSdk: metadata.minSdk,
        signingCertificateSha256: parseSigningCertificate(signing),
      });
    },
  });
};
