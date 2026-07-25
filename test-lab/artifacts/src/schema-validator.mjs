// 功能用途：离线加载并严格验证 N34 结果 Schema，阻止未知字段或非法标识进入产物路径。
import { readFile } from "node:fs/promises";
import path from "node:path";

const schemaDirectory = path.resolve(import.meta.dirname, "..", "schema");

const schemaFiles = {
  scenarioResult: "scenario-result.schema.json",
  artifactSummary: "artifact-summary.schema.json",
  blockedSummary: "blocked-summary.schema.json",
  twentyRunStatistics: "twenty-run-statistics.schema.json",
};

const valueType = (value) => {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Number.isInteger(value)) return "integer";
  return typeof value === "object" ? "object" : typeof value;
};

const matchesType = (value, expected) => {
  if (expected === "integer") return Number.isInteger(value);
  if (expected === "number") return typeof value === "number" && Number.isFinite(value);
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
    }
  }
  return errors;
};

export const loadArtifactSchemas = async () => {
  const entries = await Promise.all(
    Object.entries(schemaFiles).map(async ([name, file]) => [
      name,
      JSON.parse(await readFile(path.join(schemaDirectory, file), "utf8")),
    ]),
  );
  return Object.fromEntries(entries);
};

export const validateArtifactDocument = (schema, value) =>
  validateNode(schema, value, schema, "$", "#");

export const assertArtifactDocument = (schema, value, code = "SCHEMA_INVALID") => {
  const errors = validateArtifactDocument(schema, value);
  if (errors.length > 0) {
    const error = new Error(`${code}: ${errors.join("; ")}`);
    error.code = code;
    error.validationErrors = errors;
    throw error;
  }
};
