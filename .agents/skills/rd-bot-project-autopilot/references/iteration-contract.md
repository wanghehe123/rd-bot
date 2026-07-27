# Iteration Contract

This Skill owns one versioned manifest at `qa-runs/autopilot/{runId}/manifest.json`.
The run directory is the only writable artifact root. `rd-bot-autopilot/v1` is
the current schema identifier; unknown schema versions, fields needed for a
decision, or states not in this contract fail closed.

## Bounds

- At most two sequential iterations.
- At most one active RD-Bot task across the run.
- At most one retry for a task.
- At most 120 elapsed minutes and 200,000 observed tokens.
- At most 2 MiB per API response and 100,000 characters per artifact excerpt.

## Lifecycle

`DRAFT -> PLANNING -> READY` freezes a plan. A live dispatch writes
`DISPATCH_INTENT` before the create request, then binds one exact task and writes
`SUBMIT_INTENT` before the single submit request. Observation is `OBSERVING`.
`FAILED_RETRYABLE` may enter `RETRY_INTENT` once and return to observation.
`COMPLETED` tasks enter `EVALUATION_INTENT`, bind one task-run evaluation, and
enter `EVALUATING`; a terminal evaluation enters `LEARNING`.

`DRY_RUN_COMPLETED`, `COMPLETED`, and `BOUNDED_STOP` are terminal run states.
`WAITING_HUMAN` is terminal for automation but resumable after an operator
decision. Approval-required, unknown, or ambiguous external states always enter
`WAITING_HUMAN`; the Skill never guesses or retries a write.

## Identity and resume

Every plan title is prefixed with the exact marker
`[autopilot:{runId}:{iteration}]`. The complete request is canonicalized and
hashed before a POST. Create, submit, retry, and evaluation actions persist an
intent first. If the process stops after an intent, resume performs read-only
reconciliation by exact marker, project/task ID, request fields, source version,
operator note, or evaluation config. It never resends an ambiguous POST.

An iteration retains all stage Attempts and observations. The latest Attempt per
role is selected for display; prior Attempts remain in the manifest as evidence.
Only IDs, statuses, hashes, timestamps, bounded summaries, and evidence
references are persisted. Private reasoning and full prompts are not part of
the contract.

## Workspace

Capture the repository fingerprint before initialization and after the run.
The fingerprint is derived from the tracked diff plus sorted non-ignored
untracked path/content hashes. A changed fingerprint is a failed verification;
the report must not claim goal completion. The runner never runs Git mutation
commands and never writes outside the allocated run directory.
