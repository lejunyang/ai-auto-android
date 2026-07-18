# Privacy and Artifact Handling

## Before Capture

1. State what source is needed and why.
2. Confirm the exact device and, for semantic snapshots, the target package.
3. Prefer metadata or semantic structure over pixels.
4. Ask the user to leave password, one-time-code, payment, health, financial,
   private-message, and account-recovery screens.
5. Choose an artifact path outside source control and shared sync folders.

## Source Classification

### Screenshot

A screenshot is raw sensitive data. It can contain notifications, account
identifiers, private text, photos, keyboard suggestions, and system overlays.
Do not upload, log, or commit it. Crop or redact only in an approved downstream
tool, and preserve the original hash separately if audit integrity is required.

### UIAutomator Hierarchy

The XML can contain visible text, content descriptions, resource IDs, package
names, and bounds. It has no App-level sensitive-node redaction guarantee.
Summarize required nodes and discard the raw response when no longer needed.

### Bridge Semantic Snapshot

The Android App filters password and sensitive node text before returning the
tree. This reduces exposure but does not make the tree public. Labels, package
names, layout, state, and non-marked text can still identify a user or task.

### Device Information

Serials and network endpoints identify devices and infrastructure. Keep them
out of public reports unless explicitly required.

## Redaction

Remove or replace:

- Passwords, one-time codes, API keys, tokens, pairing codes, and session data.
- Email addresses, phone numbers, account IDs, personal names, and message
  bodies not required by the task.
- Wi-Fi endpoints and device serials in externally shared output.
- Payment, banking, health, authentication, and recovery content.

Do not claim redaction by visual inspection alone. When uncertain, do not share
the raw artifact.

## Retention

- Keep the minimum number of captures.
- Record path, source, selected serial, timestamp, SHA-256 when available, and
  intended retention.
- Delete temporary screenshots after extracting approved facts.
- Do not add screenshots, hierarchy payloads, or semantic snapshots to Git.
- Close bridge sessions after the workflow so the ADB forward and local session
  record are removed.

## Failure Handling

- Empty or protected screenshot: report it; do not bypass `FLAG_SECURE`.
- Truncated or invalid PNG/XML: discard it and retry once after confirming the
  device is online.
- Bridge `AUTH_REQUIRED` or `AUTH_EXPIRED`: require a fresh user-generated code.
- `PERMISSION_DENIED` or unavailable accessibility capability: ask the user to
  enable the service manually in Android settings. Never automate authorization.
