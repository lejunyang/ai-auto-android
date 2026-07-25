// 模块用途：提供 LAN invitation 的无副作用预检、规范 transcript 与密钥分域参考实现。
import {
  createPublicKey,
  createHash,
  createHmac,
  diffieHellman,
  hkdfSync,
  timingSafeEqual,
} from "node:crypto";
import { isIP } from "node:net";

const INVITATION_FINGERPRINT_DOMAIN = "AIAUTO-LAN-INVITATION-FINGERPRINT-V1";
const TRANSCRIPT_DOMAIN = "AIAUTO-LAN-BRIDGE-TRANSCRIPT-V1";
const HKDF_SALT_DOMAIN = "AIAUTO-LAN-BRIDGE-HKDF-SALT-V1";
const HKDF_INFO_DOMAIN = "AIAUTO-LAN-BRIDGE-KEYS-V1";
const CONFIRMATION_DOMAIN = "AIAUTO-LAN-BRIDGE-CONFIRMATION-V1";
const REQUIRED_CAPABILITIES = [
  "lan.bridge.mutual-confirmation.v1",
  "lan.bridge.rpc.v1",
];
const MIN_TTL_SECONDS = 15;
const MAX_TTL_SECONDS = 120;
const X25519_SPKI_PREFIX = Buffer.from("302a300506032b656e032100", "hex");

export const LAN_INVITATION_ERROR_CODES = [
  "LAN_INVITATION_SCHEMA_INVALID",
  "LAN_VERSION_INCOMPATIBLE",
  "LAN_DOWNGRADE_REJECTED",
  "LAN_INVITATION_TIME_INVALID",
  "LAN_INVITATION_TTL_INVALID",
  "LAN_INVITATION_TTL_MISMATCH",
  "LAN_INVITATION_NOT_YET_VALID",
  "LAN_INVITATION_EXPIRED",
  "LAN_INVITATION_REPLAYED",
  "LAN_NONCE_REPLAYED",
  "LAN_ADDRESS_INVALID",
  "LAN_ADDRESS_UNSPECIFIED",
  "LAN_ADDRESS_MULTICAST",
  "LAN_ADDRESS_NOT_PRIVATE",
  "LAN_ADDRESS_SCOPE_MISMATCH",
  "LAN_INTERFACE_MISMATCH",
  "LAN_EPHEMERAL_KEY_INVALID",
  "LAN_EPHEMERAL_KEY_WEAK",
  "LAN_FINGERPRINT_MISMATCH",
  "LAN_CAPABILITY_MISMATCH",
  "LAN_TRANSCRIPT_MISMATCH",
  "LAN_CONFIRMATION_INVALID",
];

export class LanInvitationError extends Error {
  constructor(code, message, options) {
    super(message, options);
    this.name = "LanInvitationError";
    this.code = code;
  }
}

const failure = (code) => ({ ok: false, code });
const success = { ok: true, code: null };

const compareUtf8 = (left, right) =>
  Buffer.from(left, "utf8").compare(Buffer.from(right, "utf8"));

const canonicalize = (value) => {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number" && Number.isSafeInteger(value)) return String(value);
  if (Array.isArray(value)) return `[${value.map(canonicalize).join(",")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value)
      .sort(compareUtf8)
      .map((key) => `${JSON.stringify(key)}:${canonicalize(value[key])}`)
      .join(",")}}`;
  }
  throw new TypeError("LAN canonical JSON only accepts null, booleans, strings, safe integers, arrays, and objects");
};

const decodeBase64Url32 = (value) => {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value)) return null;
  const decoded = Buffer.from(value, "base64url");
  return decoded.length === 32 && decoded.toString("base64url") === value ? decoded : null;
};

const parseIpv4 = (host) => {
  if (isIP(host) !== 4) return null;
  const octets = host.split(".").map(Number);
  if (octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255)) return null;
  return octets;
};

const parseIpv6 = (host) => {
  if (isIP(host) !== 6 || host.includes("%")) return null;
  const halves = host.toLowerCase().split("::");
  if (halves.length > 2) return null;

  const parseHalf = (half) => {
    if (half === "") return [];
    const groups = half.split(":");
    const output = [];
    for (const group of groups) {
      if (group.includes(".")) {
        const ipv4 = parseIpv4(group);
        if (!ipv4) return null;
        output.push((ipv4[0] << 8) | ipv4[1], (ipv4[2] << 8) | ipv4[3]);
      } else {
        if (!/^[0-9a-f]{1,4}$/.test(group)) return null;
        output.push(Number.parseInt(group, 16));
      }
    }
    return output;
  };

  const left = parseHalf(halves[0]);
  const right = parseHalf(halves[1] ?? "");
  if (!left || !right) return null;
  const missing = 8 - left.length - right.length;
  if ((halves.length === 1 && missing !== 0) || (halves.length === 2 && missing < 1)) return null;
  return [...left, ...Array(missing).fill(0), ...right];
};

