# Unsupported: bare-metal VM deployment (historical example)

> **This directory is NOT the supported deployment path.** The supported way to run
> RD-Bot is the Docker Compose stack — see [README.md](../../README.md) and
> [deploy/docker/README.md](../docker/README.md).
>
> The scripts here are sanitized remnants of the maintainer's private single-VM
> setup, kept only as documentation of the bare-metal JVM startup pattern
> (resource limits, git timeout, overlay copy, nohup restart). They reference
> placeholder hosts and repositories and are not maintained.

## What remains here

| File | Purpose |
| --- | --- |
| `start-backend.sh` | Historical example: restart the fat jar with Pi-friendly resource limits. Requires `RD_AGENT_RUNTIME_MUTATION_TOKEN` to be set (fail closed). |
| `application-local.server.yaml` | Historical example: server overlay with placeholder repositories and no fixed mutation token. |
| `application-local.example.yaml` | Conservative local-dev overlay example (mock platform, delivery off). |
| `episode-budget.sh` | Optional Pi episode-budget env freeze used by live probes. |
| `monitor-codex-task.sh` | Generic task polling loop against a running backend. |

## Still-accurate technical notes

- OpenCode-compatible providers reach the Pi credential relay at
  `/v1/messages` on the configured base URL using the `X-Api-Key` header (not Bearer).
  The base must be the provider root (e.g. `.../zen/go`), not `.../zen/go/v1`,
  otherwise the relay drops the path segment and the upstream 404s.
- Keep provider keys only in gitignored, `0600` local env files (`*.local`,
  `*.env`); never commit them.
- Integration smoke tests can target a throwaway PostgreSQL database via
  `-Drd.integration.stage-finalization.enabled=true -Drd.integration.stage-finalization.url=...`
  (see `RULE.md` for the exact focused-test commands).
