# Safety and Recovery

## Decision Order

Evaluate each action in this order:

1. Is the serial explicit and currently online?
2. Is the package explicitly authorized and still foreground?
3. Is the action available through a typed CLI, MCP, or bridge interface?
4. Does it touch a prohibited target or secret?
5. Does it send, submit, publish, delete, remove, stop, navigate away, or change
   external data?
6. Is there a fresh observation and an objective postcondition?

Refuse at step 3 or 4. Require explicit confirmation at step 5. Execute only
after step 6 is defined.

## Confirmation Record

Before a confirmation-required action, present:

- Device serial and transport.
- Target package and visible target label.
- Exact typed action and parameters.
- External or destructive effect.
- Verification condition and recovery limit.

Accept only an explicit response to that concrete action. Do not reuse consent
from pairing, bridge setup, task creation, or an earlier step.

## Retry Policy

- Read-only observation can be retried once after confirming the device state.
- An action with a confirmed failed result can be retried once only after a
  fresh observation still shows the original precondition.
- An action with an unknown result must not be retried. Observe and reconcile
  first to avoid duplicate sends, submissions, deletions, or replay.
- Never replace an ambiguous semantic selector with a guessed coordinate.
- Never broaden the target package or reduce safety checks to make progress.

## Error Recovery

### `ADB_UNAUTHORIZED`

Stop. Require the user to approve the workstation RSA fingerprint on-device,
then rediscover the device. Do not automate approval.

### `DEVICE_OFFLINE`, `DEVICE_NOT_FOUND`, `DEVICE_UNREACHABLE`

Stop actions. Return to device discovery, repair the transport, and obtain a
fresh observation before resuming.

### `MULTIPLE_DEVICES`

Require an exact serial. Never choose by list order.

### `AUTH_REQUIRED`, `AUTH_INVALID`, `AUTH_EXPIRED`

Stop bridge work. Require the user to generate a fresh one-time code in the App
and open a new bridge session. Do not reuse or reveal old credentials.

### `CAPABILITY_UNAVAILABLE`

Do not emulate the missing capability with shell. Choose a documented typed
backend or ask the user to perform the step manually.

### `ACTION_NOT_ALLOWED`, `PERMISSION_DENIED`

Do not retry. Report the blocked policy or permission. The user must grant any
legitimate Android permission manually outside automation.

### `SELECTOR_NOT_FOUND`

Capture a fresh package-scoped semantic snapshot. Retry only if the same unique
target can be identified from new data; otherwise request intervention.

### `SELECTOR_AMBIGUOUS`

Stop and request intervention. Do not select the first candidate or tap a
center coordinate.

### `ACTION_FAILED`, `DEADLINE_EXCEEDED`

Observe before retrying. A timeout can have an unknown outcome. If the action
could change external data, do not retry until the result is reconciled.

### `SCRIPT_NOT_FOUND`

List sanitized summaries with
`aactl recording list --device SERIAL --json`, then ask the user to select and
review an existing recording in the Android App. The list does not expose
steps, variables, or secrets.

### Replay Failure

Stop when `succeeded` is false or `requiresIntervention` is true. Report the
first failed step and its route, score, attempts, code, and message. Do not skip
failed steps or launch an unreviewed alternate script.

## Emergency Stop

On a stop request, submit no new action, replay, or retry. Close the bridge when
it is safe to do so, discard pending action data and temporary screenshots, and
report the last verified state.
