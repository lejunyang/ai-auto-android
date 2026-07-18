---
name: android-device-discovery
description: Diagnoses ADB and discovers USB or Wi-Fi Android devices safely. Use when connecting, pairing, troubleshooting, or selecting an Android device.
---

# Android Device Discovery

Use `aactl` as the only device transport interface. Discover and select a device
before invoking observation or automation skills.

## Required Workflow

1. Run the version and doctor checks. Report a failed doctor check; do not kill
   or restart the shared ADB server automatically.
2. List devices and inspect every returned `serial`, `state`, `transport`, and
   capability.
3. For USB, ask the user to connect the cable, enable USB debugging, and accept
   the workstation RSA fingerprint on the device. Never accept it for them.
4. For Android 11+ Wi-Fi debugging, require the user to enable Wireless
   debugging and provide the pairing endpoint. Read the six-digit pairing code
   from the interactive prompt, then connect to the separately displayed
   connection endpoint.
5. List devices again after pairing or connection.
6. If more than one device is present, present the serials and require an
   explicit choice. Carry that exact serial into every later `--device` or MCP
   `device` field.
7. Continue only when the selected device state is `device` and the required
   capability reports `available: true`.

Read [the command reference](references/commands.md) before pairing or
connecting. Read [the troubleshooting reference](references/troubleshooting.md)
when doctor or device state is unhealthy.

## Safety Rules

- Never run raw `adb`, arbitrary shell, `adb kill-server`, `root`, `remount`,
  `disable-verity`, factory reset, or trust-revocation commands.
- Never bypass `unauthorized`, approve an RSA prompt, enable Wireless
  debugging, or enter a pairing code without the user's direct participation.
- Do not expose pairing codes in arguments, logs, chat, or saved artifacts.
- Do not infer a target from ordering, model name, transport, or a previous
  session when multiple devices are listed.

## MCP Boundary

Use `android_devices_list` and `android_device_get` when MCP is available.
Doctor, Wi-Fi pairing, and Wi-Fi connection are CLI-only. MCP intentionally
cannot pair devices or change trust.

## Completion

Return the selected serial, transport, normalized state, relevant capabilities,
and any remaining user action. Do not claim readiness while a device is
`unauthorized`, `offline`, absent, or ambiguous.
