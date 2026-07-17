import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const protocolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const schemaRoot = path.join(protocolRoot, "schema", "v1");
const draft202012 = "https://json-schema.org/draft/2020-12/schema";

const readJson = async (file) => JSON.parse(await readFile(file, "utf8"));
const deepEqual = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const typeOf = (value) => {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Number.isInteger(value)) return "integer";
  return typeof value === "object" ? "object" : typeof value;
};

const escapeJsonPointer = (part) => part.replaceAll("~", "~0").replaceAll("/", "~1");
const resolvePointer = (document, fragment) => {
  if (!fragment || fragment === "#") return document;
  if (!fragment.startsWith("#/")) throw new Error(`Unsupported JSON pointer: ${fragment}`);

  return fragment
    .slice(2)
    .split("/")
    .map((part) => part.replaceAll("~1", "/").replaceAll("~0", "~"))
    .reduce((current, part) => current?.[part], document);
};

const matchesType = (value, expected) => {
  if (expected === "number") return typeof value === "number" && Number.isFinite(value);
  if (expected === "integer") return Number.isInteger(value);
  if (expected === "object") return value !== null && typeof value === "object" && !Array.isArray(value);
  if (expected === "array") return Array.isArray(value);
  if (expected === "null") return value === null;
  return typeof value === expected;
};

const checkFormat = (value, format) => {
  if (format === "uuid") {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  }
  if (format === "date-time") {
    return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
      && Number.isFinite(Date.parse(value));
  }
  return true;
};

class SchemaValidator {
  constructor(schemaFiles) {
    this.schemaFiles = schemaFiles;
    this.schemasById = new Map(schemaFiles.map(({ schema }) => [schema.$id, schema]));
  }

  resolveRef(reference, currentDocument) {
    const [documentRef, rawFragment] = reference.split("#", 2);
    const fragment = rawFragment === undefined ? "" : `#${rawFragment}`;
    let document = currentDocument;

    if (documentRef) {
      const resolvedId = new URL(documentRef, currentDocument.$id).href;
      document = this.schemasById.get(resolvedId);
      if (!document) throw new Error(`Unresolved $ref: ${reference} from ${currentDocument.$id}`);
    }

    const target = resolvePointer(document, fragment);
    if (target === undefined) throw new Error(`Missing JSON pointer in $ref: ${reference}`);
    return { target, document };
  }

  validate(schema, data, currentDocument = schema, dataPath = "$", schemaPath = "#") {
    if (schema === true) return [];
    if (schema === false) return [`${dataPath}: ${schemaPath} is false`];

    if (schema.$ref) {
      const { target, document } = this.resolveRef(schema.$ref, currentDocument);
      return this.validate(target, data, document, dataPath, `${schemaPath}/$ref(${schema.$ref})`);
    }

    const errors = [];
    if (schema.allOf) {
      schema.allOf.forEach((branch, index) => {
        errors.push(...this.validate(branch, data, currentDocument, dataPath, `${schemaPath}/allOf/${index}`));
      });
    }
    if (schema.anyOf) {
      const branchErrors = schema.anyOf.map((branch, index) =>
        this.validate(branch, data, currentDocument, dataPath, `${schemaPath}/anyOf/${index}`),
      );
      if (!branchErrors.some((branch) => branch.length === 0)) {
        errors.push(`${dataPath}: anyOf matched no branches`);
        errors.push(...branchErrors.flat());
      }
    }
    if (schema.oneOf) {
      const branchErrors = schema.oneOf.map((branch, index) =>
        this.validate(branch, data, currentDocument, dataPath, `${schemaPath}/oneOf/${index}`),
      );
      const matches = branchErrors.filter((branch) => branch.length === 0).length;
      if (matches !== 1) {
        errors.push(`${dataPath}: oneOf expected exactly one match, got ${matches}`);
        errors.push(...branchErrors.flat());
      }
    }

    if (schema.type) {
      const types = Array.isArray(schema.type) ? schema.type : [schema.type];
      if (!types.some((expected) => matchesType(data, expected))) {
        errors.push(`${dataPath}: type expected ${types.join("|")}, got ${typeOf(data)}`);
        return errors;
      }
    }
    if ("const" in schema && !deepEqual(data, schema.const)) {
      errors.push(`${dataPath}: const expected ${JSON.stringify(schema.const)}`);
    }
    if (schema.enum && !schema.enum.some((candidate) => deepEqual(candidate, data))) {
      errors.push(`${dataPath}: enum does not contain ${JSON.stringify(data)}`);
    }

    if (typeof data === "string") {
      if (schema.minLength !== undefined && [...data].length < schema.minLength) {
        errors.push(`${dataPath}: minLength is ${schema.minLength}`);
      }
      if (schema.maxLength !== undefined && [...data].length > schema.maxLength) {
        errors.push(`${dataPath}: maxLength is ${schema.maxLength}`);
      }
      if (schema.pattern && !new RegExp(schema.pattern, "u").test(data)) {
        errors.push(`${dataPath}: pattern ${schema.pattern} did not match`);
      }
      if (schema.format && !checkFormat(data, schema.format)) {
        errors.push(`${dataPath}: format ${schema.format} is invalid`);
      }
    }

    if (typeof data === "number" && Number.isFinite(data)) {
      if (schema.minimum !== undefined && data < schema.minimum) {
        errors.push(`${dataPath}: minimum is ${schema.minimum}`);
      }
      if (schema.maximum !== undefined && data > schema.maximum) {
        errors.push(`${dataPath}: maximum is ${schema.maximum}`);
      }
      if (schema.exclusiveMinimum !== undefined && data <= schema.exclusiveMinimum) {
        errors.push(`${dataPath}: exclusiveMinimum is ${schema.exclusiveMinimum}`);
      }
      if (schema.exclusiveMaximum !== undefined && data >= schema.exclusiveMaximum) {
        errors.push(`${dataPath}: exclusiveMaximum is ${schema.exclusiveMaximum}`);
      }
    }

    if (Array.isArray(data)) {
      if (schema.minItems !== undefined && data.length < schema.minItems) {
        errors.push(`${dataPath}: minItems is ${schema.minItems}`);
      }
      if (schema.maxItems !== undefined && data.length > schema.maxItems) {
        errors.push(`${dataPath}: maxItems is ${schema.maxItems}`);
      }
      if (schema.uniqueItems) {
        const serialized = data.map((item) => JSON.stringify(item));
        if (new Set(serialized).size !== serialized.length) {
          errors.push(`${dataPath}: uniqueItems contains duplicates`);
        }
      }
      if (schema.items) {
        data.forEach((item, index) => {
          errors.push(...this.validate(schema.items, item, currentDocument, `${dataPath}[${index}]`, `${schemaPath}/items`));
        });
      }
    }

    if (data !== null && typeof data === "object" && !Array.isArray(data)) {
      const keys = Object.keys(data);
      if (schema.minProperties !== undefined && keys.length < schema.minProperties) {
        errors.push(`${dataPath}: minProperties is ${schema.minProperties}`);
      }
      if (schema.maxProperties !== undefined && keys.length > schema.maxProperties) {
        errors.push(`${dataPath}: maxProperties is ${schema.maxProperties}`);
      }
      if (schema.required) {
        for (const property of schema.required) {
          if (!Object.hasOwn(data, property)) errors.push(`${dataPath}: required property ${property} is missing`);
        }
      }
      if (schema.properties) {
        for (const [property, propertySchema] of Object.entries(schema.properties)) {
          if (Object.hasOwn(data, property)) {
            errors.push(
              ...this.validate(
                propertySchema,
                data[property],
                currentDocument,
                `${dataPath}.${property}`,
                `${schemaPath}/properties/${escapeJsonPointer(property)}`,
              ),
            );
          }
        }
      }
      const knownProperties = new Set(Object.keys(schema.properties ?? {}));
      const extras = keys.filter((key) => !knownProperties.has(key));
      if (schema.additionalProperties === false && extras.length > 0) {
        errors.push(`${dataPath}: additionalProperties are not allowed: ${extras.join(", ")}`);
      } else if (schema.additionalProperties && typeof schema.additionalProperties === "object") {
        for (const property of extras) {
          errors.push(
            ...this.validate(
              schema.additionalProperties,
              data[property],
              currentDocument,
              `${dataPath}.${property}`,
              `${schemaPath}/additionalProperties`,
            ),
          );
        }
      }
    }

    return errors;
  }
}

