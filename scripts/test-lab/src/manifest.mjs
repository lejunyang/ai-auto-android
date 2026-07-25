// 功能用途：严格解析第三方 App 文本 manifest，拒绝未知字段、不安全来源和模糊制品身份。
import { readFile } from "node:fs/promises";

const manifestKeys = [
  "abi",
  "licenseNote",
  "minSdk",
  "package",
  "schemaVersion",
  "sha256",
  "signingCertificateSha256",
  "sizeBytes",
  "source",
  "version",
  "versionCode",
];
const allowedAbis = new Set([
  "arm64-v8a",
  "armeabi-v7a",
  "x86",
  "x86_64",
]);
const packageNamePattern =
  /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const digestPattern = /^[0-9a-f]{64}$/u;
const maximumManifestBytes = 64 * 1024;

export class ExternalAppError extends Error {
  constructor(code) {
    super(code);
    this.name = "ExternalAppError";
    this.code = code;
  }
}

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const safeText = (value, maximumLength = 1024) =>
  typeof value === "string"
  && value.trim() === value
  && value.length >= 1
  && value.length <= maximumLength
  && !/[\u0000-\u001f\u007f]/u.test(value);

const parseStrictJson = (text) => {
  let offset = 0;

  const fail = () => {
    throw new ExternalAppError("MANIFEST_INVALID");
  };
  const skipWhitespace = () => {
    while (/[\u0009\u000a\u000d\u0020]/u.test(text[offset] ?? "")) offset += 1;
  };
  const parseString = () => {
    if (text[offset] !== "\"") fail();
    const start = offset;
    offset += 1;
    while (offset < text.length) {
      const character = text[offset];
      if (character === "\"") {
        offset += 1;
        try {
          return JSON.parse(text.slice(start, offset));
        } catch {
          fail();
        }
      }
      if (character === "\\") {
        offset += 2;
      } else {
        offset += 1;
      }
    }
    fail();
  };
  const parseNumber = () => {
    const match = text.slice(offset).match(
      /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/u,
    );
    if (!match) fail();
    offset += match[0].length;
    const value = Number(match[0]);
    if (!Number.isFinite(value)) fail();
    return value;
  };
  const parseArray = () => {
    const result = [];
    offset += 1;
    skipWhitespace();
    if (text[offset] === "]") {
      offset += 1;
      return result;
    }
    while (offset < text.length) {
      result.push(parseValue());
      skipWhitespace();
      if (text[offset] === "]") {
        offset += 1;
        return result;
      }
      if (text[offset] !== ",") fail();
      offset += 1;
      skipWhitespace();
    }
    fail();
  };
  const parseObject = () => {
    const result = Object.create(null);
    const keys = new Set();
    offset += 1;
    skipWhitespace();
    if (text[offset] === "}") {
      offset += 1;
      return result;
    }
    while (offset < text.length) {
      const key = parseString();
      if (keys.has(key)) fail();
      keys.add(key);
      skipWhitespace();
      if (text[offset] !== ":") fail();
      offset += 1;
      skipWhitespace();
      result[key] = parseValue();
      skipWhitespace();
      if (text[offset] === "}") {
        offset += 1;
        return result;
      }
      if (text[offset] !== ",") fail();
      offset += 1;
      skipWhitespace();
    }
    fail();
  };
  const parseValue = () => {
    skipWhitespace();
    const character = text[offset];
    if (character === "{") return parseObject();
    if (character === "[") return parseArray();
    if (character === "\"") return parseString();
    for (const [literal, value] of [
      ["true", true],
      ["false", false],
      ["null", null],
    ]) {
      if (text.startsWith(literal, offset)) {
        offset += literal.length;
        return value;
      }
    }
    return parseNumber();
  };

  if (typeof text !== "string" || text.length === 0 || text.charCodeAt(0) === 0xfeff) {
    fail();
  }
  const value = parseValue();
  skipWhitespace();
  if (offset !== text.length) fail();
  return value;
};

const validateSource = (source) => {
  if (source?.type === "user-provided") {
    if (
      !exactKeys(source, ["note", "type"])
      || !safeText(source.note)
    ) {
      throw new ExternalAppError(
        Object.prototype.hasOwnProperty.call(source ?? {}, "url")
          ? "SOURCE_UNSAFE"
          : "MANIFEST_INVALID",
      );
    }
    return Object.freeze({ type: source.type, note: source.note });
  }
  if (source?.type === "https") {
    if (
      !exactKeys(source, ["note", "type", "url"])
      || !safeText(source.note)
      || !safeText(source.url, 2048)
    ) {
      throw new ExternalAppError("MANIFEST_INVALID");
    }
    let parsed;
    try {
      parsed = new URL(source.url);
    } catch {
      throw new ExternalAppError("SOURCE_UNSAFE");
    }
    if (
      parsed.protocol !== "https:"
      || parsed.username !== ""
      || parsed.password !== ""
      || parsed.hostname === ""
      || parsed.search !== ""
      || parsed.hash !== ""
    ) {
      throw new ExternalAppError("SOURCE_UNSAFE");
    }
    return Object.freeze({
      type: source.type,
      url: source.url,
      note: source.note,
    });
  }
  throw new ExternalAppError("MANIFEST_INVALID");
};

export const validateExternalAppManifest = (value) => {
  if (
    !exactKeys(value, manifestKeys)
    || value.schemaVersion !== "1.0"
    || !packageNamePattern.test(value.package ?? "")
    || !safeText(value.version, 128)
    || !Number.isSafeInteger(value.versionCode)
    || value.versionCode < 1
    || !Array.isArray(value.abi)
    || value.abi.length < 1
    || value.abi.length > allowedAbis.size
    || new Set(value.abi).size !== value.abi.length
    || value.abi.some((abi) => !allowedAbis.has(abi))
    || !Number.isInteger(value.minSdk)
    || value.minSdk < 1
    || value.minSdk > 100
    || !safeText(value.licenseNote, 2048)
    || !Number.isSafeInteger(value.sizeBytes)
    || value.sizeBytes < 1
    || !digestPattern.test(value.sha256 ?? "")
    || !digestPattern.test(value.signingCertificateSha256 ?? "")
  ) {
    throw new ExternalAppError("MANIFEST_INVALID");
  }
  const source = validateSource(value.source);
  const abi = Object.freeze([...value.abi]);
  return Object.freeze({
    schemaVersion: value.schemaVersion,
    package: value.package,
    version: value.version,
    versionCode: value.versionCode,
    abi,
    minSdk: value.minSdk,
    source,
    licenseNote: value.licenseNote,
    sizeBytes: value.sizeBytes,
    sha256: value.sha256,
    signingCertificateSha256: value.signingCertificateSha256,
  });
};

export const loadExternalAppManifest = async (manifestPath) => {
  let bytes;
  try {
    bytes = await readFile(manifestPath);
  } catch {
    throw new ExternalAppError("MANIFEST_READ_FAILED");
  }
  if (bytes.length === 0 || bytes.length > maximumManifestBytes) {
    throw new ExternalAppError("MANIFEST_INVALID");
  }
  return validateExternalAppManifest(parseStrictJson(bytes.toString("utf8")));
};
