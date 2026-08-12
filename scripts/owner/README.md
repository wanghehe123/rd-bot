# Owner / maintainer scripts

Scripts in this directory are **maintainer-specific**. They encode private org defaults, local machine paths, or domain seed data that OSS contributors do not need.

Contributors should follow the root [README](../../README.md) quick-start (`docker compose` → `bootstrap-db` → `application-local.yaml` → `mvnw`).

| Script | Purpose |
|--------|---------|
| `start-rd-bot-owner-allowlist.sh` | Start the backend with a broad Docker execution allowlist (override via `RD_OWNER_GITHUB_ORG` / allowlist env vars). |
| `seed-waimai-*.sh` | Seed domain knowledge into a **running** backend; require matching project/domain data already present. |
