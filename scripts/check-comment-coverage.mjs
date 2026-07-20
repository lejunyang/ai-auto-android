// 检查所有受管手写代码、测试与工程配置是否包含中文功能或用途注释。
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const trackedFiles = execFileSync(
  "git",
  ["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--deduplicate"],
  {
    cwd: root,
    encoding: "utf8",
  },
)
  .split("\0")
  .filter((file) => file && existsSync(path.join(root, file)));

const excludedDirectory = /(^|\/)(build|generated|vendor|node_modules|\.gradle)(\/|$)/;
const excludedFile = /(^|\/)(gradle-wrapper\.properties|[^/]+\.lock)$/;
const sourceFile = /\.(go|kt|java|js|mjs|ts)$/;
const gradleScript = /\.gradle\.kts$/;
const commandScript = /\.(sh|ps1)$/;
const workflow = /^\.github\/workflows\/[^/]+\.ya?ml$/;
const controlledConfig = /\.(properties|toml)$/;
const commentableXml = /^(android|protocol)\/.*\.xml$/;

const isExcluded = (file) =>
  excludedDirectory.test(file) || excludedFile.test(file);

const isManaged = (file) =>
  !isExcluded(file)
  && (
    sourceFile.test(file)
    || gradleScript.test(file)
    || file === "Makefile"
    || commandScript.test(file)
    || workflow.test(file)
    || controlledConfig.test(file)
    || commentableXml.test(file)
  );

const chinese = String.raw`\p{Script=Han}`;
const commentPatterns = {
  slash: new RegExp(
    String.raw`(?:\/\/[^\n]*${chinese}|\/\*[\s\S]*?${chinese}[\s\S]*?\*\/)`,
    "u",
  ),
  hash: new RegExp(
    String.raw`(?:^[ \t]*#[^\n]*${chinese}|<#[\s\S]*?${chinese}[\s\S]*?#>)`,
    "mu",
  ),
  xml: new RegExp(String.raw`<!--[\s\S]*?${chinese}[\s\S]*?-->`, "u"),
};

const commentPatternFor = (file) => {
  if (file.endsWith(".xml")) return commentPatterns.xml;
  if (sourceFile.test(file) || gradleScript.test(file)) {
    return commentPatterns.slash;
  }
  return commentPatterns.hash;
};

const managedFiles = trackedFiles.filter(isManaged).sort();
const excludedFiles = trackedFiles.filter(isExcluded).sort();
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
  console.log(
    `中文注释覆盖检查通过：覆盖 ${managedFiles.length} 个手写文件，`
      + `排除 ${excludedFiles.length} 个生成、依赖、缓存、锁文件或 Gradle Wrapper 文件。`,
  );
}
