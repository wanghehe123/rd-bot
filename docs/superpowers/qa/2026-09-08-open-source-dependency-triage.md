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
| Maven | CycloneDX Maven plugin 2.9.1 + osv-scanner 2.5.1 | 2026-09-09 17:31 UTC+8 | reactor aggregate SBOM (113 packages) |
| Container images | Trivy 0.74.0, vulnerability DB refreshed 2026-09-09 | 2026-09-09 17:07–17:26 UTC+8 | `rd-bot/app:oss-test`, `rd-bot/pi-agent:oss-test`, `rd-bot/pi-agent-qa:oss-test` (OS packages, arm64) |

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

The release-machine re-scan is now complete enough to reject the previous
conditional PASS. A direct recursive source scan cannot resolve this reactor's
unpublished `0.1.0-SNAPSHOT` modules, so the reproducible path was:

```bash
./mvnw -q -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputFormat=json -DoutputName=rd-bot-g10-bom
osv-scanner scan source --sbom /private/tmp/bom.json --format json
```

The aggregate SBOM contains 113 packages. osv-scanner returned **82 unique
advisory IDs / 83 advisory groups**; **41 groups have CVSS >= 7 across 13
runtime package entries**. The highest-risk surfaces include
`tomcat-embed-core 10.1.48` (max 9.8), `netty-handler 4.1.128.Final` (9.1),
Jackson 2.19.2, PostgreSQL JDBC 42.7.8, Spring Framework 6.2.12 and Spring Boot
3.5.7. Tomcat/Spring MVC/Jackson/PostgreSQL are on the supported HTTP and
database path, so this is release-blocking pending version upgrades and
post-upgrade regression/re-scan. The full machine-readable result is a local
temporary artifact (`/private/tmp/rd-bot-osv-maven.json`) and is not committed.

Blocking rule (from the plan): a critical/high that is runtime-reachable and
affects host code execution, path traversal, request smuggling, credential or
data exposure must be fixed or the release is blocked. Test-scope-only or
unreached-module findings may be deferred with the dependency path and a
configuration/code anchor.

## Container images

Trivy's vulnerability DB downloaded successfully after using a clean Docker
credential configuration. The scan exposed two fixed, Git/HTTPS-reachable
Critical findings in `libgnutls30` in each Pi image. The Pi Dockerfile now runs
the current Debian security upgrade before installing the toolchain; both Pi
images were rebuilt, moving `libgnutls30` from `3.7.9-2+deb12u5` to
`3.7.9-2+deb12u7`. The two fix-available Critical records are gone from each
rebuilt image.

Remaining OS-package results (High/Critical package-vulnerability records):

| Image | Result | Assessment |
| --- | ---: | --- |
| app (Ubuntu 22.04) | 204 (6 Critical) | all records belong to `linux-libc-dev`; these are kernel-header advisories, while the container executes the host kernel |
| Pi (Debian 12.15) | 262 (15 Critical), fix-available 0 | remaining Critical records are Perl, SQLite, zlib and kernel headers; contained to the non-root, socket-less task container but not yet fully reachability-triaged |
| QA (Debian 12.15) | 289 (17 Critical), fix-available 0 | Pi set plus GLib/XML2 browser dependencies; the QA browser processes target content, so full applicability triage is still required |

These counts are not a zero-vulnerability claim. The fixed GnuTLS exposure is
closed; the remaining Critical/High set and the unscanned PostgreSQL/Redis/
MinIO images keep G10 blocked.

Base images are pinned tags (see `docker-compose.yml`, root `Dockerfile`,
Pi `Dockerfile`); bumps happen deliberately, never via `:latest`.

## Outcome

- npm: 0 known vulnerabilities in both release lockfiles (npm audit +
  osv-scanner, 2026-09-09); achieved by version upgrades only, no audit
  suppressions.
- Maven: aggregate SBOM scan found runtime-path High/Critical advisories;
  dependency upgrades and regression are required.
- Containers: the two fix-available GnuTLS Critical findings were repaired and
  both Pi images rebuilt, but remaining unfixed findings need applicability
  triage and the infrastructure images still need scanning.
- **G10 result: BLOCKED. The branch may be pushed for review, but the repository
  must not yet be published as an accepted open-source release.**
