## 规划与修改前置阅读

- **规划、实现或修改任何功能前，必须先阅读 `RULE.md` 与该功能最相关的 spec。**
  RD 任务管理优先阅读 `docs/rd-task-management-requirements.md` 和
  `docs/rd-task-management-design.md`；先追踪现有代码链路，再补充规则或实现。
- `RULE.md` 是受保护的仓库约束：不得删除、改名或以空白文件替换。规则需要演进时，
  在原文件中以可验证的约束追加或修订，并注明真实路径与验证命令。

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

## QA evidence references and browser start mode

- Browser QA results must REFERENCE evidence, not just collect it: the union of
  `logArtifactId` and `evidenceArtifactIds` across `acceptanceResults` must
  include `qa-evidence/console/`, `qa-evidence/network/`, a
  `qa-evidence/traces/*.zip`, and screenshots whose names contain `desktop` and
  `mobile`. The host `QaEvidenceBundleValidator` rejects the whole result
  otherwise, after the container is gone.
- Any new host-side result validation rule must be mirrored in the in-container
  pre-validation (`bootstrap/src/main/resources/executor/pi/src/result-tool.mjs`)
  and stated in the `RequirementDeliveryEngine` role contract prompt. Prompt,
  bridge, and host disagreeing is a protocol crack: the agent passes locally and
  fails terminally.
- Auto-detected Next.js QA profiles must start the app in production mode
  (`npm run build && npm run start`). Dev-mode hydration in the QA container is
  slow enough that clicks silently no-op; that is not "Playwright cannot trigger
  React synthetic events". Keep `RD_QA_STARTUP_TIMEOUT_SECONDS` large enough to
  cover the build (300s in both executors).
- After editing the bridge or QA skill resources, rebuild both Pi images
  (`Dockerfile` and `Dockerfile.qa` under `bootstrap/src/main/resources/executor/pi`);
  the running backend keeps serving stale in-container rules otherwise.
- See `docs/superpowers/specs/2026-07-28-qa-evidence-reference-and-production-mode-spec.md`
  for the verified chain, acceptance criteria, and failure handling.
