## Premium model escalation

Before making a high-risk or difficult engineering decision, load the
`model-escalation` skill.

You must consider escalation when the task involves:

- authentication, authorization, payment, encryption or privacy;
- database schema, public API or protocol changes;
- concurrency, transactions, retries, idempotency or distributed consistency;
- destructive or difficult-to-reverse operations;
- conflicting evidence about the root cause;
- two failed implementation or debugging attempts;
- a large final change requiring architectural review.

Do not invoke the premium advisor for routine file edits, formatting,
straightforward CRUD work or obvious test fixes.

## Pi QA protocol and workspace hygiene

- Trace `RequirementDeliveryEngine` -> execution-profile resolver ->
  `DockerPiAgentExecutor` -> `rd-pi-bridge.mjs` before changing a Pi failure
  diagnosis or retry behavior.
- Treat `Pi result requires RESULT_SUBMITTED followed by AGENT_SETTLED` as a
  missing-lifecycle aggregate, not proof that event ordering was violated.
  Inspect the normalized stage events and bridge lifecycle first.
- A Pi attempt may recover once after it settles without `rd_submit_result`.
  Do not add unbounded follow-up prompts or automatic cross-runtime fallback.
- Keep package-manager caches task-local under `/work/cache`. Clean stale
  `output/` only while holding the task workspace lock; preserve `repo/` and
  `cache/` so retries do not redownload dependencies or race over inputs.
- Raw Pi events are private diagnostics, capped by
  `RD_EXECUTOR_PI_MAX_RAW_EVENT_BYTES` (16 MiB by default). Do not increase
  the cap or bulk-delete workspaces without first measuring ownership/open-file
  state and recording the recovery path.
- When changing this path, run `npm test` in
  `bootstrap/src/main/resources/executor/pi`, then
  `./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`
  and the relevant Bootstrap configuration test.
