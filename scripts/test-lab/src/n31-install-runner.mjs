// 功能用途：将 N46 verified descriptor 绑定 N31 owned emulator，并以固定 ADB argv 验证安装同字节制品。
import { createHash } from "node:crypto";
import {
  chmod,
  copyFile,
  mkdir,
  mkdtemp,
  open,
  rm,
} from "node:fs/promises";
import path from "node:path";

import { ExternalAppError } from "./manifest.mjs";
import { assertVerifiedExternalAppDescriptor } from "./verifier.mjs";

const packagePattern =
  /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const serialPattern = /^emulator-[0-9]{4,5}$/u;
const profilePattern = /^api-(?:30|33|34)$/u;
const fingerprintPattern = /^[0-9a-f]{64}$/u;
const installedPathPattern =
  /^\/data\/app\/[^\u0000-\u0020\u007f]+\/base\.apk$/u;

const fail = (code) => {
  throw new ExternalAppError(code);
};

const digestFile = async (file) => {
  const handle = await open(file, "r");
  try {
    const info = await handle.stat();
    const digest = createHash("sha256");
    const buffer = Buffer.alloc(1024 * 1024);
    let position = 0;
    while (position < info.size) {
      const { bytesRead } = await handle.read(
        buffer,
        0,
        Math.min(buffer.length, info.size - position),
        position,
      );
      if (bytesRead <= 0) fail("INSTALLED_APK_READ_FAILED");
      digest.update(buffer.subarray(0, bytesRead));
      position += bytesRead;
    }
    buffer.fill(0);
    return Object.freeze({
      sha256: digest.digest("hex"),
      sizeBytes: info.size,
    });
  } finally {
    await handle.close();
  }
};

const assertContext = (context, expected) => {
  assertVerifiedExternalAppDescriptor(context?.descriptor);
  if (
    context.serial !== expected.serial
    || context.snapshot !== "clean"
    || context.package !== context.descriptor.package
    || !serialPattern.test(context.serial)
    || !packagePattern.test(context.package)
  ) {
    fail("RUNNER_INPUT_INVALID");
  }
};

const assertN31Result = (result, expected) => {
  if (
    result?.profileId !== expected.profileId
    || result?.serial !== expected.serial
    || result?.deviceFingerprint !== expected.deviceFingerprint
  ) {
    fail("RUNNER_CONTEXT_DRIFT");
  }
};

const runAdb = async (command, adbPath, args, timeoutMs) => {
  let result;
  try {
    result = await command(adbPath, Object.freeze([...args]), {
      maxBuffer: 1024 * 1024,
      timeoutMs,
    });
  } catch {
    fail("ADB_OPERATION_FAILED");
  }
  if (
    result === null
    || typeof result !== "object"
    || !Number.isInteger(result.code)
    || typeof result.stdout !== "string"
    || typeof result.stderr !== "string"
    || result.timedOut === true
    || result.code !== 0
  ) {
    fail("ADB_OPERATION_FAILED");
  }
  return result;
};

const outputLines = (result) =>
  `${result.stdout}\n${result.stderr}`
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);

const assertSingleSuccess = (result, code) => {
  const lines = outputLines(result);
  if (
    !(
      (lines.length === 1 && lines[0] === "Success")
      || (
        lines.length === 2
        && lines[0] === "Performing Push Install"
        && lines[1] === "Success"
      )
      || (
        lines.length === 3
        && lines[0] === "Performing Push Install"
        && lines[1] === "Success"
        && /(?:^|[/\\])install\.apk: 1 file pushed, 0 skipped\. [0-9]+(?:\.[0-9]+)? MB\/s \([1-9][0-9]* bytes in [0-9]+(?:\.[0-9]+)?s\)$/u
          .test(lines[2])
      )
    )
  ) {
    fail(code);
  }
};

