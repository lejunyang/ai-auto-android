// 检查所有可注释的手写代码、测试与构建配置是否包含中文用途注释。
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const trackedFiles = execFileSync("git", ["ls-files", "-z"], {
  cwd: root,
  encoding: "utf8",
})
  .split("\0")
  .filter(Boolean);

const managedPatterns = [
  /^(?:cmd|internal)\/.*\.go$/,
  /^android\/(?:app|device-fixture)\/src\/.*\.(?:kt|java|xml)$/,
  /^android\/(?:app|device-fixture)\/(?:build\.gradle\.kts|proguard-rules\.pro)$/,
  /^android\/(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties)$/,
  /^android\/gradle\/(?:libs\.versions\.toml|wrapper\/gradle-wrapper\.properties)$/,
  /^\.github\/workflows\/.*\.ya?ml$/,
  /^(?:protocol\/scripts|scripts)\/.*\.mjs$/,
  /^scripts\/.*\.sh$/,
  /^Makefile$/,
];

const chinese = String.raw`\p{Script=Han}`;
const commentPatterns = {
  slash: new RegExp(
    String.raw`(?:\/\/[^\n]*${chinese}|\/\*[\s\S]*?${chinese}[\s\S]*?\*\/)`,
    "u",
  ),
  hash: new RegExp(String.raw`^[ \t]*#[^\n]*${chinese}`, "mu"),
  xml: new RegExp(String.raw`<!--[\s\S]*?${chinese}[\s\S]*?-->`, "u"),
};

const commentPatternFor = (file) => {
  if (file.endsWith(".xml")) return commentPatterns.xml;
  if (
    file.endsWith(".go")
    || file.endsWith(".kt")
    || file.endsWith(".java")
    || file.endsWith(".kts")
    || file.endsWith(".mjs")
  ) {
    return commentPatterns.slash;
  }
  return commentPatterns.hash;
};

const managedFiles = trackedFiles
  .filter((file) => managedPatterns.some((pattern) => pattern.test(file)))
  .sort();
const missing = [];

for (const file of managedFiles) {
  const content = await readFile(path.join(root, file), "utf8");
  if (!commentPatternFor(file).test(content)) {
    missing.push(file);
  }
}

if (missing.length > 0) {
  console.error("以下手写文件缺少中文功能或用途注释：");
  for (const file of missing) console.error(`- ${file}`);
  process.exitCode = 1;
} else {
  console.log(`中文注释覆盖检查通过：${managedFiles.length} 个手写文件。`);
}
