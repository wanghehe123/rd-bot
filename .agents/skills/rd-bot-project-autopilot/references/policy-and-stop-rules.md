# Policy And Stop Rules

## Modes and writes

`dry-run` is the default and performs no POST. A write requires both the CLI
`--live-test` flag and the independent environment opt-in
`RD_BOT_AUTOPILOT_LIVE_TEST=1`. A live manifest alone is not permission.
Missing opt-ins or a client-schema preflight failure happen before the intent is
written, so the run remains `READY`/`OBSERVING` and can be retried after the
operator fixes the invocation. A transport failure after an intent is written
is ambiguous: resume performs read-only reconciliation and never resends it.

The fixed origin is a loopback HTTP(S) origin with no path, query, fragment, or
user-info. Redirects, oversized responses, non-JSON responses, malformed JSON,
unknown methods, and unknown paths fail closed. Request bodies are schema
validated before transport. The selected project's repository identity and
base branch are authoritative; a plan cannot redirect work to another repo.
Autonomous materials are inline `MANUAL_TEXT` only, with no `sourceUri`.

The only POST routes are:

- `/admin/rd-tasks/requirements` with `autoExecute=false`;
- `/admin/rd-tasks/{numericTaskId}/submit`;
- `/admin/rd-tasks/{numericTaskId}/retry`;
- `/admin/rd-tasks/{numericTaskId}/evaluations`.

## Provisioning writes

`live-provision` adds exactly four more writes, all gated by `--live-provision`,
`RD_BOT_AUTOPILOT_LIVE_PROVISION=1`, and a persisted `provision-confirm`
receipt whose digest must be repeated via `--confirm-plan-sha256`:

- one `gh api --method POST /user/repos` with fixed private/auto-init args;
- `POST /knowledge-base`;
- `POST /knowledge-base/{id}/docs/write` for the two generated documents;
- `POST /admin/projects`.

Each write records a Manifest intent first and binds only an exact, marker-
verified read-back. An ambiguous outcome reconciles at most one exact
candidate; zero, multiple, or mismatched candidates become a resumable
`WAITING_HUMAN`. `provision-resume` is reconciliation-only and never resends.
The repository owner must equal the authenticated `gh` user; organization,
public, or internal repositories, clone, push, collaborators, secrets, external
source ingestion, and automatic cleanup are refused. On failure, only the
created IDs and URLs are reported as a manual cleanup list.

There is no automatic approval, pause, cancel, delete, merge, deploy, branch
mutation, shell, raw `curl`, or Git command path. The Skill stops for a human
at approval, merge, deployment, deletion, an unknown state, or an ambiguous
write outcome.

An operator may record a bounded `RESUME` decision from `WAITING_HUMAN`; it
restores only the exact pre-gate state recorded in `pendingApproval`. It never
creates a new task or repeats a write by itself.

## Status mapping

Known active task states are observed within the deadline. `WAITING_APPROVAL`,
`FAILED_NEEDS_HUMAN`, `REJECTED`, and `MERGED` map to `WAITING_HUMAN`.
`CANCELLED`, `DEAD_LETTERED`, and `DELETED` map to `BOUNDED_STOP`. Unknown task
or evaluation states map to `WAITING_HUMAN`. `FAILED_RETRYABLE` in an allowed
failure phase may use the single retry budget. `FAILED_NEEDS_HUMAN` may use that
same single budget only after an operator records a task-bound `RESUME` decision;
the guarded retry preview, live opt-ins, intent-before-POST ordering, and phase
allowlist still apply.

## Evidence and redaction

Run artifacts are atomically written beneath the exact run directory. Common
secret keys, Bearer values, API-key-shaped strings, URL user-info, credentials,
authorization headers, full prompts, and private reasoning are redacted. Keep
complete IDs and hashes, but cap text previews and traces. Request ledgers keep
method, path, request hash, status, and UTC timestamp only; they never store
headers or raw bodies.
