// 功能用途：离线加载并严格验证 N47 Schema，再执行包、页面和动态区域跨字段约束。
import { readFile } from "node:fs/promises";
import path from "node:path";

const schemaDirectory = path.resolve(import.meta.dirname, "..", "schema");

const schemaFiles = Object.freeze({
  scenario: "scenario.schema.json",
  runReport: "run-report.schema.json",
  compatibilitySummary: "compatibility-summary.schema.json",
});

const valueType = (value) => {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Number.isInteger(value)) return "integer";
  return typeof value === "object" ? "object" : typeof value;
};

const matchesType = (value, expected) => {
  if (expected === "integer") return Number.isInteger(value);
  if (expected === "number") {
    return typeof value === "number" && Number.isFinite(value);
  }
  if (expected === "object") {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }
  if (expected === "array") return Array.isArray(value);
  if (expected === "null") return value === null;
  return typeof value === expected;
};

const checkFormat = (value, format) => {
  if (format === "uuid") {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu
      .test(value);
  }
  if (format === "date-time") {
    return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/u.test(value)
      && Number.isFinite(Date.parse(value));
  }
  return false;
};

const resolvePointer = (document, reference) => {
  if (!reference.startsWith("#/")) {
    throw new Error(`only local JSON pointers are supported: ${reference}`);
  }
  return reference
    .slice(2)
    .split("/")
    .map((part) => part.replaceAll("~1", "/").replaceAll("~0", "~"))
    .reduce((current, part) => current?.[part], document);
};

const sameJson = (left, right) => JSON.stringify(left) === JSON.stringify(right);

const validateNode = (schema, value, document, dataPath, schemaPath) => {
  if (schema.$ref) {
    const referenced = resolvePointer(document, schema.$ref);
    if (referenced === undefined) {
      return [`${dataPath}: unresolved schema reference ${schema.$ref}`];
    }
    return validateNode(
      referenced,
      value,
      document,
      dataPath,
      `${schemaPath}/$ref(${schema.$ref})`,
    );
  }

  const errors = [];
  if (schema.allOf) {
    schema.allOf.forEach((branch, index) => {
      errors.push(
        ...validateNode(branch, value, document, dataPath, `${schemaPath}/allOf/${index}`),
      );
    });
  }
  if (schema.oneOf) {
    const branches = schema.oneOf.map((branch, index) =>
      validateNode(branch, value, document, dataPath, `${schemaPath}/oneOf/${index}`));
    const matches = branches.filter((branch) => branch.length === 0).length;
    if (matches !== 1) {
      errors.push(`${dataPath}: oneOf expected one match but got ${matches}`);
    }
  }

  if (schema.type) {
    const expectedTypes = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!expectedTypes.some((expected) => matchesType(value, expected))) {
      errors.push(
        `${dataPath}: expected ${expectedTypes.join("|")} but got ${valueType(value)}`,
      );
      return errors;
    }
  }
  if (Object.hasOwn(schema, "const") && !sameJson(value, schema.const)) {
    errors.push(`${dataPath}: does not match const`);
  }
  if (schema.enum && !schema.enum.some((candidate) => sameJson(candidate, value))) {
    errors.push(`${dataPath}: value is not in enum`);
  }

  if (typeof value === "string") {
    const length = [...value].length;
    if (schema.minLength !== undefined && length < schema.minLength) {
      errors.push(`${dataPath}: shorter than minLength ${schema.minLength}`);
    }
    if (schema.maxLength !== undefined && length > schema.maxLength) {
      errors.push(`${dataPath}: longer than maxLength ${schema.maxLength}`);
    }
    if (schema.pattern && !new RegExp(schema.pattern, "u").test(value)) {
      errors.push(`${dataPath}: does not match pattern`);
    }
    if (schema.format && !checkFormat(value, schema.format)) {
      errors.push(`${dataPath}: invalid ${schema.format} format`);
    }
  }

  if (typeof value === "number" && Number.isFinite(value)) {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${dataPath}: below minimum ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push(`${dataPath}: above maximum ${schema.maximum}`);
    }
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      errors.push(`${dataPath}: fewer than ${schema.minItems} items`);
    }
    if (schema.maxItems !== undefined && value.length > schema.maxItems) {
      errors.push(`${dataPath}: more than ${schema.maxItems} items`);
    }
    if (schema.items) {
      value.forEach((item, index) => {
        errors.push(
          ...validateNode(
            schema.items,
            item,
            document,
            `${dataPath}[${index}]`,
            `${schemaPath}/items`,
          ),
        );
      });
    }
  }

  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    for (const required of schema.required ?? []) {
      if (!Object.hasOwn(value, required)) {
        errors.push(`${dataPath}.${required}: required property is missing`);
      }
    }
    for (const [property, propertySchema] of Object.entries(schema.properties ?? {})) {
      if (Object.hasOwn(value, property)) {
        errors.push(
          ...validateNode(
            propertySchema,
            value[property],
            document,
            `${dataPath}.${property}`,
            `${schemaPath}/properties/${property}`,
          ),
        );
      }
    }
    if (schema.additionalProperties === false) {
      const known = new Set(Object.keys(schema.properties ?? {}));
      for (const property of Object.keys(value)) {
        if (!known.has(property)) {
          errors.push(`${dataPath}.${property}: additional property is forbidden`);
        }
      }
    } else if (
      schema.additionalProperties
      && typeof schema.additionalProperties === "object"
    ) {
      const known = new Set(Object.keys(schema.properties ?? {}));
      for (const [property, propertyValue] of Object.entries(value)) {
        if (!known.has(property)) {
          errors.push(
            ...validateNode(
              schema.additionalProperties,
              propertyValue,
              document,
              `${dataPath}.${property}`,
              `${schemaPath}/additionalProperties`,
            ),
          );
        }
      }
    }
  }
  return errors;
};

