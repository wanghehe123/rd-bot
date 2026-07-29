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
- The Python verifier suite passed with 99 tests on 2026-07-30. It now also
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
  still candidates: their base-only RAG documents and host-only Gold/runtime
  test-patch assets exist, but no formal Trial has been made from them.
- Docker Desktop is now available on `linux/arm64`. The two shared thin layers
  were built from the locally attested Pi base without Docker pulls or package
  downloads; the immutable build record is
  [`shared-images-arm64-20260730-v4.json`](../../scripts/evaluation/docker/shared-images-arm64-20260730-v4.json):
  - Agent: `rd-bot/coding-eval-agent@sha256:c532fa2ea586a9221c5dcefb44d4d72f601ef3f66ddf31027c47776b7755cc03`
  - Oracle: `rd-bot/coding-eval-oracle@sha256:904063462a3f3eb74902fdf38283f18d66f2ce908fffe00381b68ad8ee90b27d`
  The Oracle probe launched from its digest using non-root, read-only and
  `network=none` constraints. Its inherited Agent bridge is explicitly cleared
  by the runtime executor before the frozen verifier argv runs.
- The v4 Oracle layer carries the offline verifier itself. The image records
  `rd.evaluation.oracle-script-sha256` and
  `RD_EVAL_ORACLE_SCRIPT=/opt/rd-pi-bridge/rd_eval_oracle.py`; the baked file
  hashes to `sha256:e82628a601be3ba01eba25b282c503179f293e04c04463b5015871b8681ebdbf`,
  matching the attested value in the build record and the repository source.
  The verifier is packed through a hand-built archive with `root:root` ownership,
  mode `0444` and a zeroed mtime, so the build host's uid no longer leaks into an
  attested image. Layer bytes are still not reproducible across builds because
  `docker cp` updates the parent directory mtime, so every formal run must record
  the digest it actually built rather than assume the v4 digest reappears.
  The probe on `sha256:904063462a3f...` ran as UID `10001` with `--read-only`,
  `--cap-drop ALL`, `--security-opt no-new-privileges` and `network=none`: the
  script hash matched, `python3 rd_eval_oracle.py --help` succeeded offline, the
  append attempt was denied, and DNS resolution failed as required.
  The **v3 digests above were superseded** and must not be used for a formal
  Trial; they remain valid only as the earlier thin-image build record.
- The reusable Java environment is now built as a third shared layer rather
  than being repeated in every Java case image:
  [`java17-arm64-20260730-v1.json`](../../scripts/evaluation/docker/java17-arm64-20260730-v1.json)
  attests `rd-bot/coding-eval-java17@sha256:0aade4a49cf595396b75b667070dff8c2dd5a7d5679b25d67dcde841869251e4`.
  Java 17 and Maven both started successfully as UID `10001` on a read-only
  root filesystem.
- `mockito__mockito-3133` has one immutable, host-only Gradle cache artifact
  (tree digest `sha256:a2a015fb8bf7e4976074a48e8738fb741a5a516f043114da49cde4816f64d4e3`)
  tied to base `edc624371009ce981bbc11b7d125ff4e359cff7e` and the Java 17 image
  digest. The base test was then rerun with `network=none`, a read-only root,
  UID `10001`, and `--offline`; it passed. A Trial will copy this one immutable
  artifact locally into separate short-lived Agent and Oracle caches, so the
  two phases never share a writable cache and no repair phase downloads a
  package.
- The executor now gives the Agent a separate output directory and accepts
  only its newly written `candidate.patch`; a stale patch quarantines the
  attempt, and a missing patch prevents Oracle execution. Oracle receives a
  distinct cache, the patch read-only, and exactly one protected test carrier
  (directory or runtime test patch). The offline verifier is now copied and
  hashed into the Oracle image itself, and the v4 build above carries it.

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
   still needs an immutable cache artifact, its frozen parser/Oracle command,
   and an offline three-pass readiness result before it can enter the snapshot.
   The first Mockito cache is only a proof of the process, not a completed
   public slice.
4. The production requirement-stage integration files are currently modified
   in the workspace by a separate change. They have intentionally not been
   overwritten; campaign execution will remain disabled until that change is
   settled and its regression tests pass.
5. Every artifact that names a shared image digest must be re-checked against
   the v4 references before a formal Trial. The Java 17 layer is unaffected: it
   is a sibling of the Agent layer built from the same
   `rd-bot/pi-agent@sha256:07dcbd9d...` base, not a child of the Agent image, so
   its `20260730-java17-v1` attestation and the Mockito cache artifact bound to
   it both remain valid.

## RAG preparation order

Before any selected public case is allowed to execute a dependency or test
preflight, its four base-only RAG documents are generated and frozen. The first
ten candidate snapshots were generated from their private prepared base
repositories only: 40 documents / 10 knowledge manifests, with aggregate
digest `sha256:d7678f17284dd1b2280cf92c4aa3d90480ae92218cfd0f6891992106707d6ddc`.
The files are held in the trusted preparation area (not in the agent worktree),
and this is readiness evidence rather than a formal snapshot: runtime-withheld
tests and Gold fixes were never supplied to the generator.

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
