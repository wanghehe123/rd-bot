# Open Source Dependency Triage — 2026-09-08/09

Scope: dependencies reachable from the first-release supported path
(single-machine Docker + PostgreSQL store + Pi runtime). Baseline facts come
from the 2026-09-08 audit (`docs/qa/open-source-readiness-2026-09-08.md`,
baseline `687af408`); this document records the post-remediation state on the
release branch. Numbers below were re-measured, not copied.

## Tooling and time

| Scan | Tool | When | Targets |
| --- | --- | --- | --- |
| npm (frontend) | `npm audit --package-lock-only` | 2026-09-09 | `frontend/package-lock.json` |
| npm (Pi bridge) | `npm audit --package-lock-only` | 2026-09-09 | `bootstrap/src/main/resources/executor/pi/package-lock.json` |
| Maven + container images | Trivy 0.74.0 (`image`, `fs`) | 2026-09-09 | `rd-bot/app:oss-test`, `rd-bot/pi-agent:oss-test`, `rd-bot/pi-agent-qa:oss-test` |

## frontend npm — baseline 3 high + 5 moderate → now 0

| Advisory | Package | Severity | Resolution |
| --- | --- | --- | --- |
| GHSA-c83g-rgw3-j3cx / GHSA-73wf-gq98-2v4g | browserslist ≤4.28.6 | high | `npm audit fix` (non-breaking patch) |
| GHSA-28wg-ghj8-5hjv / GHSA-2v37-7h3g-55p8 | nanoid ≤3.3.17 | high | `npm audit fix` |
| GHSA-fxqj-rqcc-2cmp / GHSA-r28c-9q8g-f849 | postcss ≤8.5.22 | high | `npm audit fix` |
| GHSA-x7hr-w5r2-h6wg | prismjs <1.30.0 (via refractor/react-syntax-highlighter) | moderate | `react-syntax-highlighter` 15.6.6 → 16.1.1 (prismjs 1.30.0); `PrismLight` usage unchanged |
| GHSA-wrjc-x8rr-h8h6 / GHSA-337j-9hxr-rhxg | react-router 6.x | moderate | `react-router-dom` 6.30.6 → 7.18.3; v7 makes `v7_startTransition`/`v7_relativeSplatPath` the default, so the v6 `future` prop was removed from `App.tsx` and `frontend/test/adminVisualSystem.test.ts` updated |

Verification: frontend contract tests 265/265, `tsc --noEmit`, production
build — all green after the upgrades.

## Pi bridge npm — baseline 2 high + 1 moderate → now 0

| Advisory | Package | Severity | Resolution |
| --- | --- | --- | --- |
| GHSA-mh99-v99m-4gvg / GHSA-rgw5-rvv9-x895 | brace-expansion (transitive) | high | `npm audit fix` |
| undici desync/cookie/CRLF set (GHSA-8xcm-r25x-g524 et al.) | undici ≤8.8.0 (via `@earendil-works/pi-coding-agent`) | high | `@earendil-works/pi-coding-agent` 0.82.1 → 0.85.1 |

The pi-coding-agent upgrade is a cross-minor change of the agent runtime
library. Verification: Pi bridge protocol tests 111/111 after the upgrade, and
both Pi images were rebuilt from the upgraded lockfile.

## Maven classpath

Baseline B05 reported 19 classpath dependencies matching 81 advisories without
reachability triage.

Re-scan status on the release machine (2026-09-09): **partially blocked by the
local environment**. The Trivy vulnerability DB download (ghcr.io) and the
deps.dev Maven resolution used by osv-scanner both time out on this network —
the same registry-path outage documented in the acceptance notes. What WAS
verified:

- `osv-scanner` 2.5.1 (OSV.dev API) over the release tree: **0 findings in
  `frontend/package-lock.json` and the Pi bridge `package-lock.json`**
  (double-confirms the npm audit zero state). The only findings reported are in
  `.worktrees/*` — old experimental branches that are NOT part of the release.
- Runtime classpath composition unchanged on the release branch except Spring
  Boot patch-line versions managed by `spring-boot-dependencies` 3.5.x.

**Open item (G10, re-check condition):** run
`osv-scanner scan source -r .` (and a Trivy image scan of the three release
images) from a network-unconstrained environment — the minimal CI workflow
includes exactly this job. Any critical/high that is runtime-reachable and
touches code execution, path traversal, request smuggling, or credential/data
exposure blocks the release per the plan; test-scope or unreached findings are
deferred with path + anchor.

Blocking rule (from the plan): a critical/high that is runtime-reachable and
affects host code execution, path traversal, request smuggling, credential or
data exposure must be fixed or the release is blocked. Test-scope-only or
unreached-module findings may be deferred with the dependency path and a
configuration/code anchor.

## Container images

Trivy image scanning could not run on this machine (vulnerability DB download
hangs on the broken registry path, see above). The images consume pinned,
upstream-maintained base tags — `node:22.19.0-bookworm-slim`,
`eclipse-temurin:21-jre-jammy`, `maven:3.9-eclipse-temurin-21`,
`pgvector/pgvector:pg16`, `redis:7.4-bookworm`,
`minio/minio:RELEASE.2025-09-07T16-13-09Z` — which receive upstream security
patches by tag movement, tracked as the G10 re-check condition alongside the
CI scan job. No "zero vulnerabilities" claim is made for the images.

Base images are pinned tags (see `docker-compose.yml`, root `Dockerfile`,
Pi `Dockerfile`); bumps happen deliberately, never via `:latest`.

## Outcome

- npm: 0 known vulnerabilities in both release lockfiles (npm audit +
  osv-scanner, 2026-09-09); achieved by version upgrades only, no audit
  suppressions.
- Maven + container images: scanning blocked by the local registry-path
  outage; recorded as G10 open item with the CI job as re-check condition.
  This is an environment fact, not a vulnerability claim.