export const createN31InstallRunner = ({
  emulatorRunner,
  command,
  adbPath,
  inspector,
  profileId,
  serial,
  deviceFingerprint,
  tempRoot,
}) => {
  if (
    typeof emulatorRunner?.restore !== "function"
    || typeof command !== "function"
    || typeof inspector?.inspect !== "function"
    || typeof adbPath !== "string"
    || !path.isAbsolute(adbPath)
    || !profilePattern.test(profileId ?? "")
    || !serialPattern.test(serial ?? "")
    || !fingerprintPattern.test(deviceFingerprint ?? "")
    || typeof tempRoot !== "string"
    || !path.isAbsolute(tempRoot)
  ) {
    fail("RUNNER_INVALID");
  }
  const expected = Object.freeze({ profileId, serial, deviceFingerprint });
  let temporaryDirectory = null;
  let installed = false;

  const cleanTemporary = async () => {
    if (temporaryDirectory !== null) {
      await rm(temporaryDirectory, { force: true, recursive: true });
      temporaryDirectory = null;
    }
  };

  const createInstallCopy = async (descriptor) => {
    await cleanTemporary();
    await mkdir(tempRoot, { mode: 0o700, recursive: true });
    temporaryDirectory = await mkdtemp(
      path.join(tempRoot, "n46-install-"),
    );
    await chmod(temporaryDirectory, 0o700);
    const staged = path.join(temporaryDirectory, "install.apk");
    await copyFile(descriptor.artifactPath, staged);
    await chmod(staged, 0o600);
    const identity = await digestFile(staged);
    if (
      identity.sizeBytes !== descriptor.sizeBytes
      || identity.sha256 !== descriptor.sha256
    ) {
      fail("INSTALL_STAGING_MISMATCH");
    }
    return staged;
  };

  return Object.freeze({
    restoreCleanSnapshot: async (context) => {
      assertContext(context, expected);
      assertN31Result(await emulatorRunner.restore(profileId), expected);
    },

    installVerified: async (context) => {
      assertContext(context, expected);
      const descriptor = context.descriptor;
      const staged = await createInstallCopy(descriptor);
      await runAdb(
        command,
        adbPath,
        ["-s", serial, "wait-for-device"],
        30_000,
      );
      const readiness = await runAdb(
        command,
        adbPath,
        ["-s", serial, "shell", "pm", "path", "android"],
        30_000,
      );
      if (
        outputLines(readiness).length !== 1
        || !readiness.stdout.trim().startsWith("package:/system/")
      ) {
        fail("PACKAGE_MANAGER_NOT_READY");
      }
      const result = await runAdb(
        command,
        adbPath,
        [
          "-s",
          serial,
          "install",
          "--no-streaming",
          staged,
        ],
        5 * 60 * 1000,
      );
      assertSingleSuccess(result, "VERIFIED_INSTALL_FAILED");
      installed = true;
    },

    inspectInstalled: async (context) => {
      assertContext(context, expected);
      const descriptor = context.descriptor;
      const result = await runAdb(
        command,
        adbPath,
        ["-s", serial, "shell", "pm", "path", descriptor.package],
        30_000,
      );
      const lines = result.stdout
        .split(/\r?\n/u)
        .filter(Boolean);
      if (
        result.stderr.trim() !== ""
        || lines.length !== 1
        || !lines[0].startsWith("package:")
      ) {
        fail("INSTALLED_INSPECTION_FAILED");
      }
      const remote = lines[0].slice("package:".length);
      if (!installedPathPattern.test(remote)) {
        fail("INSTALLED_INSPECTION_FAILED");
      }
      if (temporaryDirectory === null) fail("INSTALLED_INSPECTION_FAILED");
      const pulled = path.join(temporaryDirectory, "device.apk");
      const pull = await runAdb(
        command,
        adbPath,
        ["-s", serial, "pull", remote, pulled],
        10 * 60 * 1000,
      );
      const identity = await digestFile(pulled);
      if (
        identity.sizeBytes !== descriptor.sizeBytes
        || identity.sha256 !== descriptor.sha256
      ) {
        fail("INSTALLED_APK_MISMATCH");
      }
      const inspected = await inspector.inspect({
        artifactPath: pulled,
        sizeBytes: identity.sizeBytes,
        sha256: identity.sha256,
      });
      return Object.freeze({
        package: inspected.package,
        version: inspected.version,
        versionCode: inspected.versionCode,
        signingCertificateSha256: inspected.signingCertificateSha256,
      });
    },

    clearAppData: async (context) => {
      assertContext(context, expected);
      if (!installed) return;
      const result = await runAdb(
        command,
        adbPath,
        ["-s", serial, "shell", "pm", "clear", context.package],
        30_000,
      );
      assertSingleSuccess(result, "APP_DATA_CLEAR_FAILED");
      installed = false;
    },

    restoreFinalSnapshot: async (context) => {
      assertContext(context, expected);
      try {
        assertN31Result(await emulatorRunner.restore(profileId), expected);
      } finally {
        await cleanTemporary();
      }
    },
  });
};
