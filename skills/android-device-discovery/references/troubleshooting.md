# Discovery Troubleshooting

## `ADB_NOT_FOUND`

Install official Android SDK Platform-Tools or set `AACTL_ADB_PATH` to the ADB
executable. Run doctor again. Do not download an unverified ADB binary.

## Doctor Is Unhealthy

Report the failed check by name:

- `adb.version`: the executable output is missing or unsupported.
- `adb.server`: server status could not be queried.
- `adb.port.5037`: no ADB server is listening on loopback.
- `adb.mdns`: Wireless debugging discovery is unavailable.

Do not automatically run `adb kill-server`. Ask the user to repair their
Platform-Tools installation, driver, cable, network, or ADB server outside the
skill, then rerun doctor.

## `ADB_UNAUTHORIZED`

1. Keep the device unlocked.
2. Ask the user to compare and accept the workstation RSA fingerprint on the
   device.
3. Reconnect the USB cable if the prompt is not visible.
4. Run the device list again.

Never bypass the prompt or modify the device trust store.

## `DEVICE_OFFLINE`

1. Stop all automation attempts.
2. Ask the user to reconnect the cable or toggle Wireless debugging.
3. For Wi-Fi, reconnect using the current connection endpoint.
4. Run doctor and list devices again.

Do not retry actions while the state is `offline`.

## `DEVICE_NOT_FOUND`

Confirm the cable, USB mode, Wireless debugging network, emulator state, and
the exact serial. A Wi-Fi endpoint can change after Wireless debugging is
restarted.

## `MULTIPLE_DEVICES`

Display each serial with its model, transport, and state. Require the user or
the calling workflow to choose one exact serial. Never choose the first entry.

## Wi-Fi Pairing Failure

- Confirm Android 11+ and that Wireless debugging remains open.
- Distinguish the pairing endpoint from the connection endpoint.
- Use a fresh six-digit code before it expires.
- Keep workstation and device on a network that permits local peer traffic.
- Do not save, echo, or reuse the pairing code.

## Retry Rule

Retry only after the state or environment has changed. Re-run discovery before
handing the serial to observation or automation.
