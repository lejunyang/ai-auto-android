#!/usr/bin/env node

// 脚本用途：校验 Google 官方 system image 归档并安装固定版本，原子生成运行时强哈希 receipt。
import crypto from "node:crypto";
import { createReadStream } from "node:fs";
import {
  access,
  mkdir,
  open,
  readFile,
  rename,
  rm,
  stat,
} from "node:fs/promises";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { spawn } from "node:child_process";

import { EmulatorError, loadProfiles } from "./runner.mjs";

const parseArguments = (argv) => {
  const options = {
    profiles: path.join(import.meta.dirname, "profiles.json"),
    archives: path.join(import.meta.dirname, "image-archives.json"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    const name = flag?.slice(2);
    if (!flag?.startsWith("--") || value === undefined) {
      throw new EmulatorError("USAGE_ERROR", `invalid argument near ${flag ?? "<end>"}`);
    }
    if (!["sdk-root", "downloads", "state-root", "profiles", "archives"].includes(name)) {
      throw new EmulatorError("USAGE_ERROR", `unsupported option: ${flag}`);
    }
    options[name] = value;
  }
  for (const name of ["sdk-root", "downloads", "state-root"]) {
    if (!options[name]) throw new EmulatorError("USAGE_ERROR", `--${name} is required`);
  }
  return options;
};

const hashFile = async (file, algorithm) => {
  const hash = crypto.createHash(algorithm);
  await pipeline(createReadStream(file), hash);
  return hash.digest("hex");
};

const extractZip = async (archive, destination) => {
  await mkdir(destination, { recursive: true });
  await new Promise((resolve, reject) => {
    const child = spawn("unzip", ["-q", archive, "-d", destination], {
      stdio: ["ignore", "ignore", "pipe"],
      windowsHide: true,
    });
    let stderr = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`unzip exited ${code}: ${stderr.trim()}`));
    });
  });
};

const atomicJson = async (destination, value) => {
  await mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.tmp`;
  const handle = await open(temporary, "wx", 0o600);
  try {
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, destination);
};

const packageDestination = (sdkRoot, profile) =>
  path.join(
    sdkRoot,
    "system-images",
    `android-${profile.apiLevel}`,
    profile.tag,
    profile.abi,
  );

const exists = async (target) => {
  try {
    await access(target);
    return true;
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
};

const main = async () => {
  const options = parseArguments(process.argv.slice(2));
  const profiles = await loadProfiles(path.resolve(options.profiles));
  const archiveManifest = JSON.parse(await readFile(path.resolve(options.archives), "utf8"));
  if (archiveManifest.schemaVersion !== "1.0") {
    throw new EmulatorError("CONFIG_INVALID", "archive manifest schemaVersion must be 1.0");
  }
  const receipts = {
    schemaVersion: "1.0",
    generatedAt: new Date().toISOString(),
    packages: {},
  };
  const installed = [];
  try {
    for (const profile of profiles.profiles) {
      const descriptor = archiveManifest.packages?.[profile.systemImage];
      if (
        !descriptor
        || descriptor.revision !== profile.packageRevision
        || !/^[a-f0-9]{40}$/u.test(descriptor.sha1)
        || !Number.isSafeInteger(descriptor.size)
      ) {
        throw new EmulatorError(
          "ARCHIVE_DESCRIPTOR_INVALID",
          `archive descriptor is missing or invalid: ${profile.systemImage}`,
        );
      }
      const archive = path.join(path.resolve(options.downloads), descriptor.archive);
      const archiveStats = await stat(archive);
      if (archiveStats.size !== descriptor.size) {
        throw new EmulatorError(
          "ARCHIVE_SIZE_MISMATCH",
          `${descriptor.archive} has unexpected size`,
          { expected: descriptor.size, actual: archiveStats.size },
        );
      }
      const sha1 = await hashFile(archive, "sha1");
      if (sha1 !== descriptor.sha1) {
        throw new EmulatorError(
          "ARCHIVE_SHA1_MISMATCH",
          `${descriptor.archive} failed official SHA-1`,
        );
      }
      const sha256 = await hashFile(archive, "sha256");
      if (profile.packageSha256 !== sha256) {
        throw new EmulatorError(
          "ARCHIVE_SHA256_MISMATCH",
          `${descriptor.archive} SHA-256 does not match the fixed profile`,
          { expected: profile.packageSha256, actual: sha256 },
        );
      }
      const destination = packageDestination(path.resolve(options["sdk-root"]), profile);
      const temporary = `${destination}.install-${process.pid}`;
      const backup = `${destination}.backup-${process.pid}`;
      await rm(temporary, { recursive: true, force: true });
      await rm(backup, { recursive: true, force: true });
      await extractZip(archive, temporary);
      const entries = await import("node:fs/promises").then(({ readdir }) =>
        readdir(temporary, { withFileTypes: true }),
      );
      const roots = entries.filter((entry) => entry.isDirectory());
      const extractedRoot =
        entries.some((entry) => entry.name === "source.properties")
          ? temporary
          : roots.length === 1
            ? path.join(temporary, roots[0].name)
            : null;
      if (!extractedRoot) {
        await rm(temporary, { recursive: true, force: true });
        throw new EmulatorError(
          "ARCHIVE_LAYOUT_INVALID",
          `${descriptor.archive} layout is ambiguous`,
        );
      }
      const properties = await readFile(path.join(extractedRoot, "source.properties"), "utf8");
      const revision = properties.match(/^Pkg\.Revision=(.+)$/mu)?.[1].trim();
      if (revision !== profile.packageRevision) {
        await rm(temporary, { recursive: true, force: true });
        throw new EmulatorError(
          "SYSTEM_IMAGE_VERSION_MISMATCH",
          `${descriptor.archive} contains revision ${revision ?? "<missing>"}`,
        );
      }
      await mkdir(path.dirname(destination), { recursive: true });
      if (await exists(destination)) await rename(destination, backup);
      try {
        await rename(extractedRoot, destination);
      } catch (error) {
        if (await exists(backup)) await rename(backup, destination);
        throw error;
      }
      await rm(temporary, { recursive: true, force: true });
      installed.push({ destination, backup });
      receipts.packages[profile.systemImage] = {
        revision,
        archive: descriptor.archive,
        size: descriptor.size,
        officialSha1: sha1,
        sha256,
      };
    }
    const receiptFile = path.join(path.resolve(options["state-root"]), "package-receipts.json");
    await atomicJson(receiptFile, receipts);
    await Promise.all(
      installed.map(({ backup }) => rm(backup, { recursive: true, force: true })),
    );
    process.stdout.write(
      `${JSON.stringify({ ok: true, receiptFile, packages: receipts.packages })}\n`,
    );
  } catch (error) {
    for (const { destination, backup } of installed.reverse()) {
      await rm(destination, { recursive: true, force: true });
      if (await exists(backup)) await rename(backup, destination);
    }
    throw error;
  }
};

try {
  await main();
} catch (error) {
  process.stderr.write(`${error.code ?? "INTERNAL_ERROR"}: ${error.message}\n`);
  process.exitCode = error.code === "USAGE_ERROR" ? 2 : 1;
}
