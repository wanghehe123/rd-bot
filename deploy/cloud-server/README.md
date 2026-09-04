# RD-Bot cloud server (106.55.13.166)

Infrastructure binds to **localhost on the VM** only. From your laptop, use SSH port forwarding to reach services or the API.

## OpenCode provider (live verification)

Default Pi provider `opencode-go` uses:

- **Model:** `qwen3.8-flash` (`RD_AI_CHAT_DEFAULT_MODEL` to override)
- **Protocol:** `ANTHROPIC_MESSAGES` → relay path `/v1/messages` on base `https://opencode.ai/zen/go` (`X-Api-Key`, not Bearer). Base must be `.../zen/go` not `.../zen/go/v1`, otherwise the relay drops `/zen/go` and 404s.
- **Credential:** `OPENCODE_API_KEY` in `.env.opencode.local`

`deploy/cloud-server/start-backend.sh` copies `application-local.server.yaml` into the runtime overlay; restart the backend after changing provider settings. `run-codex-memory-live-task.py` upserts the same profile into `rd_model_provider_profiles` before submitting tasks.

## One-time setup on the server

```bash
cd ~/RD-Bot
sudo apt-get install -y openjdk-21-jdk-headless nodejs npm
cp deploy/cloud-server/application-local.example.yaml \
   bootstrap/src/main/resources/application-local.yaml
docker compose up -d
./scripts/bootstrap-db.sh
docker exec rd-bot-postgres psql -U postgres -tc \
  "SELECT 1 FROM pg_database WHERE datname='rdbot_acceptance'" | grep -q 1 \
  || docker exec rd-bot-postgres psql -U postgres -c "CREATE DATABASE rdbot_acceptance"
POSTGRES_DB=rdbot_acceptance ./scripts/bootstrap-db.sh
```

## Start backend

```bash
cd ~/RD-Bot
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
SPRING_PROFILES_ACTIVE=local \
SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:./bootstrap/src/main/resources/application-local.yaml \
  ./mvnw -pl bootstrap spring-boot:run
```

API: `http://127.0.0.1:8080` on the server.

## SSH tunnel from laptop

```bash
ssh -L 8080:127.0.0.1:8080 -L 5432:127.0.0.1:5432 ubuntu@106.55.13.166
```

Then open `http://127.0.0.1:8080/admin/` locally.

## Integration tests (acceptance DB)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q -pl bootstrap -am \
  -Drd.integration.stage-finalization.enabled=true \
  -Drd.integration.stage-finalization.url='jdbc:postgresql://127.0.0.1:5432/rdbot_acceptance?client_encoding=UTF8' \
  -Dtest='*ProjectMemory*,PostgresRequirementStageFinalizationRealSmokeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Git clone on this VM

`github.com:443` is often slow or blocked from the VM, so `start-backend.sh` clones the live waimai repo from `~/mirror/waimai.git` and keeps `git push` on tokenized GitHub (`pushInsteadOf`). Do not point both fetch and push at the mirror.

Keep `GITHUB_PAT` / `GH_TOKEN` in `~/RD-Bot/.env.opencode.local` (mode 600). Do not rsync that file from a laptop.

## Sync code from laptop

```bash
rsync -az --delete \
  --exclude '.git' --exclude 'node_modules' --exclude 'target' \
  --exclude 'frontend/node_modules' --exclude 'application-local.yaml' \
  --exclude '.env.opencode.local' --exclude '.env.*.local' \
  ./ ubuntu@106.55.13.166:~/RD-Bot/
```