const scenarioSemantics = (scenario) => {
  if (scenario === null || typeof scenario !== "object" || Array.isArray(scenario)) {
    return [];
  }
  const errors = [];
  const allowedPackages = new Set(scenario.targetPackages ?? []);
  const stepIds = new Set();
  for (const [index, step] of (scenario.steps ?? []).entries()) {
    if (stepIds.has(step.id)) {
      errors.push(`$.steps[${index}].id: duplicate step ID`);
    }
    stepIds.add(step.id);
    if (!allowedPackages.has(step.targetPackage)) {
      errors.push(`$.steps[${index}].targetPackage: package is not allowlisted`);
    }
    if (
      (step.action?.type === "launch" || step.action?.type === "switch-app")
      && !allowedPackages.has(step.action.package)
    ) {
      errors.push(`$.steps[${index}].action.package: package is not allowlisted`);
    }
    if (
      (step.action?.type === "launch" || step.action?.type === "switch-app")
      && step.action.package !== step.targetPackage
    ) {
      errors.push(`$.steps[${index}].action.package: package does not match target`);
    }
    const accepted = step.acceptedPageClasses ?? [];
    if (accepted.some((value) => value !== "normal" && value !== "ab-variant")) {
      errors.push(`$.steps[${index}].acceptedPageClasses: unsafe class cannot be accepted`);
    }
    if (new Set(accepted).size !== accepted.length) {
      errors.push(`$.steps[${index}].acceptedPageClasses: duplicate page class`);
    }
    if (new Set(step.allowedRoutes ?? []).size !== (step.allowedRoutes ?? []).length) {
      errors.push(`$.steps[${index}].allowedRoutes: duplicate route`);
    }
    for (const [regionIndex, region] of (step.dynamicRegions ?? []).entries()) {
      if (
        typeof region?.x === "number"
        && typeof region?.y === "number"
        && typeof region?.width === "number"
        && typeof region?.height === "number"
        && (
          region.width <= 0
          || region.height <= 0
          || region.x + region.width > 1
          || region.y + region.height > 1
        )
      ) {
        errors.push(
          `$.steps[${index}].dynamicRegions[${regionIndex}]: region exceeds screen`,
        );
      }
    }
  }
  return errors;
};

