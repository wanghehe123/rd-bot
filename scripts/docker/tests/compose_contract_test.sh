#!/usr/bin/env bash
# Compose contract assertions for the RD-Bot release stack (openspec T06).
# Pure client-side: `docker compose config` only, daemon not required.
# Usage: bash scripts/docker/tests/compose_contract_test.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE="$ROOT/docker-compose.yml"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
ENV_FILE="$TMP/runtime.env"
cat > "$ENV_FILE" <<EOF
COMPOSE_PROJECT_NAME=rd-bot-contract-test
RD_BOT_IMAGE_TAG=contract-test
RD_BOT_PORT=18080
DOCKER_GID=999
RD_BOT_WORKSPACE_ROOT=$TMP/.rd-bot-data/workspaces
RD_BOT_EGRESS_NETWORK=rd-bot-egress-contract-test
POSTGRES_USERNAME=postgres
POSTGRES_PASSWORD=contract-test-password
RUSTFS_ACCESS_KEY_ID=contract-test-minio
RUSTFS_SECRET_ACCESS_KEY=contract-test-minio-secret
RUSTFS_BUCKET=biz
RD_AGENT_RUNTIME_MUTATION_TOKEN=
EOF

docker compose --env-file "$ENV_FILE" -f "$COMPOSE" config --format json > "$TMP/resolved.json"

python3 - "$TMP/resolved.json" <<'EOF'
import json, sys

resolved = json.load(open(sys.argv[1]))
services = resolved.get("services", {})
failures = []

def check(name, condition, detail=""):
    if condition:
        print(f"ok  - {name}")
    else:
        failures.append(name)
        print(f"FAIL- {name} {detail}")

# 1. host port = 127.0.0.1:${RD_BOT_PORT}:18080 (loopback only)
rd = services.get("rd-bot", {})
ports = rd.get("ports", []) or []
loopback_ports = [p for p in ports
                  if str(p.get("host_ip", "")) == "127.0.0.1" and str(p.get("target")) == "18080"]
check("app publishes only 127.0.0.1:<port>->18080",
      len(ports) == 1 and len(loopback_ports) == 1,
      f"got {ports}")

# 2. only rd-bot mounts /var/run/docker.sock
sock_services = [name for name, svc in services.items()
                 if any(str(v.get("source", v) if isinstance(v, dict) else v).endswith("docker.sock")
                        for v in (svc.get("volumes") or []))]
check("only rd-bot mounts the docker socket", sock_services == ["rd-bot"], f"got {sock_services}")

# 3. no service is privileged / no pid:host / no cgroup ns host
privileged = [name for name, svc in services.items()
              if svc.get("privileged") in (True, "true")]
check("no privileged services", not privileged, f"got {privileged}")

# 4. no infrastructure port published on the host by default
published = []
for name, svc in services.items():
    if name == "rd-bot":
        continue
    for p in (svc.get("ports") or []):
        published.append((name, p))
check("no infrastructure ports published by default", not published, f"got {published}")

# 5. rd-bot waits for migrations + minio-init success
deps = (rd.get("depends_on") or {})
mig = deps.get("migrations", {})
init = deps.get("minio-init", {})
check("rd-bot waits for successful migrations",
      mig.get("condition") == "service_completed_successfully", f"got {mig}")
check("rd-bot waits for successful bucket init",
      init.get("condition") == "service_completed_successfully", f"got {init}")

# 6. persistent stores declare explicit volumes
for store in ("postgres", "redis", "minio"):
    volumes = services.get(store, {}).get("volumes") or []
    named = [v for v in volumes if isinstance(v, dict) and str(v.get("source", "")).startswith("rd-bot-")]
    check(f"{store} has a named persistent volume", bool(named), f"got {volumes}")

# 7. workspace source and container target are the SAME absolute path
ws = [v for v in (rd.get("volumes") or []) if isinstance(v, dict) and str(v.get("target", "")).startswith("/") and str(v.get("target", "")) not in ("/config/application-docker.yaml",)]
sock_mount = [v for v in ws if str(v.get("source", "")).endswith("docker.sock")]
ws = [v for v in ws if not sock_mount or v is not sock_mount[0]]
ws = [v for v in ws if v.get("source") != "/var/run/docker.sock"]
same_path = any(str(v.get("source")) == str(v.get("target")) for v in ws)
check("workspace bind uses the same absolute path on host and container", same_path, f"got {ws}")

# 8. relay URL + agent network ride the egress network
raw_env = rd.get("environment") or {}
if isinstance(raw_env, dict):
    env = {k: (v if v is not None else "") for k, v in raw_env.items()}
else:
    env = {e.split("=", 1)[0]: e.split("=", 1)[1] if "=" in e else ""
           for e in raw_env}
relay_url = env.get("RD_EXECUTOR_PI_CREDENTIAL_RELAY_URL", "")
network_mode = env.get("RD_EXECUTOR_PI_NETWORK_MODE", "")
egress_net = (resolved.get("networks") or {}).get("egress", {}).get("name", "")
check("relay URL targets the rd-bot alias over HTTP",
      relay_url.startswith("http://rd-bot:18080/"), f"got {relay_url}")
check("relay sidecar network mode is the egress network",
      network_mode == egress_net and bool(egress_net), f"got {network_mode} vs {egress_net}")
aliases = (((rd.get("networks") or {}).get("egress")) or {}).get("aliases", [])
check("rd-bot answers to alias rd-bot on egress", "rd-bot" in aliases, f"got {aliases}")

if failures:
    raise SystemExit(f"{len(failures)} contract assertion(s) failed: {failures}")
print("compose contract: all assertions passed")
EOF

echo "compose config parsed successfully"
