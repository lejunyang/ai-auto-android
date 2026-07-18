# Observation Commands

## Device Information

```bash
aactl device info --device SERIAL --json
```

Verify `state` is `device` and inspect the returned capabilities before
capturing UI data.

## PNG Screenshot

Use an approved local output path. `aactl` writes the file with owner-only
permissions and returns its absolute path, format, byte size, and SHA-256:

```bash
aactl observe screenshot --device SERIAL --output screen.png --json
```

The image itself is not embedded in the CLI JSON response.

With MCP, call `android_observe`:

```json
{"device":"SERIAL","kind":"screenshot"}
```

MCP returns image content plus structured metadata. Treat the client-managed
image as a sensitive artifact.

## UIAutomator Hierarchy

```bash
aactl observe hierarchy --device SERIAL --json
```

The CLI JSON data includes `format: "uiautomator-xml"`, `xml`, `sizeBytes`, and
`sha256`. There is no CLI output-file option for hierarchy.

With MCP:

```json
{"device":"SERIAL","kind":"hierarchy"}
```

## App Bridge Semantic Snapshot

In the Android App, the user must first enable the desktop bridge and generate
a fresh one-time code. Omit `--code` so the CLI reads it interactively:

```bash
aactl bridge open --device SERIAL --json
```

Inspect the established bridge's device information:

```bash
aactl bridge info --device SERIAL --json
```

Capture a package-scoped semantic tree:

```bash
aactl bridge snapshot --device SERIAL --package com.example.app --max-depth 64 --json
```

`--package` is optional. `--max-depth` is optional and accepts 1 through 100;
the shared service defaults to 64.

With MCP, an already established bridge session is required:

```json
{"device":"SERIAL","kind":"semantic","targetPackage":"com.example.app","maxDepth":64}
```

When finished with bridge work:

```bash
aactl bridge close --device SERIAL --json
```

MCP does not expose bridge open, info, action, or close. Establish and close
sessions through the CLI with direct user participation.

## Current Command Boundary

The CLI supports only `observe screenshot` and `observe hierarchy`. Semantic
observation uses `bridge snapshot`; MCP unifies all three sources under
`android_observe`.
