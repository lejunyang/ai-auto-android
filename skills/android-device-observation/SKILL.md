---
name: android-device-observation
description: Collects Android device info, screenshots, UI hierarchies, and bridge snapshots. Use when inspecting a selected device or gathering automation artifacts.
---

# Android Device Observation

Observe one explicitly selected Android device with the least sensitive source
that can answer the task.

## Required Workflow

1. Require a serial selected by the discovery workflow. Re-list devices if the
   selection may be stale.
2. Fetch device information and verify the required observation capability.
3. Choose one source:
   - Device metadata for hardware, Android version, state, and capabilities.
   - ADB UI hierarchy for basic visible UI structure without the App bridge.
   - App bridge semantic snapshot for accessibility selectors and node state.
   - Screenshot only when visual pixels are necessary.
4. Ask the user to navigate away from passwords, one-time codes, payments,
   personal messages, and other sensitive content before capture.
5. Capture once, record format, size or depth, SHA-256 when provided, and the
   exact selected serial.
6. Extract only the minimum facts required. Do not paste or persist an entire
   hierarchy or snapshot when a short redacted summary is sufficient.
7. Apply the retention and cleanup rules before handing artifacts to another
   workflow.

Read [the command reference](references/commands.md) for exact CLI and MCP
forms. Read [the privacy and artifact reference](references/privacy-and-artifacts.md)
before collecting screenshots or UI content.

## Source Rules

- ADB screenshots and UIAutomator hierarchy are raw device observations and
  can contain sensitive content.
- App bridge semantic snapshots redact nodes marked password or sensitive, but
  surrounding labels, package names, bounds, and UI state can still be private.
- `FLAG_SECURE` or protected surfaces can be unavailable or blank. Never try to
  bypass platform protection.
- Never infer hidden text, request secrets, or place raw observations in logs.
- Never use arbitrary shell, raw ADB commands, OCR, or unlisted capture tools
  as a fallback.

## Bridge Boundary

Semantic snapshots require the user to enable the desktop bridge in the Android
App and establish a short-lived session. Opening that session requires a fresh
one-time code and explicit device selection. Do not expose the code or local
session token.

## Completion

Return a redacted summary plus artifact metadata and retention status. Identify
the source as `device-info`, `screenshot`, `uiautomator-hierarchy`, or
`bridge-semantic-snapshot`; do not merge their privacy guarantees.