const runReportSemantics = (report) => {
  if (report === null || typeof report !== "object" || Array.isArray(report)) {
    return [];
  }
  const errors = [];
  if (
    report.status === "passed" && report.errorCode !== null
    || report.status !== "passed" && report.errorCode === null
  ) {
    errors.push("$.errorCode: status and error code are inconsistent");
  }
  const steps = Array.isArray(report.steps) ? report.steps : [];
  const hasUnknownCommit = steps.some((step) => step?.actionCommits === null);
  const commits = steps.reduce(
    (total, step) => total + (Number.isInteger(step?.actionCommits)
      ? step.actionCommits
      : 0),
    0,
  );
  if (
    hasUnknownCommit && report.actionCommits !== null
    || !hasUnknownCommit
      && Number.isInteger(report.actionCommits)
      && report.actionCommits !== commits
    || !hasUnknownCommit && report.actionCommits === null
  ) {
    errors.push("$.actionCommits: does not equal step commits");
  }
  steps.forEach((step, index) => {
    if (step?.sequence !== index + 1) {
      errors.push(`$.steps[${index}].sequence: sequence is not contiguous`);
    }
    if (
      step?.status === "passed" && step?.errorCode !== null
      || step?.status !== "passed" && step?.errorCode === null
    ) {
      errors.push(`$.steps[${index}].errorCode: status and error code are inconsistent`);
    }
    if (step?.actionCommits === 0 && step?.postObservationId !== null) {
      errors.push(`$.steps[${index}].postObservationId: zero commit cannot have post observation`);
    }
    if (step?.status === "passed" && step?.actionCommits !== 1) {
      errors.push(`$.steps[${index}].actionCommits: passed step must commit once`);
    }
    if (
      step?.actionCommits === null
      && step?.errorCode !== "ACTION_COMMIT_UNKNOWN"
    ) {
      errors.push(
        `$.steps[${index}].errorCode: unknown commit requires stable code`,
      );
    }
  });
  const cleanup = report.cleanup;
  if (
    cleanup
    && Number.isInteger(cleanup.attempted)
    && Number.isInteger(cleanup.completed)
    && (
      cleanup.completed > cleanup.attempted
      || cleanup.completed === cleanup.attempted && cleanup.errorCode !== null
      || cleanup.completed < cleanup.attempted
        && cleanup.errorCode !== "SCENARIO_CLEANUP_FAILED"
    )
  ) {
    errors.push("$.cleanup: completion and error code are inconsistent");
  }
  return errors;
};

const compatibilitySemantics = (summary) => {
  if (summary === null || typeof summary !== "object" || Array.isArray(summary)) {
    return [];
  }
  const errors = [];
  if (
    Number.isInteger(summary.runs)
    && Number.isInteger(summary.passedRuns)
    && Number.isInteger(summary.failedRuns)
    && Number.isInteger(summary.cancelledRuns)
    && summary.passedRuns + summary.failedRuns + summary.cancelledRuns !== summary.runs
  ) {
    errors.push("$: run status counts do not equal runs");
  }
  if (
    Number.isInteger(summary.runs)
    && summary.runs > 0
    && typeof summary.successRate === "number"
    && summary.successRate !== summary.passedRuns / summary.runs
  ) {
    errors.push("$.successRate: does not match passed run count");
  }
  return errors;
};

export const loadRunnerSchemas = async () => {
  const entries = await Promise.all(
    Object.entries(schemaFiles).map(async ([name, file]) => [
      name,
      JSON.parse(await readFile(path.join(schemaDirectory, file), "utf8")),
    ]),
  );
  return Object.freeze(Object.fromEntries(entries));
};

export const validateRunnerDocument = (schema, value) => {
  const errors = validateNode(schema, value, schema, "$", "#");
  if (schema?.$id?.endsWith("/scenario.schema.json")) {
    errors.push(...scenarioSemantics(value));
  }
  if (schema?.$id?.endsWith("/run-report.schema.json")) {
    errors.push(...runReportSemantics(value));
  }
  if (schema?.$id?.endsWith("/compatibility-summary.schema.json")) {
    errors.push(...compatibilitySemantics(value));
  }
  return errors;
};

export const assertRunnerDocument = (schema, value, code = "SCHEMA_INVALID") => {
  const errors = validateRunnerDocument(schema, value);
  if (errors.length > 0) {
    const error = new Error(code);
    error.code = code;
    error.validationErrors = Object.freeze([...errors]);
    throw error;
  }
};
