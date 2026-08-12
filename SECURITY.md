# Security Policy

## Reporting a Vulnerability

Please report security issues privately via [GitHub Security Advisories](https://github.com/wanghehe123/rd-bot/security/advisories/new) on [`wanghehe123/rd-bot`](https://github.com/wanghehe123/rd-bot).

If advisories are unavailable, open a **private** GitHub security report / limited-visibility issue on that repository. Do not post exploit details in public issues.

## Critical operator warning: unauthenticated admin

The `/admin` HTTP API and static admin UI currently have **no authentication** (no Spring Security / `SecurityFilterChain`). Anyone who can reach the service can call admin endpoints.

**Do not expose port `18080` to the public internet.** Run RD-Bot on localhost or a trusted network only until authentication is added.

## Secrets

- Load secrets from environment variables or local untracked files such as `*.env` and `application-local.yaml`.
- Never commit API keys, tokens, or credentials to the repository.

## Local demo credentials

Default MinIO / RustFS credentials in sample configuration (for example `rustfsadmin`) are for **local demo only**. Replace them before any shared or production-like deployment.
