// 脚本用途：检查工作区和全部 Git 历史中的 Android 安装制品路径，阻止提交或重分发。
import { execFile } from "node:child_process";
import { lstat, readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

import { ExternalAppError } from "./manifest.mjs";

const execFileAsync = promisify(execFile);
const forbiddenExtension = /\.(?:apk|apks|aab|xapk)$/iu;
const ignoredDirectories = new Set([".git"]);

const repositoryRootFromModule = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../..",
);

const listWorkspacePaths = async (repositoryRoot) => {
  const matches = [];
  const visit = async (directory, relativeDirectory = "") => {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const relative = relativeDirectory === ""
        ? entry.name
        : path.posix.join(relativeDirectory, entry.name);
      if (entry.isSymbolicLink()) {
        if (forbiddenExtension.test(relative)) matches.push(relative);
        continue;
      }
      if (entry.isDirectory()) {
        if (!ignoredDirectories.has(entry.name)) {
          await visit(path.join(directory, entry.name), relative);
        }
      } else if (forbiddenExtension.test(relative)) {
        matches.push(relative);
      }
    }
  };
  await visit(repositoryRoot);
  return matches.sort();
};

const defaultReadHistory = async (repositoryRoot) => {
  const { stdout } = await execFileAsync(
    "git",
    ["log", "--all", "--name-only", "--pretty=format:", "-z"],
    {
      cwd: repositoryRoot,
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
    },
  );
  return stdout;
};

export const assertApkIgnored = async (repositoryRoot) => {
  let info;
  try {
    info = await lstat(path.join(repositoryRoot, ".gitignore"));
  } catch {
    throw new ExternalAppError("GITIGNORE_MISSING");
  }
  if (!info.isFile() || info.isSymbolicLink()) {
    throw new ExternalAppError("GITIGNORE_UNSAFE");
  }
  const content = await readFile(path.join(repositoryRoot, ".gitignore"), "utf8");
  const rules = content
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter((line) => line !== "" && !line.startsWith("#"));
  if (!rules.includes("*.apk")) {
    throw new ExternalAppError("APK_IGNORE_RULE_MISSING");
  }
};

export const scanRepositoryForApk = async ({
  repositoryRoot,
  readHistory = defaultReadHistory,
  readWorkspace = listWorkspacePaths,
}) => {
  if (typeof repositoryRoot !== "string" || !path.isAbsolute(repositoryRoot)) {
    throw new ExternalAppError("REPOSITORY_ROOT_INVALID");
  }
  await assertApkIgnored(repositoryRoot);
  let workspacePaths;
  try {
    workspacePaths = await readWorkspace(repositoryRoot);
  } catch {
    throw new ExternalAppError("WORKSPACE_SCAN_FAILED");
  }
  if (
    !Array.isArray(workspacePaths)
    || workspacePaths.some((entry) => typeof entry !== "string")
  ) {
    throw new ExternalAppError("WORKSPACE_SCAN_FAILED");
  }
  const workspaceMatches = [...new Set(
    workspacePaths.filter((entry) => forbiddenExtension.test(entry)),
  )].sort();
  let history;
  try {
    history = await readHistory(repositoryRoot);
  } catch {
    throw new ExternalAppError("GIT_HISTORY_SCAN_FAILED");
  }
  if (typeof history !== "string") {
    throw new ExternalAppError("GIT_HISTORY_SCAN_FAILED");
  }
  const historyMatches = [...new Set(
    history
      .split(/\0|\r?\n/u)
      .map((entry) => entry.trim())
      .filter((entry) => forbiddenExtension.test(entry)),
  )].sort();
  return Object.freeze({
    workspaceMatches: Object.freeze(workspaceMatches),
    historyMatches: Object.freeze(historyMatches),
  });
};

const runCli = async () => {
  const result = await scanRepositoryForApk({
    repositoryRoot: repositoryRootFromModule,
  });
  if (result.workspaceMatches.length > 0 || result.historyMatches.length > 0) {
    process.stderr.write(`${JSON.stringify(result)}\n`);
    process.exitCode = 1;
    return;
  }
  process.stdout.write(
    `${JSON.stringify({
      ok: true,
      workspaceApkPaths: 0,
      historyApkPaths: 0,
    })}\n`,
  );
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await runCli();
}
