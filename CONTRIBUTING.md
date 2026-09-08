# Contributing to RD-Bot

Thanks for your interest in RD-Bot — an experimental, personal-maintenance project
for orchestrating AI coding agents through an auditable Manager/Execute/Audit
delivery pipeline. This page covers the minimum needed to build, test and propose
changes. For threat model and supported scope, read [`SECURITY.md`](SECURITY.md);
for license and third-party boundaries, see [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Minimum development environment

- JDK 21 (Temurin recommended), Maven Wrapper included (no global Maven needed)
- Node.js 22 + npm (frontend and Pi bridge)
- Docker Engine / Docker Desktop with Compose v2 (integration and image work)
- PostgreSQL 16 + pgvector, Redis 7, MinIO — only when running beyond the in-memory
  store locally; the Compose stack provides all of them

## Getting started

```bash
git clone <your fork>
cd RD-Bot
./mvnw -pl bootstrap -am -DskipTests package   # backend build
cd frontend && npm ci && npm run build         # admin UI build
```

For a full local stack without host toolchains, use the supported Docker path:

```bash
./scripts/rd-bot.sh up      # builds images and starts the complete stack
```

## Branches and tests

- Branch naming: `feat/<topic>`, `fix/<topic>`, `docs/<topic>`.
- One logical change per commit; commit message format `<type>: <description>`.
- Run before submitting:
  - backend: `./mvnw test`
  - frontend: `cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build`
  - Pi bridge (if touched): `cd bootstrap/src/main/resources/executor/pi && npm test`
- Changes to the delivery pipeline, Pi bridge, QA evidence or executor defaults have
  extra repository rules in [`AGENTS.md`](AGENTS.md) and [`RULE.md`](RULE.md) —
  please read the relevant section before proposing changes there.

## OpenSpec

Behavioral changes go through [OpenSpec](openspec/) proposals: create a change
directory under `openspec/changes/<name>/` with a proposal, design (when needed),
tasks, and spec deltas with `WHEN/THEN` scenarios; keep `openspec validate --all --strict`
green. Existing specs live under `openspec/specs/`. Historical design documents are
kept under `docs/superpowers/specs/` and are classified in
`docs/openspec/historical-spec-provenance-audit.md` — they are background, not
current behavior contracts.

## Security issues

Do **not** open public issues for security problems. Follow
[`SECURITY.md`](SECURITY.md) and use private GitHub security advisories.

## What the project is not

RD-Bot's own development is AI-assisted and uses internal automation that is not
part of the public project: external contributors do **not** need any specific AI
agent tooling, subscriptions or services to build, run, test and contribute code.
