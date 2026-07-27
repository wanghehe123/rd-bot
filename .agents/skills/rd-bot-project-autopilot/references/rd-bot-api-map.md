# RD-Bot API Map

The Java controllers in this repository are the source of truth. The Python
client uses only the following typed, exact routes and treats new fields or
states as unknown until reviewed.

## Discovery and task lifecycle

- `GET /admin/projects` and `GET /admin/projects/{projectId}` are implemented by
  `bootstrap/.../controller/admin/project/RdProjectController.java`.
- `GET /admin/rd-tasks` supports project, keyword, type, page, and page size;
  `GET /admin/rd-tasks/{taskId}` returns the detail. Requirement creation is
  `POST /admin/rd-tasks/requirements` with title, project ID, expected result,
  2-6 acceptance criteria, bounded materials, `autoExecute=false`, and an
  optional non-negative token budget.
- `POST /admin/rd-tasks/{taskId}/submit` is the one explicit execution submit.
  `GET /admin/rd-tasks/{taskId}/timeline` is read-only evidence.

## Execution and retry evidence

- `GET /admin/rd-tasks/{taskId}/execution-overview` returns stage Runs;
  `GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-trace`
  returns a bounded trace reference target.
- `GET /admin/rd-tasks/{taskId}/retry-preview` and
  `GET /admin/rd-tasks/{taskId}/retry-history` expose the failure phase,
  source task version, Attempt, and durable checkpoints.
- `POST /admin/rd-tasks/{taskId}/retry` requires stale-failure guards and the
  exact operator note marker. The prototype allows one retry only.

## Evaluation

- `POST /admin/rd-tasks/{taskId}/evaluations` creates a task-run evaluation.
  The prototype sends `judgeProvider=NONE`, `judgeLimit=0`,
  `timeoutSeconds=90`, and an empty baseline.
- `GET /admin/evaluations/runs` lists candidates for ambiguous creation;
  `GET /admin/evaluations/runs/{runId}` is polled through known statuses
  `CREATED`, `QUEUED`, `RECORDING`, `SCORING`, `REPORTING`, `DIFFING`,
  `SUCCEEDED`, `FAILED`, and `CANCELLED`.
- `GET /admin/evaluations/runs/{runId}/timeline` and `/artifacts` provide
  references for the final evidence record. Logs and arbitrary artifact
  content are intentionally not exposed by this Skill.

The frontend may display richer objects, but it must not be used to infer a
write contract. If a response omits an exact identity field or introduces an
unknown lifecycle status, stop and request human review.
