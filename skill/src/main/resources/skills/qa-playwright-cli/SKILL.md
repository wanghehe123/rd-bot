---
name: qa-playwright-cli
description: Perform RD-Bot QA with real commands, real HTTP requests, Playwright CLI browser flows, regression checks, and integrity-verifiable evidence. Use only for the QA_AGENT stage after coding.
---

# RD-Bot Playwright QA

Treat QA as a delivery blocker, not a build summary.

## Boundaries

- Read `/work/input/prompt.md`, `/work/input/context.json`, `/work/input/qa-profile.json`, and `/work/input/result.schema.json` first.
- Do not modify tracked files in `/work/repo`. Put temporary scripts and configuration under `/work/output/qa-work`.
- Capture `git status --porcelain=v1 --untracked-files=all` before QA and require the same clean state after QA. If dependency installation creates `node_modules`, framework caches, coverage, or build output, remove only those paths created by this QA attempt after stopping the application; never use `git clean`, `git reset`, or checkout to hide a mutation.
- Use only origins allowed by `RD_QA_ALLOWED_HOSTS`. Do not weaken TLS, authentication, or repository policy.
- Never print credentials. Only report missing environment variable names.
- A required check that cannot run is `SKIPPED` and blocks delivery.

## Required Coverage

1. Verify every current acceptance criterion with an executable command or browser flow.
2. Run the repository's existing relevant tests and an impacted regression set.
3. Exercise at least one pre-existing critical path that the change could break.
4. When browser validation is required, verify desktop `1440x900` and mobile `390x844` with Chromium.
5. Capture command exit codes, durations, logs, screenshots, trace, console output, network requests, and real HTTP transcripts as applicable.

Do not use a clean base-branch comparison unless the changed branch fails and diagnosis needs it.

## Browser Workflow

Every generated browser driver must begin with `set -euo pipefail`.
Stop dependent browser steps after the first failed required assertion; close the trace and browser, perform at most the one allowed diagnostic
rerun, then report the failure. Never continue through multiple 30-second locator timeouts after the page
has failed to reach the prerequisite state. A command log containing `isError=true` or JSON
`"isError": true` cannot support a `PASSED` acceptance result, even when an outer shell exits with code 0.

Reserve at least two minutes of the hard QA runtime for stopping application processes, removing only
dependency/cache/build paths created by this attempt, generating the manifest, and writing `result.json`.
Track those generated paths before dependency installation and register shell cleanup traps before starting
the application. Do not leave cleanup and the strict result protocol until after optional exploration.

Start the application with `RD_QA_START_COMMAND` when it is non-empty, capture its log under
`/work/output/qa-evidence/commands/`, and wait at most `RD_QA_STARTUP_TIMEOUT_SECONDS` for
`RD_QA_BASE_URL` plus `RD_QA_HEALTH_PATH` to return a real successful response. A startup timeout is
`ENVIRONMENT`, not a product pass. Run the application in this QA container, stop it when QA ends,
and do not use Docker, Docker Compose, or a host Docker socket.

Use a stable named session and explicit durable evidence paths. Put generated CLI snapshots and the
CLI-owned trace under `PLAYWRIGHT_MCP_OUTPUT_DIR` in `/work/output/qa-work`; copy only selected evidence
into `/work/output/qa-evidence` before generating the manifest:

```bash
set -euo pipefail
mkdir -p /work/output/qa-work/playwright \
  /work/output/qa-evidence/{screenshots,traces,console,network,commands}
pw() { node /usr/local/bin/rd-qa-evidence.mjs playwright -s=rd-qa "$@"; }
pw open "$BASE_URL"
pw resize 1440 900
pw snapshot
# Mask sensitive controls before trace recording or any durable screenshot.
pw run-code "async page => { await page.locator('input[type=password],input[autocomplete=current-password],input[autocomplete=new-password]').evaluateAll(nodes => nodes.forEach(node => { node.value = '[REDACTED]'; node.setAttribute('value', '[REDACTED]'); })); }"
pw tracing-start
pw screenshot --filename=/work/output/qa-evidence/screenshots/current-desktop.png
pw resize 390 844
# Repeat the current critical flow at the mobile viewport before taking this screenshot.
pw screenshot --filename=/work/output/qa-evidence/screenshots/current-mobile.png
pw console error > /work/output/qa-evidence/console/current.log
pw requests > /work/output/qa-evidence/network/current.log
node /usr/local/bin/rd-qa-evidence.mjs redact \
  /work/output/qa-evidence/console/current.log \
  /work/output/qa-evidence/network/current.log
pw tracing-stop
find "$PLAYWRIGHT_MCP_OUTPUT_DIR/traces" -maxdepth 1 -type f -name '*.trace' -print -quit | grep -q .
node /usr/local/bin/rd-qa-evidence.mjs trace \
  --source "$PLAYWRIGHT_MCP_OUTPUT_DIR/traces" \
  --output /work/output/qa-evidence/traces/current.zip
test -s /work/output/qa-evidence/traces/current.zip
pw close
```

