// 脚本用途：用共享 fixture 固定 LAN invitation 的 preflight、密钥派生和确认契约。
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  applyFixtureMutations,
  buildLanTranscriptHash,
  computeInvitationFingerprint,
  deriveLanSessionKeys,
  deriveLanSessionKeysFromX25519,
  negotiateLanSession,
  signLanConfirmation,
  validateLanClientHello,
  validateLanInvitationPreflight,
  verifyLanTranscriptHash,
  verifyLanConfirmation,
} from "./lan-invitation.mjs";

const protocolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const readJson = async (name) =>
  JSON.parse(await readFile(path.join(protocolRoot, "fixtures", name), "utf8"));
const validInvitation = await readJson("lan-invitation-v1-valid.json");
const threatSuite = await readJson("lan-invitation-v1-threats.json");
const cryptoVector = await readJson("lan-handshake-v1-vector.json");

test("合法 invitation 在 socket 建立前通过纯 preflight", () => {
  const result = validateLanInvitationPreflight(validInvitation, {
    now: "2026-07-25T10:00:30Z",
    consumedInvitationIds: [],
    consumedNonces: [],
    expectedInterface: validInvitation.listener.selectedInterface,
  });
  assert.deepEqual(result, { ok: true, code: null });
});

test("所有语义威胁 fixture 返回声明的稳定错误码且不改写输入", () => {
  const semanticThreats = threatSuite.cases.filter(({ stage }) => stage === "preflight");
  assert.ok(semanticThreats.length >= 10);
  for (const fixture of semanticThreats) {
    const invitation = applyFixtureMutations(validInvitation, fixture.mutations);
    const before = structuredClone(invitation);
    const result = validateLanInvitationPreflight(invitation, fixture.context);
    assert.equal(result.ok, false, fixture.name);
    assert.equal(result.code, fixture.expectedCode, fixture.name);
    assert.deepEqual(invitation, before, `${fixture.name} mutated its input`);
  }
});

test("时间、IPv6 和 scope 边界在 preflight 失败关闭", () => {
  const cases = [
    {
      name: "future invitation",
      expectedCode: "LAN_INVITATION_NOT_YET_VALID",
      mutate: () => {},
      context: { now: "2026-07-25T09:59:59Z" },
    },
    {
      name: "IPv6 unspecified",
      expectedCode: "LAN_ADDRESS_UNSPECIFIED",
      mutate: (invitation) => {
        invitation.listener.addressCandidates[2].host = "::";
      },
    },
    {
      name: "IPv6 multicast",
      expectedCode: "LAN_ADDRESS_MULTICAST",
      mutate: (invitation) => {
        invitation.listener.addressCandidates[2].host = "ff02::1";
      },
    },
    {
      name: "scope spoofing",
      expectedCode: "LAN_ADDRESS_SCOPE_MISMATCH",
      mutate: (invitation) => {
        invitation.listener.addressCandidates[0].scope = "linkLocal";
      },
    },
    {
      name: "missing IPv6 link-local zone",
      expectedCode: "LAN_INTERFACE_MISMATCH",
      mutate: (invitation) => {
        delete invitation.listener.addressCandidates[3].zoneId;
      },
    },
  ];
  for (const boundary of cases) {
    const invitation = structuredClone(validInvitation);
    boundary.mutate(invitation);
    if (boundary.name !== "future invitation") {
      invitation.fingerprint = computeInvitationFingerprint(invitation);
    }
    const result = validateLanInvitationPreflight(invitation, {
      now: "2026-07-25T10:00:30Z",
      ...boundary.context,
    });
    assert.deepEqual(result, { ok: false, code: boundary.expectedCode }, boundary.name);
  }
});

test("短指纹绑定 invitation 的全部公开字段", () => {
  assert.equal(
    computeInvitationFingerprint(validInvitation),
    validInvitation.fingerprint,
  );
  const tampered = structuredClone(validInvitation);
  tampered.capabilities = [...tampered.capabilities, "device.info"];
  assert.notEqual(computeInvitationFingerprint(tampered), validInvitation.fingerprint);
});

test("capability 协商只选择双方明确支持的版本和能力", () => {
  const result = negotiateLanSession(
    validInvitation,
    {
      supportedBridgeVersions: ["1.0"],
      requestedCapabilities: [
        "lan.bridge.rpc.v1",
        "lan.bridge.mutual-confirmation.v1",
        "ui.snapshot",
      ],
    },
    {
      supportedBridgeVersions: ["1.0"],
      allowedCapabilities: validInvitation.capabilities,
    },
  );
  assert.deepEqual(result, {
    ok: true,
    code: null,
    selectedBridgeVersion: "1.0",
    selectedCapabilities: [
      "lan.bridge.mutual-confirmation.v1",
      "lan.bridge.rpc.v1",
      "ui.snapshot",
    ],
  });
});

test("版本或必要能力不匹配时不允许旧版降级", () => {
  const versionResult = negotiateLanSession(
    validInvitation,
    {
      supportedBridgeVersions: ["0.9"],
      requestedCapabilities: validInvitation.capabilities,
    },
    {
      supportedBridgeVersions: ["1.0"],
      allowedCapabilities: validInvitation.capabilities,
    },
  );
  assert.equal(versionResult.code, "LAN_VERSION_INCOMPATIBLE");

  const capabilityResult = negotiateLanSession(
    validInvitation,
    {
      supportedBridgeVersions: ["1.0"],
      requestedCapabilities: ["ui.snapshot"],
    },
    {
      supportedBridgeVersions: ["1.0"],
      allowedCapabilities: validInvitation.capabilities,
    },
  );
  assert.equal(capabilityResult.code, "LAN_CAPABILITY_MISMATCH");
});

