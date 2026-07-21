// 脚本用途：校验发布 Skills 的结构、安全内容与项目级 Trae 镜像一致性。
import { cp, lstat, mkdir, readdir, readFile, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(root, "skills");
const mirrorRoot = path.join(root, ".trae", "skills");
const skillNames = [
  "android-device-automation",
  "android-device-discovery",
  "android-device-observation",
];

const requiredContent = {
  "android-device-discovery": [
    "aactl doctor --json",
    "aactl devices list --json",
    "aactl devices pair",
    "aactl devices connect",
    "unauthorized",
    "offline",
    "多台设备",
  ],
  "android-device-observation": [
    "aactl device info",
    "aactl observe screenshot",
    "aactl observe hierarchy",
    "aactl bridge snapshot",
    "隐私",
    "脱敏",
    "产物",
  ],
  "android-device-automation": [
    "aactl action tap",
    "aactl bridge action",
    "aactl recording replay",
    "确认",
    "任意 shell",
    "自动批准",
    "支付",
    "恢复",
  ],
};

const fail = (message) => {
  throw new Error(message);
};

const listEntries = async (directory) =>
  (await readdir(directory, { withFileTypes: true }))
    .sort((left, right) => {
      if (left.name < right.name) return -1;
      if (left.name > right.name) return 1;
      return 0;
    });

const assertRootLayout = async (directory, allowedFiles) => {
  const entries = await listEntries(directory);
  const actual = entries.map((entry) => entry.name);
  const expected = [...skillNames, ...allowedFiles].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(
      `${path.relative(root, directory)} entries must be ${expected.join(", ")}, `
      + `got ${actual.join(", ")}`,
    );
  }
  for (const entry of entries) {
    if (skillNames.includes(entry.name) && !entry.isDirectory()) {
      fail(`${path.relative(root, directory)}/${entry.name} must be a directory`);
    }
    if (allowedFiles.includes(entry.name) && !entry.isFile()) {
      fail(`${path.relative(root, directory)}/${entry.name} must be a regular file`);
    }
  }
};

