// 验证协议 Schema、合法/非法夹具以及版本协商兼容性。
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const protocolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const schemaRoot = path.join(protocolRoot, "schema", "v1");
const draft202012 = "https://json-schema.org/draft/2020-12/schema";

const readJson = async (file) => JSON.parse(await readFile(file, "utf8"));
const deepEqual = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};
const typeOf = (value) => {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Number.isInteger(value)) return "integer";
  return typeof value === "object" ? "object" : typeof value;
};

const parseProtocolVersion = (version) => {
  const match = /^([1-9][0-9]*)\.([0-9]+)$/.exec(version);
  if (!match) throw new Error(`Invalid protocol version: ${version}`);
  return { text: version, major: Number(match[1]), minor: Number(match[2]) };
};

const negotiateProtocolVersion = (localVersions, remoteVersions, incompatibleError) => {
  const remote = new Set(remoteVersions);
  const common = localVersions
    .filter((candidate) => remote.has(candidate))
    .map(parseProtocolVersion)
    .sort((left, right) => right.major - left.major || right.minor - left.minor);
  return common.length > 0
    ? { version: common[0].text, error: null }
    : { version: null, error: incompatibleError };
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
const schemaByName = (name) => schemaFiles.find((entry) => entry.name === name)?.schema;

assert(
  common.$defs.deadlineMs.maximum === limits.maxDeadlineMs,
  "limits.maxDeadlineMs does not match common.schema.json",
);
assert(version.supported.includes(version.current), "version.current must be included in version.supported");
assert(
  new Set(version.supported).size === version.supported.length,
  "version.supported must not contain duplicate versions",
);
version.supported.forEach(parseProtocolVersion);
assert(limits.protocolVersion === version.current, "limits.protocolVersion must match version.current");
assert(
  limits.defaultDeadlineMs > 0 && limits.defaultDeadlineMs <= limits.maxDeadlineMs,
  "limits.defaultDeadlineMs must be positive and no greater than maxDeadlineMs",
);
assert(limits.maxMessageBytes > 0, "limits.maxMessageBytes must be positive");
assert(limits.maxConcurrentRequests > 0, "limits.maxConcurrentRequests must be positive");

const expectedCompatibility = {
  sameMajorAllows: [
    "add-optional-field",
    "add-capability",
    "add-enum-value-only-when-capability-gated",
  ],
  requiresNewMajor: [
    "remove-field",
    "change-field-semantics",
    "make-optional-field-required",
    "narrow-valid-range",
    "change-default-behavior",
  ],
  unknownExtensibleObjectFields: "ignore",
  unknownCommandPayloadFields: "reject",
  unknownActionsMethodsAndErrorCodes: "reject",
};
assert(
  version.negotiation.strategy === "highest-common-version",
  "version.negotiation.strategy must be highest-common-version",
);
assert(
  deepEqual(version.compatibility, expectedCompatibility),
  "version.compatibility does not match the schema compatibility policy",
);

const bridgeRequest = schemaByName("bridge-request.schema.json");
const bridgeResponse = schemaByName("bridge-response.schema.json");
const action = schemaByName("action.schema.json");
const errorSchema = schemaByName("error.schema.json");
const cliEnvelope = schemaByName("cli-envelope.schema.json");
const automationScript = schemaByName("automation-script.schema.json");
const recordingListResult = schemaByName("recording-list-result.schema.json");

assert(
  errorSchema.properties.code.enum.includes(version.negotiation.majorMismatchError),
  "version.negotiation.majorMismatchError must be a stable error code",
);
assert(
  cliEnvelope.$defs.success.properties.schemaVersion.const === version.current
    && cliEnvelope.$defs.failure.properties.schemaVersion.const === version.current
    && automationScript.properties.schemaVersion.const === version.current,
  "version.current must match versioned schema constants",
);
assert(
  bridgeRequest.$defs.base.additionalProperties === false,
  "bridge request envelopes must reject unknown fields",
);
for (const [name, definition] of Object.entries(bridgeRequest.$defs)) {
  if (!Array.isArray(definition.allOf)) continue;
  const paramsSchema = definition.allOf.find((branch) => branch.properties?.params)?.properties.params;
  assert(paramsSchema, `bridge request ${name} must declare params`);
  assert(
    paramsSchema.additionalProperties === false,
    `bridge request ${name} params must reject unknown fields`,
  );
}
for (const branch of action.oneOf) {
  const actionDefinition = resolvePointer(action, branch.$ref);
  assert(
    actionDefinition.additionalProperties === false,
    `${branch.$ref} must reject unknown action fields`,
  );
  const paramsSchema = actionDefinition.properties.params;
  const resolvedParams = paramsSchema.$ref
    ? validator.resolveRef(paramsSchema.$ref, action).target
    : paramsSchema;
  assert(
    resolvedParams.additionalProperties === false,
    `${branch.$ref} params must reject unknown command fields`,
  );
}
for (const definition of Object.values(bridgeResponse.$defs)) {
  assert(
    definition.additionalProperties === true,
    "bridge responses must ignore optional fields added by newer minor versions",
  );
}
for (const name of [
  "automation-script.schema.json",
  "capability.schema.json",
  "device.schema.json",
]) {
  assert(
    schemaByName(name).additionalProperties === true,
    `${name} must ignore unknown extensible object fields`,
  );
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

const goRecordingListFixture = await readJson(
  path.join(protocolRoot, "..", "internal", "bridge", "testdata", "recording-list-result.json"),
);
const androidRecordingListFixture = await readJson(
  path.join(
    protocolRoot,
    "..",
    "android",
    "app",
    "src",
    "test",
    "resources",
    "recording-list-result.json",
  ),
);
assert(
  deepEqual(goRecordingListFixture, androidRecordingListFixture),
  "Go and Android recording.list fixtures must be identical",
);
const recordingListErrors = validator.validate(recordingListResult, goRecordingListFixture);
assert(
  recordingListErrors.length === 0,
  `recording.list fixture does not match the protocol schema:\n${recordingListErrors.join("\n")}`,
);

const compatibilityChecks = [
  {
    name: "highest common protocol version is selected numerically",
    run: () => {
      const result = negotiateProtocolVersion(
        ["1.0", "1.2", "1.10"],
        ["1.1", "1.2", "1.10"],
        version.negotiation.majorMismatchError,
      );
      assert(result.version === "1.10" && result.error === null, `negotiated ${JSON.stringify(result)}`);
    },
  },
  {
    name: "different majors fail with the configured stable error",
    run: () => {
      const result = negotiateProtocolVersion(
        ["1.0", "1.1"],
        ["2.0", "2.1"],
        version.negotiation.majorMismatchError,
      );
      assert(
        result.version === null && result.error === "VERSION_INCOMPATIBLE",
        `negotiated ${JSON.stringify(result)}`,
      );
    },
  },
  {
    name: "same-major optional response fields are ignored",
    run: () => {
      const errors = validator.validate(bridgeResponse, {
        jsonrpc: "2.0",
        id: "1285a189-dd86-445c-b3b3-379848bd20a0",
        requestId: "1285a189-dd86-445c-b3b3-379848bd20a0",
        protocolVersion: "1.1",
        result: { accepted: true, optionalResultField: "introduced-in-1.1" },
        optionalResponseField: "introduced-in-1.1",
      });
      assert(errors.length === 0, errors.join("\n"));
    },
  },
  {
    name: "unknown request fields are rejected",
    run: () => {
      const errors = validator.validate(bridgeRequest, {
        jsonrpc: "2.0",
        id: "a20d1e2c-4411-4575-998d-4f8f47ca9012",
        requestId: "a20d1e2c-4411-4575-998d-4f8f47ca9012",
        protocolVersion: "1.0",
        method: "rpc.hello",
        params: {
          clientVersion: "0.1.0",
          supportedProtocolVersions: ["1.0"],
          capabilities: [],
          optionalCommandField: "must-not-be-ignored",
        },
        deadlineMs: 5000,
      });
      assert(errors.some((error) => error.includes("additionalProperties")), errors.join("\n"));
    },
  },
];
for (const check of compatibilityChecks) {
  try {
    check.run();
  } catch (error) {
    throw new Error(`Compatibility check failed: ${check.name}\n${error.message}`);
  }
}

console.log(
  `Protocol validation passed: ${schemaFiles.length} schemas, `
  + `${fixtures.valid.length} valid fixtures, ${fixtures.invalid.length} invalid fixtures, `
  + `${compatibilityChecks.length} compatibility checks (${passed + compatibilityChecks.length} total).`,
);