Use snapshot refs or resilient role/test-id locators for interactions. In the pinned CLI, make assertions
with `run-code` and throw when the observable result is wrong, for example
`async page => { const heading = page.getByRole('heading', { name: 'Ready' }); await heading.waitFor(); }`.
Always invoke CLI commands through the `pw` wrapper above. The pinned CLI can return process exit code 0
while its JSON response has `isError=true`; the wrapper converts that condition to a non-zero exit. Do not
treat `eval`, `snapshot`, or a screenshot alone as an assertion. Repeat the critical current flow at
`390x844`.

React/Next.js pages hydrate after the server HTML renders: a visible button whose click produces no DOM
change usually means hydration has not finished, not a broken feature. Before the first interaction run
`async page => { await page.waitForLoadState('networkidle'); }`, and when a click has no observable effect
wait for network idle and retry that click once. If clicks still have no effect and the application was
started in dev mode (`next dev`, `vite`, watch mode), rebuild and restart in production mode
(for Next.js: `npm run build && npm run start`) and rerun the flow; only after a production-mode retry may
you classify the failure as `QA_INFRASTRUCTURE`. Before reporting any browser `FAILED` result, capture
`pw console error` and `pw requests` into `qa-evidence/console/` and `qa-evidence/network/` as the
classification evidence.

Write the browser sequence to `/work/output/qa-work/current-browser.sh` and run the complete sequence
through `rd-qa-evidence.mjs` so its exit code, duration, stdout, and stderr are durable. Never allow
`.playwright-cli` output in the repository. Run existing `@playwright/test` suites for durable regression
coverage when present; use Playwright CLI for exploratory acceptance evidence.

## Command Evidence

Wrap non-browser checks so exit status and duration are durable:

```bash
node /usr/local/bin/rd-qa-evidence.mjs run --scope CURRENT --id current-api -- curl -fsS http://127.0.0.1:8080/api/health
node /usr/local/bin/rd-qa-evidence.mjs run --scope REGRESSION --id existing-tests -- npm test
```

The helper prints `qaEvidenceArtifact=<relative-path>`. Use that exact path in `result.json`. Reusing an
ID creates `-attempt-N.log`; it never overwrites an earlier failure. Do not delete or replace prior command
logs, and keep every attempt in the final manifest.

Run `rd-qa-evidence.mjs redact` on every text file written directly under `qa-evidence/http`,
`qa-evidence/network`, or `qa-evidence/console`. It recursively removes credential-shaped JSON fields,
known secret environment values, Authorization/Cookie headers, and credential query parameters. Do this
before generating the manifest. Never persist full request or response headers when a method, path,
status, duration, and bounded redacted body summary are enough.

Run one diagnostic rerun for a suspected flaky failure. A rerun does not turn the result into `PASSED`; classify it as `FLAKY` and recommend `HUMAN`.

## Result And Remediation

- `PRODUCT_DEFECT` or `REGRESSION`: `status=FAILED`, `retryRecommendation=CODING_AGENT`.
- `ENVIRONMENT`, `AUTHENTICATION`, `QA_INFRASTRUCTURE`, `REQUIREMENT_AMBIGUITY`, or `FLAKY`: `status=FAILED`, `retryRecommendation=HUMAN`.
- Only use `status=PASSED`, `failureCategory=NONE`, and `retryRecommendation=NONE` when all required CURRENT and REGRESSION checks pass with real artifacts.

Generate `/work/output/qa-evidence/manifest.json` last:

```bash
node /usr/local/bin/rd-qa-evidence.mjs manifest
```

Every `logArtifactId`, every `evidenceArtifactIds` entry, and `evidenceManifestArtifactId` in `result.json` must be a relative path under `qa-evidence/` that exists and is non-empty.

When browser validation was performed, the union of `logArtifactId` and `evidenceArtifactIds` across
`acceptanceResults` must reference at least one file under each of `qa-evidence/console/`,
`qa-evidence/network/`, and `qa-evidence/traces/`, plus one `qa-evidence/screenshots/` file whose name
contains `desktop` and one whose name contains `mobile`. Evidence that is only collected or only listed
in the manifest without being referenced causes the host to reject the whole result.
