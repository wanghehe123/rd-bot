# Policy And Stop Rules

## Modes and writes

`dry-run` is the default and performs no POST. A write requires both the CLI
`--live-test` flag and the independent environment opt-in
`RD_BOT_AUTOPILOT_LIVE_TEST=1`. A live manifest alone is not permission.
Missing opt-ins fail before the network request and leave the persisted intent
for safe resume.

The fixed origin is a loopback HTTP(S) origin with no path, query, fragment, or
user-info. Redirects, oversized responses, non-JSON responses, malformed JSON,
unknown methods, and unknown paths fail closed. Request bodies are schema
validated before transport.

The only POST routes are:

- `/admin/rd-tasks/requirements` with `autoExecute=false`;
- `/admin/rd-tasks/{numericTaskId}/submit`;
- `/admin/rd-tasks/{numericTaskId}/retry`;
- `/admin/rd-tasks/{numericTaskId}/evaluations`.

There is no automatic approval, pause, cancel, delete, merge, deploy, branch
mutation, shell, raw `curl`, or Git command path. The Skill stops for a human
at approval, merge, deployment, deletion, an unknown state, or an ambiguous
write outcome.

## Status mapping

Known active task states are observed within the deadline. `WAITING_APPROVAL`,
`FAILED_NEEDS_HUMAN`, `REJECTED`, and `MERGED` map to `WAITING_HUMAN`.
`CANCELLED`, `DEAD_LETTERED`, and `DELETED` map to `BOUNDED_STOP`. Unknown task
or evaluation states map to `WAITING_HUMAN`. Only `FAILED_RETRYABLE` in an
allowed failure phase may use the single retry budget.

## Evidence and redaction

Run artifacts are atomically written beneath the exact run directory. Common
secret keys, Bearer values, API-key-shaped strings, URL user-info, credentials,
authorization headers, full prompts, and private reasoning are redacted. Keep
complete IDs and hashes, but cap text previews and traces. Request ledgers keep
method, path, request hash, status, and UTC timestamp only; they never store
headers or raw bodies.