const parseFrontmatter = (skillName, content) => {
  const match = content.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]+)$/);
  if (!match) {
    fail(`${skillName}/SKILL.md must contain YAML frontmatter and a Markdown body`);
  }
  const properties = {};
  for (const line of match[1].split(/\r?\n/)) {
    const property = line.match(/^([a-z][a-z-]*):[ \t]+(.+)$/);
    if (!property) {
      fail(`${skillName}/SKILL.md has unsupported frontmatter line: ${line}`);
    }
    const [, key, value] = property;
    if (Object.hasOwn(properties, key)) {
      fail(`${skillName}/SKILL.md repeats frontmatter field ${key}`);
    }
    properties[key] = value.trim().replace(/^(['"])(.*)\1$/, "$2");
  }
  const keys = Object.keys(properties).sort();
  if (JSON.stringify(keys) !== JSON.stringify(["description", "name"])) {
    fail(`${skillName}/SKILL.md frontmatter must contain only name and description`);
  }
  return { properties, body: match[2] };
};

const markdownReferencePaths = (content) => {
  const references = new Set();
  const linkPattern = /\]\((references\/[^)\s#]+)(?:#[^)]+)?\)/g;
  for (const match of content.matchAll(linkPattern)) {
    references.add(match[1]);
  }
  return references;
};

const validateSkill = async (baseRoot, skillName) => {
  const relativeRoot = path.relative(root, baseRoot);
  const skillRoot = path.join(baseRoot, skillName);
  const entries = await listEntries(skillRoot);
  const names = entries.map((entry) => entry.name);
  if (JSON.stringify(names) !== JSON.stringify(["SKILL.md", "references"])) {
    fail(`${relativeRoot}/${skillName} must contain only SKILL.md and references/`);
  }
  if (!entries.find((entry) => entry.name === "SKILL.md")?.isFile()) {
    fail(`${relativeRoot}/${skillName}/SKILL.md must be a regular file`);
  }
  if (!entries.find((entry) => entry.name === "references")?.isDirectory()) {
    fail(`${relativeRoot}/${skillName}/references must be a directory`);
  }

  const skillFile = path.join(skillRoot, "SKILL.md");
  const skillContent = await readFile(skillFile, "utf8");
  const { properties, body } = parseFrontmatter(skillName, skillContent);
  if (properties.name !== skillName) {
    fail(`${relativeRoot}/${skillName}/SKILL.md name must match its directory`);
  }
  if (
    properties.name.length > 64
    || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(properties.name)
  ) {
    fail(`${relativeRoot}/${skillName}/SKILL.md has an invalid name`);
  }
  const descriptionLength = Array.from(properties.description).length;
  if (
    descriptionLength === 0
    || descriptionLength >= 200
    || !/\p{Script=Han}/u.test(properties.description)
    || !/(?:使用时|时使用|用于|适用于)/u.test(properties.description)
  ) {
    fail(
      `${relativeRoot}/${skillName}/SKILL.md description must contain Chinese, `
      + `be under 200 Unicode characters, and describe when it is used`,
    );
  }
  if (!body.trimStart().startsWith("# ") || body.trim().length < 200) {
    fail(`${relativeRoot}/${skillName}/SKILL.md must have a substantive Markdown body`);
  }
  if (skillContent.split(/\r?\n/).length > 500) {
    fail(`${relativeRoot}/${skillName}/SKILL.md exceeds the 500-line recommendation`);
  }

  const referenceRoot = path.join(skillRoot, "references");
  const referenceEntries = await listEntries(referenceRoot);
  if (referenceEntries.length === 0) {
    fail(`${relativeRoot}/${skillName}/references must not be empty`);
  }
  for (const entry of referenceEntries) {
    if (!entry.isFile() || !entry.name.endsWith(".md")) {
      fail(
        `${relativeRoot}/${skillName}/references/${entry.name} must be a one-level Markdown file`,
      );
    }
  }

  const linkedReferences = markdownReferencePaths(skillContent);
  const actualReferences = new Set(
    referenceEntries.map((entry) => `references/${entry.name}`),
  );
  for (const reference of linkedReferences) {
    if (!actualReferences.has(reference)) {
      fail(`${relativeRoot}/${skillName}/SKILL.md has missing reference ${reference}`);
    }
  }
  for (const reference of actualReferences) {
    if (!linkedReferences.has(reference)) {
      fail(`${relativeRoot}/${skillName}/SKILL.md does not link ${reference}`);
    }
  }

  const referenceContents = await Promise.all(
    referenceEntries.map((entry) =>
      readFile(path.join(referenceRoot, entry.name), "utf8")
    ),
  );
  const combined = [skillContent, ...referenceContents].join("\n").toLowerCase();
  for (const required of requiredContent[skillName]) {
    if (!combined.includes(required)) {
      fail(`${relativeRoot}/${skillName} must document ${JSON.stringify(required)}`);
    }
  }
};

const relativeFiles = async (directory, prefix = "") => {
  const files = [];
  for (const entry of await listEntries(directory)) {
    const relative = path.join(prefix, entry.name);
    const absolute = path.join(directory, entry.name);
    const info = await lstat(absolute);
    if (info.isSymbolicLink()) {
      fail(`${path.relative(root, absolute)} must not be a symbolic link`);
    }
    if (info.isDirectory()) {
      files.push(...await relativeFiles(absolute, relative));
    } else if (info.isFile()) {
      files.push(relative);
    } else {
      fail(`${path.relative(root, absolute)} must be a regular file or directory`);
    }
  }
  return files;
};

const assertMirrored = async (skillName) => {
  const source = path.join(sourceRoot, skillName);
  const mirror = path.join(mirrorRoot, skillName);
  const sourceFiles = await relativeFiles(source);
  const mirrorFiles = await relativeFiles(mirror);
  if (JSON.stringify(sourceFiles) !== JSON.stringify(mirrorFiles)) {
    fail(`${skillName} source and Trae mirror have different file lists`);
  }
  for (const relative of sourceFiles) {
    const sourceContent = await readFile(path.join(source, relative));
    const mirrorContent = await readFile(path.join(mirror, relative));
    if (!sourceContent.equals(mirrorContent)) {
      fail(`${skillName}/${relative} differs between source and Trae mirror`);
    }
  }
};

const check = async () => {
  await assertRootLayout(sourceRoot, ["README.md"]);
  await assertRootLayout(mirrorRoot, []);
  for (const skillName of skillNames) {
    await validateSkill(sourceRoot, skillName);
    await validateSkill(mirrorRoot, skillName);
    await assertMirrored(skillName);
  }
  console.log(`Skill validation passed: ${skillNames.length} portable skills and Trae mirrors.`);
};

const sync = async () => {
  await assertRootLayout(sourceRoot, ["README.md"]);
  for (const skillName of skillNames) {
    await validateSkill(sourceRoot, skillName);
  }
  await rm(mirrorRoot, { recursive: true, force: true });
  await mkdir(mirrorRoot, { recursive: true });
  for (const skillName of skillNames) {
    await cp(
      path.join(sourceRoot, skillName),
      path.join(mirrorRoot, skillName),
      { recursive: true },
    );
  }
  await check();
};

const mode = process.argv[2];
if (mode === "sync") {
  await sync();
} else if (mode === "check") {
  await check();
} else {
  console.error("Usage: node scripts/skills.mjs sync|check");
  process.exitCode = 2;
}
