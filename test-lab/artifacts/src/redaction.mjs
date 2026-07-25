// 功能用途：统一过滤设备文本产物中的秘密与编码变体，无法证明安全时拒绝继续保留。
const redacted = "[REDACTED]";
const sensitiveKey = /(?:password|passwd|pwd|verification[_ -]?code|one[_ -]?time[_ -]?(?:code|password)|otp(?:[_ -]?code)?|access[_ -]?token|refresh[_ -]?token|auth(?:orization)?|bearer|token|pairing[_ -]?code|pair[_ -]?code|api[_ -]?key|x-api-key|client[_ -]?secret|secret)/iu;
const sensitiveAssignment = new RegExp(
  `((?:password|passwd|pwd|verification[_ -]?code|one[_ -]?time[_ -]?(?:code|password)|otp(?:[_ -]?code)?|access[_ -]?token|refresh[_ -]?token|auth(?:orization)?|bearer|token|pairing[_ -]?code|pair[_ -]?code|api[_ -]?key|x-api-key|client[_ -]?secret|secret)[\\t ]*(?::|=|[\\t ])[\t ]*)(?!\\[REDACTED\\])("[^"\\r\\n]*"|'[^'\\r\\n]*'|[^\\s,;<>{}\\[\\]]+)`,
  "giu",
);
const bearerAssignment =
  /((?:authorization[\t ]*:[\t ]*)?bearer[\t ]+)(?!\[REDACTED\])([^\s,;<>{}\[\]]+)/giu;

export class RedactionError extends Error {
  constructor(code) {
    super(code);
    this.name = "RedactionError";
    this.code = code;
  }
}

const decodeUtf8 = (value) => {
  if (typeof value === "string") return value;
  if (!Buffer.isBuffer(value) && !(value instanceof Uint8Array)) {
    throw new RedactionError("TEXT_ENCODING_UNVERIFIED");
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(value);
  } catch {
    throw new RedactionError("TEXT_ENCODING_UNVERIFIED");
  }
};

const escapePattern = (value) =>
  value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");

const variantsFor = (value) => {
  const bytes = Buffer.from(value, "utf8");
  return new Set([
    value,
    encodeURIComponent(value),
    bytes.toString("base64"),
    bytes.toString("base64url"),
    bytes.toString("hex"),
  ].filter((candidate) => candidate.length > 0));
};

const redactStructured = (value, explicitValues, state) => {
  if (Array.isArray(value)) {
    return value.map((item) => redactStructured(item, explicitValues, state));
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => {
        if (sensitiveKey.test(key)) {
          state.count += 1;
          return [key, redacted];
        }
        return [key, redactStructured(item, explicitValues, state)];
      }),
    );
  }
  if (typeof value !== "string") return value;
  return replaceExplicitVariants(value, explicitValues, state);
};

const replaceExplicitVariants = (text, explicitValues, state) => {
  let output = text;
  const variants = explicitValues
    .flatMap((value) => [...variantsFor(value)])
    .sort((left, right) => right.length - left.length);
  for (const variant of variants) {
    const pattern = new RegExp(escapePattern(variant), "giu");
    output = output.replace(pattern, () => {
      state.count += 1;
      return redacted;
    });
  }
  return output;
};

const decodedTokenIsSensitive = (token, explicitValues) => {
  const decoders = [];
  if (/^[A-Fa-f0-9]{16,}$/u.test(token) && token.length % 2 === 0) {
    decoders.push(() => Buffer.from(token, "hex"));
  }
  if (/^[A-Za-z0-9+/_-]{12,}={0,2}$/u.test(token)) {
    decoders.push(() => Buffer.from(token, "base64"));
    decoders.push(() => Buffer.from(token, "base64url"));
  }
  for (const decode of decoders) {
    try {
      const decoded = new TextDecoder("utf-8", { fatal: true }).decode(decode());
      if (sensitiveKey.test(decoded)) return true;
      const normalized = decoded.toLocaleLowerCase("en-US");
      if (explicitValues.some((value) =>
        normalized.includes(value.toLocaleLowerCase("en-US")))) {
        return true;
      }
    } catch {
      // 不是可判定 UTF-8 的编码 token 时继续按普通文本处理。
    }
  }
  return false;
};

const redactEncodedTokens = (text, explicitValues, state) =>
  text.replace(/[A-Za-z0-9+/_-]{12,}={0,2}|[A-Fa-f0-9]{16,}/gu, (token) => {
    if (!decodedTokenIsSensitive(token, explicitValues)) return token;
    state.count += 1;
    return redacted;
  });

const normalizedValues = (sensitivity) => {
  if (sensitivity?.declaration !== "complete") {
    throw new RedactionError("SENSITIVE_DECLARATION_INCOMPLETE");
  }
  if (!Array.isArray(sensitivity.values)) {
    throw new RedactionError("SENSITIVE_DECLARATION_INCOMPLETE");
  }
  return [...new Set(
    sensitivity.values
      .filter((value) => typeof value === "string")
      .map((value) => value.trim())
      .filter(Boolean),
  )];
};

export const containsSensitiveData = (textValue, declaredValues = []) => {
  const text = decodeUtf8(textValue);
  bearerAssignment.lastIndex = 0;
  if (bearerAssignment.test(text)) return true;
  sensitiveAssignment.lastIndex = 0;
  if (sensitiveAssignment.test(text)) return true;
  const normalized = text.toLocaleLowerCase("en-US");
  for (const value of declaredValues) {
    for (const variant of variantsFor(value)) {
      if (normalized.includes(variant.toLocaleLowerCase("en-US"))) return true;
    }
  }
  const encodedTokens = text.match(
    /[A-Za-z0-9+/_-]{12,}={0,2}|[A-Fa-f0-9]{16,}/gu,
  ) ?? [];
  return encodedTokens.some((token) =>
    decodedTokenIsSensitive(token, declaredValues));
};

export const redactText = (textValue, sensitivity) => {
  const explicitValues = normalizedValues(sensitivity);
  const state = { count: 0 };
  let text = decodeUtf8(textValue);

  try {
    const parsed = JSON.parse(text);
    text = JSON.stringify(redactStructured(parsed, explicitValues, state));
  } catch (error) {
    if (error instanceof RedactionError) throw error;
  }

  text = replaceExplicitVariants(text, explicitValues, state);
  text = redactEncodedTokens(text, explicitValues, state);
  text = text.replace(
    /(?:authorization[\t ]*:[\t ]*)?bearer[\t ]+\[REDACTED\]/giu,
    redacted,
  );
  text = text.replace(bearerAssignment, () => {
    state.count += 1;
    return redacted;
  });
  text = text.replace(sensitiveAssignment, (_match, prefix) => {
    state.count += 1;
    return `${prefix}${redacted}`;
  });

  if (containsSensitiveData(text, explicitValues)) {
    throw new RedactionError("REDACTION_UNVERIFIED");
  }
  return {
    text,
    redactions: state.count,
    verified: true,
  };
};
