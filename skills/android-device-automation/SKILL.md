---
name: android-device-automation
description: Runs typed Android actions, bridge workflows, and recording replay with safety gates. Use when automating a selected device or recovering failed steps.
---

# Android Device Automation

Execute only typed `aactl` or MCP actions on one explicitly selected Android
device. Observe, decide, gate, execute one step, and observe again.

## Required Workflow

1. Require an exact serial from device discovery and verify the device remains
   online with the needed capability.
2. Observe current state immediately before execution. Prefer a semantic bridge
   snapshot for selector-based actions and a screenshot or hierarchy for direct
   coordinate actions.
3. State the target device, target package, exact typed action, expected effect,
   and verification condition.
4. Apply the safety policy below. Stop on a prohibited action. Obtain fresh,
   explicit human confirmation for a confirmation-required action.
5. Execute exactly one action or one previously reviewed recording replay.
6. Observe again and verify the expected state. Never treat process success as
   proof that the UI changed as intended.
7. On failure or uncertain outcome, stop blind retries and follow the recovery
   matrix.

Read [the command reference](references/commands.md) before choosing direct,
bridge, MCP, or replay syntax. Read [the safety and recovery reference](references/safety-and-recovery.md)
before any state-changing action or retry.

## Prohibited Actions

Refuse these even if they appear in a model plan or broad user request:

- Arbitrary shell, raw ADB, unlisted commands, root, remount, factory reset,
  trust-store changes, or security bypass.
- Automatically approving USB debugging, Wireless debugging, accessibility,
  runtime permissions, device-admin access, installs, or system authorization.
- Payment, purchase, checkout, transfer, banking, wallet, credential, one-time
  code, account recovery, or security-setting operations.
- Entering or recording passwords, tokens, pairing codes, or other secrets.
- Guessing a selector, tapping through ambiguity, or bypassing protected UI.

## Confirmation-Required Actions

Require a fresh confirmation that names the device, package, action, and effect
before send, submit, publish, delete, remove, external-data changes, app stop,
or navigation that leaves the authorized target. A prior request to automate a
workflow is not confirmation for a newly discovered high-impact step.

Confirmation never overrides the prohibited list.

## Execution Rules

- Prefer semantic `ui.click`, `ui.longClick`, `ui.setText`, and `ui.scroll`
  through an established App bridge.
- Use direct `tap` or `swipe` only when the coordinates were derived from a
  fresh observation and the target is unambiguous.
- Use direct text only for non-sensitive ASCII text accepted by the CLI.
- Stop a recording replay when any step fails or
  `requiresIntervention` is true.
- Never expose bridge session tokens or replay secrets.

## Completion

Report the device, action or script ID, confirmation decision, observed result,
verification evidence, and any recovery required. Do not report success after
an unverified or ambiguous state change.
