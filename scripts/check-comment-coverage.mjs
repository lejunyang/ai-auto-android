// 功能用途：按语言结构检查受管手写文件的中文用途注释，并用内置反例防止无信息量注释蒙混过关。
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
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

const chinese = /\p{Script=Han}/gu;
const semanticAction =
  /实现|提供|验证|覆盖|约束|构建|配置|执行|检查|校验|生成|确保|防止|定义|声明|注册|维护|封装|管理|处理|解析|转换|映射|路由|记录|加载|保存|连接|同步|清理|输出|输入|诊断|发现|观察|保护|控制|绘制|组合|限定|固定|锁定|拒绝|允许|协调|编排|持久化|恢复|读取|写入|发布|统一|暴露|承载|组织|展示/u;
const testSemanticAction = /验证|覆盖|回归|检查|校验|确保|防止|拒绝|测试/u;
const purposePrefixes = [
  "功能用途：",
  "模块用途：",
  "配置用途：",
  "脚本用途：",
  "安全约束：",
];
const testPrefix = "测试用途：";

const slashComments = (content) =>
  content.match(/\/\/[^\n]*|\/\*[\s\S]*?\*\//g) ?? [];
const kdocComments = (content) => content.match(/\/\*\*[\s\S]*?\*\//g) ?? [];
const hashComments = (content) =>
  content.match(/^[ \t]*#[^\n]*|<#[\s\S]*?#>/gm) ?? [];
const xmlComments = (content) => content.match(/<!--[\s\S]*?-->/g) ?? [];

const isTestFile = (file) =>
  file.endsWith("_test.go")
  || /(?:^|\/)(?:test|androidTest)(?:\/|$)/.test(file)
  || /(?:Test|Tests)\.(?:kt|java)$/.test(file);

const stripCommentSyntax = (comment) =>
  comment
    .replace(/^\/\*\*?/, "")
    .replace(/\*\/$/, "")
    .replace(/^<!--/, "")
    .replace(/-->$/, "")
    .split("\n")
    .map((line) =>
      line
        .replace(/^[ \t]*\/\/[ \t]?/, "")
        .replace(/^[ \t]*#[ \t]?/, "")
        .replace(/^[ \t]*\*[ \t]?/, ""),
    )
    .join(" ")
    .trim();

const extractPrefixedBody = (comment, prefixes) => {
  const normalized = stripCommentSyntax(comment);
  const prefix = prefixes.find((candidate) => normalized.startsWith(candidate));
  return prefix === undefined ? null : normalized.slice(prefix.length).trim();
};

const meaningfulBody = (body, semanticPattern) => {
  if (body === null) return false;
  const hanCharacters = body.match(chinese) ?? [];
  const hanCount = hanCharacters.length;
  const distinctHanCount = new Set(hanCharacters).size;
  const nonWhitespaceCount = body.replace(/\s/gu, "").length;
  return (hanCount >= 8 || nonWhitespaceCount >= 12)
    && distinctHanCount >= 6
    && semanticPattern.test(body);
};

const commentsFor = (file, content) => {
  if (file.endsWith(".xml")) return xmlComments(content);
  if (file.endsWith(".kt") || file.endsWith(".java")) {
    return kdocComments(content);
  }
  if (
    file.endsWith(".go")
    || /\.(?:js|mjs|ts)$/.test(file)
    || gradleScript.test(file)
  ) {
    return slashComments(content);
  }
  return hashComments(content);
};

const packageDoc = (content) => {
  const match = content.match(
    /(?:^|\n)((?:\/\/[^\n]*\n)+)package[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*(?:\n|$)/,
  );
  if (!match) return null;
  const [, comment, packageName] = match;
  const firstLine = comment.split("\n")[0];
  if (!firstLine.startsWith(`// Package ${packageName} `)) return null;
  const body = stripCommentSyntax(comment).slice(`Package ${packageName} `.length).trim();
  return meaningfulBody(body, semanticAction) ? packageName : null;
};

const validateContent = (file, content) => {
  const comments = commentsFor(file, content);
  const errors = [];
  const testFile = isTestFile(file);
  const kotlinOrJava = file.endsWith(".kt") || file.endsWith(".java");
  const goFile = file.endsWith(".go");
  const requiredPrefixes = testFile
    ? [testPrefix]
    : kotlinOrJava
      ? ["功能用途："]
      : purposePrefixes;
  const requiredSemanticAction = testFile
    ? testSemanticAction
    : semanticAction;
  const hasMeaningfulPurpose = comments.some((comment) =>
    meaningfulBody(
      extractPrefixedBody(comment, requiredPrefixes),
      requiredSemanticAction,
    ),
  );

  if (!hasMeaningfulPurpose && !(goFile && !testFile && packageDoc(content))) {
    errors.push(
      testFile
        ? "缺少以 `测试用途：` 开头且正文信息充分的测试用途注释"
        : "缺少固定用途前缀且正文信息充分的中文用途注释",
    );
  }
  if (kotlinOrJava && !hasMeaningfulPurpose) {
    errors.push(
      testFile
        ? "Kotlin/Java 测试文件缺少合格的 `测试用途：` KDoc"
        : "Kotlin/Java 生产文件缺少合格的 `功能用途：` KDoc",
    );
  }
  if (goFile && testFile && !hasMeaningfulPurpose) {
    errors.push("Go 测试文件缺少合格的 `测试用途：` 行注释");
  }
  return errors;
};

const runSelfTest = () => {
  const cases = [
    {
      name: "拒绝无信息量中文注释",
      file: "scripts/temporary.mjs",
      content: "// 临时\nconst value = 1;\n",
      valid: false,
    },
    {
      name: "拒绝只有用途二字",
      file: "scripts/purpose-only.mjs",
      content: "// 用途\nconst value = 1;\n",
      valid: false,
    },
    {
      name: "拒绝只有实现二字",
      file: "scripts/implementation-only.mjs",
      content: "// 实现\nconst value = 1;\n",
      valid: false,
    },
    {
      name: "拒绝只有测试二字",
      file: "sample/src/test/SampleTest.kt",
      content: "package sample\n\n/** 测试 */\nclass SampleTest\n",
      valid: false,
    },
    {
      name: "拒绝固定前缀后的空洞正文",
      file: "scripts/temporary-purpose.mjs",
      content: "// 功能用途：临时\nconst value = 1;\n",
      valid: false,
    },
    {
      name: "拒绝重复用途词填充正文",
      file: "scripts/repeated-purpose.mjs",
      content: "// 功能用途：用途用途用途用途用途用途\nconst value = 1;\n",
      valid: false,
    },
    {
      name: "拒绝重复动作词填充正文",
      file: "scripts/repeated-action.mjs",
      content: "// 功能用途：实现实现实现实现实现实现\nconst value = 1;\n",
      valid: false,
    },
    {
      name: "拒绝只有中文字符串",
      file: "scripts/string-only.mjs",
      content: 'const label = "中文功能用途";\n',
      valid: false,
    },
    {
      name: "拒绝 Go 缺少 package doc",
      file: "sample/sample.go",
      content: "package sample\n\n// 功能用途：提供示例能力。\nconst value = 1\n",
      valid: false,
      requirePackageDoc: true,
    },
    {
      name: "拒绝 Go package doc 的过短正文",
      file: "sample/sample.go",
      content: "// Package sample 实现\npackage sample\n",
      valid: false,
      requirePackageDoc: true,
    },
    {
      name: "拒绝 Kotlin 普通中文行注释",
      file: "sample/Sample.kt",
      content: "package sample\n\n// 功能用途：提供示例能力。\nclass Sample\n",
      valid: false,
    },
    {
      name: "接受合格 Go package doc",
      file: "sample/sample.go",
      content: "// Package sample 提供检查器自测使用的完整示例能力。\npackage sample\n",
      valid: true,
      requirePackageDoc: true,
    },
    {
      name: "接受合格 Kotlin 测试 KDoc",
      file: "sample/src/test/SampleTest.kt",
      content: "package sample\n\n/** 测试用途：验证示例输入能够生成稳定且完整的契约结果。 */\nclass SampleTest\n",
      valid: true,
    },
    {
      name: "接受合格脚本用途注释",
      file: "scripts/valid.mjs",
      content: "// 脚本用途：检查受管手写文件的中文注释结构与信息量契约。\nconst value = 1;\n",
      valid: true,
    },
  ];

  const failures = [];
  for (const testCase of cases) {
    const errors = validateContent(testCase.file, testCase.content);
    if (testCase.requirePackageDoc && packageDoc(testCase.content) === null) {
      errors.push("Go 包目录缺少紧邻 package 声明的规范 package doc");
    }
    const actual = errors.length === 0;
    if (actual !== testCase.valid) {
      failures.push(`${testCase.name}: ${errors.join("；") || "意外通过"}`);
    }
  }
  if (failures.length > 0) {
    console.error("中文注释检查器自测失败：");
    for (const failure of failures) console.error(`- ${failure}`);
    return false;
  }
  console.log(`中文注释检查器自测通过：${cases.length} 个正反例。`);
  return true;
};

const runRepositoryCheck = async () => {
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
  const managedFiles = trackedFiles.filter(isManaged).sort();
  const excludedFiles = trackedFiles.filter(isExcluded).sort();
  const failures = new Map();
  const goPackages = new Map();

  for (const file of managedFiles) {
    const content = await readFile(path.join(root, file), "utf8");
    const errors = validateContent(file, content);
    if (errors.length > 0) failures.set(file, errors);
    if (file.endsWith(".go")) {
      const directory = path.posix.dirname(file);
      const docs = goPackages.get(directory) ?? [];
      docs.push(packageDoc(content));
      goPackages.set(directory, docs);
    }
  }

  for (const [directory, docs] of goPackages) {
    if (!docs.some(Boolean)) {
      failures.set(
        `${directory}/`,
        ["Go 包目录缺少紧邻 package 声明的 `// Package name ...` 中文用途说明"],
      );
    }
  }

  if (failures.size > 0) {
    console.error("以下手写文件未通过中文功能或用途注释检查：");
    for (const [file, errors] of failures) {
      console.error(`- ${file}: ${errors.join("；")}`);
    }
    return false;
  }

  console.log(
    `中文注释覆盖检查通过：覆盖 ${managedFiles.length} 个手写文件，`
      + `排除 ${excludedFiles.length} 个生成、依赖、缓存、锁文件或 Gradle Wrapper 文件。`,
  );
  return true;
};

if (process.argv.includes("--self-test")) {
  if (!runSelfTest()) process.exitCode = 1;
} else if (!(await runRepositoryCheck())) {
  process.exitCode = 1;
}
