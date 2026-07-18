# Discovery Commands

## CLI

Check the installed CLI without requiring ADB:

```bash
aactl version --json
```

Diagnose the ADB executable, server status, loopback port 5037, and mDNS:

```bash
aactl doctor --json
```

List normalized USB, Wi-Fi, and emulator devices:

```bash
aactl devices list --json
```

Get details and capabilities for one explicit serial:

```bash
aactl device info --device SERIAL --json
```

For Android 11+ Wireless debugging, first pair with the pairing endpoint shown
by Android. The command prompts on stderr and reads the six-digit code from
stdin so the code does not enter process arguments:

```bash
aactl devices pair 192.0.2.10:37123 --json
```

Then connect with the connection endpoint shown by Android. Its port commonly
differs from the pairing port:

```bash
aactl devices connect 192.0.2.10:40117 --json
```

Run `devices list` again and use the returned serial exactly. There is no
`devices watch` command in the current CLI.

All JSON CLI responses use the protocol envelope:

```text
schemaVersion, requestId, ok, data, error, meta
```

Treat `error.code` as stable. Human-readable messages can gain detail without
changing the code.

## MCP

The MCP server exposes:

- `android_devices_list` with `{}`.
- `android_device_get` with `{"device":"SERIAL"}`.

Configure the MCP client to start this long-running stdio command:

```bash
aactl mcp serve
```

Do not invoke the server as a one-shot interactive command.

MCP does not expose `doctor`, `pair`, `connect`, trust changes, or arbitrary
shell.

## Selection Contract

Record the selected serial as data, not prose. Every later command must include
`--device SERIAL`, and every later MCP call must include `"device":"SERIAL"`.
Never substitute a model name for a serial.
