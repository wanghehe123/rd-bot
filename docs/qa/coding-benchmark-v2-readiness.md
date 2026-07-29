# Coding Benchmark V2 Readiness Record

Status: **NOT READY — no capability Trial may be queued.**

This is deliberately a fail-closed record, not a partial benchmark result. It
tracks the evidence needed before LongCat 2.0 receives any of the 88 planned
Trial requests.

## Implemented and verified locally

- The campaign model creates the pre-registered `20 × 4` formal Trial matrix
  plus four A/D sentinel repeats (88 logical Trials), with 45-minute Agent and
  8-minute Oracle limits.
- Agent/Oracle execution contracts use immutable image digests, `--pull=never`,
  task-local `/work/cache`, offline package-manager flags, isolated network for
  the Agent and `network=none` for the Oracle.
- The Oracle sees only an extracted patch and protected test bundle. It cannot
  see the Agent worktree, cache, build output or model credential.
- RAG snapshot generation writes four per-case documents from the base
  repository only, hashes every document/chunk, excludes Gold and
  runtime-withheld paths, and aborts on protected-content leakage.
- The scorer separates fresh/public/formal slices; it reports Wilson intervals,
  paired score intervals, exact McNemar, Holm exploratory contrasts,
  missing-pair bounds, D/A cost ratios and sentinel flip/reversal evidence.
- The Python verifier suite passed with 86 tests on 2026-07-30. It now also
  rejects a case unless its declared 40-character `baseCommit` object and
  ancestry are locally present before any RAG document, dependency layer or
  Oracle bundle may be produced.
- The Oracle can inject a runtime-withheld Git test patch only after the
  candidate patch and rejects a candidate that touches any path protected by
  that test patch. This is required by Multi-SWE-bench, whose withheld tests
  often modify existing test files rather than adding a new directory.
- Ten **candidate** public anchors were selected from the immutable
  Multi-SWE-bench revision in
  [`public-anchor-selection-20260730.json`](../../scripts/evaluation/public-anchor-selection-20260730.json).
  Their complete upstream histories were mirrored only in the trusted temporary
  preparation area and every candidate was reconstructed at its exact base
  commit with no remote and no future commit objects. They are deliberately
  still candidates: no RAG document, dependency layer, Oracle bundle or Trial
  has been made from them.
- Docker Desktop is now available on `linux/arm64`. The two shared thin layers
  were built from the locally attested Pi base without Docker pulls or package
  downloads; the immutable build record is
  [`shared-images-arm64-20260730-v3.json`](../../scripts/evaluation/docker/shared-images-arm64-20260730-v3.json):
  - Agent: `rd-bot/coding-eval-agent@sha256:41ed60ec907225173b5a72ca6c45845e12d562ee9685d01fc8f7d9dd5868f037`
  - Oracle: `rd-bot/coding-eval-oracle@sha256:356bf9ba42b5655a3b7858c435ef7ac46fe537e015156552394c0380add31595`
  The Oracle probe launched from its digest using non-root, read-only and
  `network=none` constraints. Its inherited Agent bridge is explicitly cleared
  by the runtime executor before the frozen verifier argv runs.
- The reusable Java environment is now built as a third shared layer rather
  than being repeated in every Java case image:
  [`java17-arm64-20260730-v1.json`](../../scripts/evaluation/docker/java17-arm64-20260730-v1.json)
  attests `rd-bot/coding-eval-java17@sha256:0aade4a49cf595396b75b667070dff8c2dd5a7d5679b25d67dcde841869251e4`.
  Java 17 and Maven both started successfully as UID `10001` on a read-only
  root filesystem. Repository-specific Gradle/Maven caches are not yet built.

## Current external blockers

1. The local machine currently has four checked-out **public** SWE-bench Django
   snapshots (`django-10914`, `10924`, `11001`, `11019`), but none contains the
   declared upstream base-commit object or its ancestry. They are shallow
   snapshots, not eligible case repositories; no RAG document, test bundle or
   dependency image has been generated from them. Each public anchor must be
   reconstructed from the exact upstream base commit into a private prepared
   repository first. The separate Multi-SWE candidates now have valid private
   base histories, but still need all offline and Oracle preflights before they
   can become a verified 10-public-case manifest. The required 10 fresh cases
   are still absent.
2. The fresh-primary slice cannot be substituted with cached public tasks. The
   readiness validator now requires every fresh case to carry an auditable
   `PRIVATE_TASK` or `POST_CUTOFF_ISSUE` reference.
3. The Node, Oracle and reusable Java layers exist, but each selected case
   still needs an immutable thin dependency layer, an Oracle bundle and an
   offline three-pass readiness result before it can enter the snapshot.
4. The production requirement-stage integration files are currently modified
   in the workspace by a separate change. They have intentionally not been
   overwritten; campaign execution will remain disabled until that change is
   settled and its regression tests pass.

## Required gate before a real run

1. Select and freeze 10 public anchors and 10 fresh-primary cases, each with a
   base commit, Oracle bundle, expected test IDs and freshness evidence.
2. Reconstruct each selected public repository at its exact base commit, then
   build the shared bases and thin case layers; capture platform-specific image
   digests and storage measurements with 20% headroom.
3. Generate the four RAG documents for all 20 cases, freeze their manifests,
   then run `BASE` / `TEST` / `FIX` three times with offline network policy.
4. Run the two probe cases through all four arms before freezing the prompt and
   retrieval configuration. Only then queue the 80 formal Trials and the eight
   pre-registered sentinels.

No output from the existing public snapshots is counted as an A/B/C/D result
until all four gates above are recorded as passed.