const schemaNames = (await readdir(schemaRoot)).filter((name) => name.endsWith(".schema.json")).sort();
const schemaFiles = await Promise.all(
  schemaNames.map(async (name) => ({ name, schema: await readJson(path.join(schemaRoot, name)) })),
);

const ids = new Set();
for (const { name, schema } of schemaFiles) {
  if (schema.$schema !== draft202012) throw new Error(`${name}: expected Draft 2020-12`);
  if (!schema.$id) throw new Error(`${name}: missing $id`);
  if (ids.has(schema.$id)) throw new Error(`${name}: duplicate $id ${schema.$id}`);
  ids.add(schema.$id);
}

const validator = new SchemaValidator(schemaFiles);
for (const { schema } of schemaFiles) {
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (node.$ref) validator.resolveRef(node.$ref, schema);
    Object.values(node).forEach(visit);
  };
  visit(schema);
}

const limits = await readJson(path.join(protocolRoot, "limits.json"));
const version = await readJson(path.join(protocolRoot, "version.json"));
const common = schemaFiles.find(({ name }) => name === "common.schema.json").schema;
if (common.$defs.deadlineMs.maximum !== limits.maxDeadlineMs) {
  throw new Error("limits.maxDeadlineMs does not match common.schema.json");
}
if (!version.supported.includes(version.current)) {
  throw new Error("version.current must be included in version.supported");
}
if (limits.protocolVersion !== version.current) {
  throw new Error("limits.protocolVersion must match version.current");
}

const fixtures = await readJson(path.join(protocolRoot, "fixtures", "manifest.json"));
let passed = 0;
for (const fixture of fixtures.valid) {
  const entry = schemaFiles.find(({ name }) => name === fixture.schema);
  if (!entry) throw new Error(`${fixture.name}: unknown schema ${fixture.schema}`);
  const errors = validator.validate(entry.schema, fixture.data);
  if (errors.length > 0) {
    throw new Error(`${fixture.name}: expected valid\n${errors.map((error) => `  - ${error}`).join("\n")}`);
  }
  passed += 1;
}

for (const fixture of fixtures.invalid) {
  const entry = schemaFiles.find(({ name }) => name === fixture.schema);
  if (!entry) throw new Error(`${fixture.name}: unknown schema ${fixture.schema}`);
  const errors = validator.validate(entry.schema, fixture.data);
  if (errors.length === 0) throw new Error(`${fixture.name}: expected invalid but validation passed`);
  if (fixture.expectedError && !errors.some((error) => error.includes(fixture.expectedError))) {
    throw new Error(
      `${fixture.name}: expected an error containing ${fixture.expectedError}\n`
      + errors.map((error) => `  - ${error}`).join("\n"),
    );
  }
  passed += 1;
}

console.log(
  `Protocol validation passed: ${schemaFiles.length} schemas, `
  + `${fixtures.valid.length} valid fixtures, ${fixtures.invalid.length} invalid fixtures (${passed} total).`,
);