const classifyIpv4 = (octets) => {
  if (octets.every((octet) => octet === 0)) return "unspecified";
  if (octets[0] >= 224 && octets[0] <= 239) return "multicast";
  if (octets[0] === 127) return "loopback";
  if (octets[0] === 169 && octets[1] === 254) return "linkLocal";
  if (
    octets[0] === 10
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168)
  ) {
    return "private";
  }
  return "public";
};

const classifyIpv6 = (groups) => {
  if (groups.every((group) => group === 0)) return "unspecified";
  if (groups.slice(0, 7).every((group) => group === 0) && groups[7] === 1) return "loopback";
  if ((groups[0] & 0xff00) === 0xff00) return "multicast";
  if ((groups[0] & 0xfe00) === 0xfc00) return "private";
  if ((groups[0] & 0xffc0) === 0xfe80) return "linkLocal";
  return "public";
};

const classifyAddress = (host, family) => {
  if (family === "ipv4") {
    const octets = parseIpv4(host);
    return octets ? classifyIpv4(octets) : "invalid";
  }
  if (family === "ipv6") {
    const groups = parseIpv6(host);
    return groups ? classifyIpv6(groups) : "invalid";
  }
  return "invalid";
};

const checkAddresses = (listener) => {
  if (!listener?.selectedInterface || !Array.isArray(listener.addressCandidates)) {
    return "LAN_INVITATION_SCHEMA_INVALID";
  }
  for (const candidate of listener.addressCandidates) {
    const classification = classifyAddress(candidate.host, candidate.family);
    if (classification === "invalid") return "LAN_ADDRESS_INVALID";
    if (classification === "unspecified") return "LAN_ADDRESS_UNSPECIFIED";
    if (classification === "multicast") return "LAN_ADDRESS_MULTICAST";
    if (classification === "loopback" || classification === "public") {
      return "LAN_ADDRESS_NOT_PRIVATE";
    }
    if (candidate.scope !== classification) return "LAN_ADDRESS_SCOPE_MISMATCH";
    if (candidate.interfaceId !== listener.selectedInterface.id) return "LAN_INTERFACE_MISMATCH";
    if (
      classification === "linkLocal"
      && candidate.family === "ipv6"
      && candidate.zoneId !== listener.selectedInterface.name
    ) {
      return "LAN_INTERFACE_MISMATCH";
    }
    if (classification !== "linkLocal" && candidate.zoneId !== undefined) {
      return "LAN_INTERFACE_MISMATCH";
    }
  }
  return null;
};

const matchesExpectedInterface = (selectedInterface, expectedInterface) =>
  expectedInterface === undefined
  || (
    selectedInterface?.id === expectedInterface.id
    && selectedInterface?.name === expectedInterface.name
    && selectedInterface?.kind === expectedInterface.kind
  );

const withoutFingerprint = (invitation) => {
  const copy = structuredClone(invitation);
  delete copy.fingerprint;
  return copy;
};

export const computeInvitationFingerprint = (invitation) => {
  const digest = createHash("sha256")
    .update(`${INVITATION_FINGERPRINT_DOMAIN}\0`, "utf8")
    .update(canonicalize(withoutFingerprint(invitation)), "utf8")
    .digest("hex")
    .slice(0, 16)
    .toUpperCase();
  return digest.match(/.{4}/g).join("-");
};

