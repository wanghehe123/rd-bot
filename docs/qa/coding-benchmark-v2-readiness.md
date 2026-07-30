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
- Ten public anchors are **verified, no longer candidates**, in
  [`public-anchor-selection-20260730-v2.json`](../../scripts/evaluation/public-anchor-selection-20260730-v2.json)
  (supersedes the earlier candidate file). The jib pair was replaced after its
  Gradle 5.x/6.x wrappers proved incompatible with the frozen Java 17 platform;
  the jackson-databind replacement candidates failed in turn (unresolvable
  SNAPSHOT parent, then javac-source-6), so the final Java half is mockito x2 +
  fastjson2 x2. Each case was reconstructed at its exact base commit with no
  remote and no future commit objects, its Gold and runtime-withheld patches
  materialized host-only, and every case then passed a three-round offline
  (network=none) preflight: base + withheld test patch makes the expected f2p
  command FAIL, adding the Gold patch makes it PASS, three of three rounds, in
  the attested images. The environment contract discovered by that preflight
  (NODE_ENV split install/test, ESM jest flag for grs-3442, UTF-8 locale for
  Maven, root-scoped test task for mockito-3220, per-instance cache warming) is
  recorded in the selection file's `preflight.environmentNotes`.
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
- Fresh cases are authored from real private fix commits by
  `rd_eval_author_fresh_case.py`, which splits a single-parent commit along the
  test boundary and emits the same host-only asset shape as the Multi-SWE
  materializer. Expected fail-to-pass IDs are read back out of the withheld
  patch instead of typed by hand. Merges, commits with no test change,
  single-file changes and commits touching a build or dependency manifest are
  refused. Against real `rd-bot` history, 40 of 44 size-eligible commits were
  accepted and the four refusals were correct.
- `rd_eval_audit_case_leakage.py` then rejects a case whose own base repository
  already documents the fix. Of the 40 accepted candidates it flagged 10, five
  of which had their new class names written verbatim into a committed
  implementation plan; 30 remain clean, which is enough for a 10-case slice.
  Only symbols the Gold patch *declares* are audited, so library calls that
  appear in base docs and tests for unrelated reasons produce no findings. A
  Gold patch with no distinctive declaration reports `UNAUDITABLE`, which must
  not be recorded as a pass.
- The executor now gives the Agent a separate output directory and accepts
  only its newly written `candidate.patch`; a stale patch quarantines the
  attempt, and a missing patch prevents Oracle execution. Oracle receives a
  distinct cache, the patch read-only, and exactly one protected test carrier
  (directory or runtime test patch). The offline verifier is now copied and
  hashed into the Oracle image itself, and the v4 build above carries it.

## Current external blockers

1. ~~Public anchors unverified~~ **Resolved on 2026-07-30**: the verified
   10-case public slice is recorded in `public-anchor-selection-20260730-v2.json`
   (see the implementation list above). The four stale Django snapshots remain
   irrelevant to this benchmark.
2. The fresh-primary slice cannot be substituted with cached public tasks. The
   approved first-round source is `wanghehe123/rd-bot` itself (private, created
   2026-07-22, Java + TypeScript frontend, so its history cannot be in any
   training corpus); all ten fresh cases come from one repository under the
   explicitly reported exception in design section 3.1.1. The ten-case fresh
   selection is now **frozen** in
   [`fresh-selection-20260730.json`](../../scripts/evaluation/fresh-selection-20260730.json)
   (seed 20260730, Java 8 / TS-JS 2, EASY 2 / MEDIUM 5 / HARD 3, every case
   CLEAN under the leakage audit with a `PRIVATE_TASK` freshness reference).
   The fresh slice is now **verified too (2026-07-30)**: every case passed
   the same three-round network=none TEST(expect-fail) / FIX(expect-pass)
   preflight as the public slice, recorded in the selection file. The JAVA
   cases run on the new attested java21 layer
   ([`java21-arm64-20260730-v1.json`](../../scripts/evaluation/docker/java21-arm64-20260730-v1.json),
   `rd-bot/coding-eval-java21@sha256:024fe12a2a...`) because the project
   targets release 21; Maven runs rebuild sibling modules from the case base
   inside the reactor to avoid SNAPSHOT jar drift across commits, and each FIX
   round is accepted only when its log proves at least one test executed.
   What remains for the fresh slice is only its base-only RAG documents.
3. ~~Case cache artifacts and offline preflights absent~~ **Resolved on
   2026-07-30** for both slices (see above).
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
**Note (2026-07-30):** that snapshot set predates the jib replacement, so the
two jib document bundles are stale. A fresh snapshot has since been generated
for the verified v2 public selection plus the ten fresh cases: 80 documents /
20 knowledge manifests, aggregate digest
`sha256:72b10647d9a57aa74818e8cb95e2d3dcec40ad2ab085f00249db2a65e75bf066`,
zero protected-content leakage hits across all manifests. The documents were
produced from each case's private prepared base repository only, with the
Gold and runtime-withheld paths excluded and fingerprint-checked; the files
are held in the trusted preparation area.

## Frozen formal snapshot (2026-07-30)

The 20 verified cases, three attested images, the frozen knowledge snapshot and
the pre-registered analysis plan are now frozen as one content-addressed
snapshot directory by `scripts/evaluation/rd_eval_freeze_snapshot_manifest.py`:

- snapshot id `20260730-coding-v2`, snapshot digest
  `sha256:ad4b55a9cfdbab22915c867c164bd2dc9d3cdf1361d08937c601e6403b5fe5b4`
- six manifests (dataset / environment / knowledge / analysis-plan /
  readiness / provenance) sharing that digest, with per-manifest SHA-256
  hashes recorded in `benchmark-provenance.json`
- the analysis plan (`scripts/evaluation/analysis-plan-20260730.json`)
  pre-registers the four arms, the confirmatory `D-A` contrast, four stratified
  sentinel cases, balanced `ABCD/BCDA/CDAB/DABC` sequences, seed `20260730`,
  missing-data rules, statistics and the expand decision thresholds
- the real `FileSystemCodingBenchmarkCatalog` discovers the snapshot and
  reports 20 cases; tampering with any manifest makes it disappear

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