test("client hello 在密钥交换和 token 签发前绑定 invitation 与 endpoint", () => {
  assert.deepEqual(
    validateLanClientHello(validInvitation, cryptoVector.clientHello),
    { ok: true, code: null },
  );

  const cases = [
    {
      name: "invitation id tampering",
      expectedCode: "LAN_TRANSCRIPT_MISMATCH",
      mutate: (hello) => {
        hello.invitationId = "b68b84a0-9084-4476-8c45-e1316a063d2c";
      },
    },
    {
      name: "nonce tampering",
      expectedCode: "LAN_TRANSCRIPT_MISMATCH",
      mutate: (hello) => {
        hello.nonce = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
      },
    },
    {
      name: "endpoint tampering",
      expectedCode: "LAN_INTERFACE_MISMATCH",
      mutate: (hello) => {
        hello.selectedEndpoint.port += 1;
      },
    },
    {
      name: "all-zero client key",
      expectedCode: "LAN_EPHEMERAL_KEY_WEAK",
      mutate: (hello) => {
        hello.clientEphemeralPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
      },
    },
  ];
  for (const fixture of cases) {
    const hello = structuredClone(cryptoVector.clientHello);
    fixture.mutate(hello);
    assert.deepEqual(
      validateLanClientHello(validInvitation, hello),
      { ok: false, code: fixture.expectedCode },
      fixture.name,
    );
  }
});

test("transcript、HKDF 分域密钥和双方确认具有确定结果", () => {
  const transcriptHash = buildLanTranscriptHash(
    validInvitation,
    cryptoVector.clientHello,
    cryptoVector.selection,
  );
  assert.equal(transcriptHash.toString("hex"), cryptoVector.expected.transcriptHashHex);
  assert.equal(
    verifyLanTranscriptHash(
      validInvitation,
      cryptoVector.clientHello,
      cryptoVector.selection,
      transcriptHash,
    ),
    true,
  );
  const tamperedSelection = structuredClone(cryptoVector.selection);
  tamperedSelection.selectedCapabilities.push("recording.replay");
  assert.equal(
    verifyLanTranscriptHash(
      validInvitation,
      cryptoVector.clientHello,
      tamperedSelection,
      transcriptHash,
    ),
    false,
  );

  const sharedSecret = Buffer.from(cryptoVector.sharedSecretHex, "hex");
  const keys = deriveLanSessionKeys(sharedSecret, transcriptHash);
  try {
    assert.equal(keys.clientToDesktopKey.toString("hex"), cryptoVector.expected.clientToDesktopKeyHex);
    assert.equal(keys.desktopToClientKey.toString("hex"), cryptoVector.expected.desktopToClientKeyHex);
    assert.equal(keys.confirmationKey.toString("hex"), cryptoVector.expected.confirmationKeyHex);
    assert.equal(keys.tokenBindingKey.toString("hex"), cryptoVector.expected.tokenBindingKeyHex);

    const desktopTag = signLanConfirmation("desktop", keys.confirmationKey, transcriptHash);
    const clientTag = signLanConfirmation("client", keys.confirmationKey, transcriptHash);
    try {
      assert.equal(desktopTag.toString("hex"), cryptoVector.expected.desktopConfirmationHex);
      assert.equal(clientTag.toString("hex"), cryptoVector.expected.clientConfirmationHex);
      assert.equal(verifyLanConfirmation("desktop", keys.confirmationKey, transcriptHash, desktopTag), true);
      assert.equal(verifyLanConfirmation("client", keys.confirmationKey, transcriptHash, clientTag), true);
      assert.equal(verifyLanConfirmation("client", keys.confirmationKey, transcriptHash, desktopTag), false);
    } finally {
      desktopTag.fill(0);
      clientTag.fill(0);
    }
  } finally {
    sharedSecret.fill(0);
    transcriptHash.fill(0);
    Object.values(keys).forEach((key) => key.fill(0));
  }
});

test("非零 X25519 低阶点在派生阶段失败关闭", () => {
  const { privateKey } = generateKeyPairSync("x25519");
  const transcriptHash = Buffer.alloc(32, 0x5a);
  try {
    for (const vector of cryptoVector.lowOrderPublicKeys) {
      const lowOrderPoint = Buffer.from(vector.publicKey, "base64url");
      assert.ok(lowOrderPoint.some((byte) => byte !== 0), vector.name);
      assert.throws(
        () => deriveLanSessionKeysFromX25519(privateKey, lowOrderPoint, transcriptHash),
        (error) => error.code === "LAN_EPHEMERAL_KEY_WEAK"
          && /invalid or all-zero shared secret/.test(error.message),
        vector.name,
      );
    }
  } finally {
    transcriptHash.fill(0);
  }
});

test("全零或错误长度 shared secret 不进入 HKDF", () => {
  const transcriptHash = Buffer.alloc(32, 0x5a);
  try {
    for (const sharedSecret of [Buffer.alloc(32), Buffer.alloc(31, 0x7a)]) {
      try {
        assert.throws(
          () => deriveLanSessionKeys(sharedSecret, transcriptHash),
          (error) => error.code === "LAN_EPHEMERAL_KEY_WEAK",
        );
      } finally {
        sharedSecret.fill(0);
      }
    }
  } finally {
    transcriptHash.fill(0);
  }
});