export const validateLanInvitationPreflight = (invitation, context = {}) => {
  if (invitation?.kind !== "ai-auto-lan-invitation" || invitation?.version !== "1.0") {
    return failure("LAN_VERSION_INCOMPATIBLE");
  }
  if (
    invitation?.security?.suite !== "X25519-HKDF-SHA256-AES-256-GCM"
    || invitation?.security?.transcript !== TRANSCRIPT_DOMAIN
    || invitation?.security?.confirmation !== "HMAC-SHA256"
  ) {
    return failure("LAN_DOWNGRADE_REJECTED");
  }

  const issuedAtMs = Date.parse(invitation.issuedAt);
  const expiresAtMs = Date.parse(invitation.expiresAt);
  const nowMs = Date.parse(context.now ?? new Date().toISOString());
  if (![issuedAtMs, expiresAtMs, nowMs].every(Number.isFinite)) {
    return failure("LAN_INVITATION_TIME_INVALID");
  }
  if (
    !Number.isInteger(invitation.ttlSeconds)
    || invitation.ttlSeconds < MIN_TTL_SECONDS
    || invitation.ttlSeconds > MAX_TTL_SECONDS
  ) {
    return failure("LAN_INVITATION_TTL_INVALID");
  }
  if (expiresAtMs - issuedAtMs !== invitation.ttlSeconds * 1000) {
    return failure("LAN_INVITATION_TTL_MISMATCH");
  }
  if (nowMs < issuedAtMs) return failure("LAN_INVITATION_NOT_YET_VALID");
  if (nowMs >= expiresAtMs) return failure("LAN_INVITATION_EXPIRED");

  if (new Set(context.consumedInvitationIds ?? []).has(invitation.invitationId)) {
    return failure("LAN_INVITATION_REPLAYED");
  }
  if (new Set(context.consumedNonces ?? []).has(invitation.nonce)) {
    return failure("LAN_NONCE_REPLAYED");
  }

  const addressError = checkAddresses(invitation.listener);
  if (addressError) return failure(addressError);
  if (!matchesExpectedInterface(invitation.listener.selectedInterface, context.expectedInterface)) {
    return failure("LAN_INTERFACE_MISMATCH");
  }

  const publicKey = decodeBase64Url32(invitation?.ephemeralKey?.publicKey);
  if (!publicKey) return failure("LAN_EPHEMERAL_KEY_INVALID");
  if (publicKey.every((byte) => byte === 0)) return failure("LAN_EPHEMERAL_KEY_WEAK");

  if (!Array.isArray(invitation.capabilities)) {
    return failure("LAN_INVITATION_SCHEMA_INVALID");
  }
  if (REQUIRED_CAPABILITIES.some((capability) => !invitation.capabilities.includes(capability))) {
    return failure("LAN_CAPABILITY_MISMATCH");
  }
  if (computeInvitationFingerprint(invitation) !== invitation.fingerprint) {
    return failure("LAN_FINGERPRINT_MISMATCH");
  }
  return success;
};

export const negotiateLanSession = (invitation, clientHello, desktopPolicy) => {
  const commonVersions = clientHello.supportedBridgeVersions
    .filter((version) => desktopPolicy.supportedBridgeVersions.includes(version))
    .filter((version) => version === invitation.version);
  if (commonVersions.length === 0) return failure("LAN_VERSION_INCOMPATIBLE");

  const advertised = new Set(invitation.capabilities);
  const allowed = new Set(desktopPolicy.allowedCapabilities);
  const requested = new Set(clientHello.requestedCapabilities);
  if (
    REQUIRED_CAPABILITIES.some((capability) => !requested.has(capability))
    || [...requested].some((capability) => !advertised.has(capability) || !allowed.has(capability))
  ) {
    return failure("LAN_CAPABILITY_MISMATCH");
  }

  return {
    ok: true,
    code: null,
    selectedBridgeVersion: commonVersions.sort(compareUtf8).at(-1),
    selectedCapabilities: [...requested].sort(compareUtf8),
  };
};

export const validateLanClientHello = (invitation, clientHello) => {
  if (clientHello?.type !== "lan.clientHello" || clientHello?.version !== invitation?.version) {
    return failure("LAN_VERSION_INCOMPATIBLE");
  }
  if (
    clientHello.invitationId !== invitation.invitationId
    || clientHello.nonce !== invitation.nonce
  ) {
    return failure("LAN_TRANSCRIPT_MISMATCH");
  }

  const clientPublicKey = decodeBase64Url32(clientHello.clientEphemeralPublicKey);
  if (!clientPublicKey) return failure("LAN_EPHEMERAL_KEY_INVALID");
  if (clientPublicKey.every((byte) => byte === 0)) {
    return failure("LAN_EPHEMERAL_KEY_WEAK");
  }

  const endpoint = clientHello.selectedEndpoint;
  const endpointMatches = invitation.listener.addressCandidates.some((candidate) =>
    candidate.host === endpoint?.host
    && candidate.family === endpoint?.family
    && candidate.interfaceId === endpoint?.interfaceId
    && invitation.listener.port === endpoint?.port,
  );
  if (!endpointMatches) return failure("LAN_INTERFACE_MISMATCH");
  return success;
};

