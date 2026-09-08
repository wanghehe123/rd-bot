# RD-Bot Pi Agent images

Two images are built from this directory:

| Image | Dockerfile | Purpose |
| --- | --- | --- |
| `rd-bot/pi-agent` | `Dockerfile` | Pi coding agent runtime: Node 22 slim base, bridge code from `src/` + `protocol/`, python3/git/jq toolchain, non-root `node` user, `/work/{input,output,cache}` mount points. |
| `rd-bot/pi-agent-qa` | `Dockerfile.qa` | QA agent runtime: everything above plus Playwright Chromium (browsers under `/ms-playwright`), `curl`/`zip` for health checks and evidence bundling. |

## First-time build (empty machine)

The QA image no longer depends on a pre-existing `rd-bot/pi-agent-qa` tag. Build the
base first, then pass it to the QA build:

```bash
cd bootstrap/src/main/resources/executor/pi
docker build --pull -t rd-bot/pi-agent:local -f Dockerfile .
docker build --pull --build-arg PI_BASE_IMAGE=rd-bot/pi-agent:local \
  -t rd-bot/pi-agent-qa:local -f Dockerfile.qa .
```

On the default path the QA image's `browser-cache` stage resolves to the Pi base
itself (whose `/ms-playwright` is empty), so `playwright install chromium` really
downloads the pinned browser build during the build.

## Optional browser cache

Rebuilding the QA image repeatedly? Seed the browser layer from the previous QA
image so the flaky Playwright CDN is only hit when versions change:

```bash
docker build --build-arg PI_BASE_IMAGE=rd-bot/pi-agent:local \
  --build-arg BROWSER_CACHE_IMAGE=rd-bot/pi-agent-qa:local \
  -t rd-bot/pi-agent-qa:local -f Dockerfile.qa .
```

`BROWSER_CACHE_IMAGE` is only a cache seed; the build succeeds without it.

## Restricted-network builds

Both Dockerfiles accept mirror overrides (default stays the official sources):

- `--build-arg NPM_REGISTRY=<mirror>` — npm registry for bridge dependencies and
  `@playwright/cli` install (bridge provision only; isolated agents stay offline).
- `--build-arg DEBIAN_MIRROR=<mirror>` — Debian apt mirror applied before
  `install-deps` / `apt-get install`.

Chromium download retries three times with `PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=120000`.

## Rebuild rules

Any change under this directory (bridge `src/`, `Dockerfile`, `Dockerfile.qa`,
`rd-qa-evidence.mjs`, `playwright-cli.config.json`, lockfile) requires rebuilding
**both** images — a running backend keeps serving the images it references by tag,
so a stale image silently resurrects old in-container rules. Verify the QA image
actually launches Chromium before trusting it:

```bash
docker run --rm --entrypoint node rd-bot/pi-agent-qa:local \
  -e "const { chromium } = require('playwright'); chromium.launch({headless:true}).then(b=>b.close())"
```

Bridge protocol tests: `npm test` in this directory (must stay green before rebuild).

## Security posture

- Images never contain or mount the Docker socket, host secret directories, or
  upstream model/GitHub credentials; model access goes through the backend's
  credential relay sidecar only.
- Agents run as the non-root `node` user (1000:1000); `/work` and `/ms-playwright`
  are owned by that user. Container tmpfs mounts must carry matching `uid=/gid=`
  options (see `RULE.md` §3.5.14).