export const buildLanTranscriptHash = (invitation, clientHello, selection) =>
  createHash("sha256")
    .update(`${TRANSCRIPT_DOMAIN}\0`, "utf8")
    .update(canonicalize({
      invitation,
      clientHello,
      selection,
    }), "utf8")
    .digest();

export const verifyLanTranscriptHash = (invitation, clientHello, selection, receivedHash) => {
  if (!Buffer.isBuffer(receivedHash) || receivedHash.length !== 32) return false;
  const expected = buildLanTranscriptHash(invitation, clientHello, selection);
  try {
    return timingSafeEqual(expected, receivedHash);
  } finally {
    expected.fill(0);
  }
};

export const deriveLanSessionKeys = (sharedSecret, transcriptHash) => {
  if (!Buffer.isBuffer(sharedSecret) || sharedSecret.length !== 32 || sharedSecret.every((byte) => byte === 0)) {
    throw new LanInvitationError(
      "LAN_EPHEMERAL_KEY_WEAK",
      "X25519 shared secret must be a non-zero 32-byte Buffer",
    );
  }
  if (!Buffer.isBuffer(transcriptHash) || transcriptHash.length !== 32) {
    throw new LanInvitationError(
      "LAN_TRANSCRIPT_MISMATCH",
      "LAN transcript hash must be a 32-byte Buffer",
    );
  }
  const salt = createHash("sha256")
    .update(`${HKDF_SALT_DOMAIN}\0`, "utf8")
    .update(transcriptHash)
    .digest();
  let material;
  try {
    material = Buffer.from(
      hkdfSync("sha256", sharedSecret, salt, `${HKDF_INFO_DOMAIN}\0`, 128),
    );
    const copyKey = (offset) => {
      const key = Buffer.alloc(32);
      material.copy(key, 0, offset, offset + 32);
      return key;
    };
    return {
      clientToDesktopKey: copyKey(0),
      desktopToClientKey: copyKey(32),
      confirmationKey: copyKey(64),
      tokenBindingKey: copyKey(96),
    };
  } finally {
    material?.fill(0);
    salt.fill(0);
  }
};

export const deriveLanSessionKeysFromX25519 = (privateKey, peerPublicKeyBytes, transcriptHash) => {
  if (!Buffer.isBuffer(peerPublicKeyBytes) || peerPublicKeyBytes.length !== 32) {
    throw new LanInvitationError(
      "LAN_EPHEMERAL_KEY_INVALID",
      "X25519 peer public key must be a 32-byte Buffer",
    );
  }
  let sharedSecret;
  try {
    const peerPublicKey = createPublicKey({
      key: Buffer.concat([X25519_SPKI_PREFIX, peerPublicKeyBytes]),
      format: "der",
      type: "spki",
    });
    sharedSecret = diffieHellman({ privateKey, publicKey: peerPublicKey });
  } catch (error) {
    throw new LanInvitationError(
      "LAN_EPHEMERAL_KEY_WEAK",
      "X25519 peer public key produced an invalid or all-zero shared secret",
      { cause: error },
    );
  }
  try {
    return deriveLanSessionKeys(sharedSecret, transcriptHash);
  } finally {
    sharedSecret?.fill(0);
  }
};

export const signLanConfirmation = (role, confirmationKey, transcriptHash) => {
  if (!["client", "desktop"].includes(role)) throw new TypeError(`Unknown LAN confirmation role: ${role}`);
  return createHmac("sha256", confirmationKey)
    .update(`${CONFIRMATION_DOMAIN}\0${role}\0`, "utf8")
    .update(transcriptHash)
    .digest();
};

export const verifyLanConfirmation = (role, confirmationKey, transcriptHash, receivedTag) => {
  if (!Buffer.isBuffer(receivedTag) || receivedTag.length !== 32) return false;
  const expected = signLanConfirmation(role, confirmationKey, transcriptHash);
  try {
    return timingSafeEqual(expected, receivedTag);
  } finally {
    expected.fill(0);
  }
};

export const applyFixtureMutations = (base, mutations) => {
  const copy = structuredClone(base);
  for (const mutation of mutations) {
    const parts = mutation.path
      .slice(1)
      .split("/")
      .map((part) => part.replaceAll("~1", "/").replaceAll("~0", "~"));
    const property = parts.pop();
    const parent = parts.reduce((current, part) => current[part], copy);
    if (mutation.op === "add" || mutation.op === "replace") {
      parent[property] = structuredClone(mutation.value);
    } else if (mutation.op === "remove") {
      delete parent[property];
    } else {
      throw new TypeError(`Unsupported fixture mutation: ${mutation.op}`);
    }
  }
  return copy;
};
