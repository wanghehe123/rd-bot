# RD-Bot 多 Agent RAG 编排快速验收收口

日期：2026-07-04

## 1. 加速策略

本阶段停止继续扩充边界测试，只保留最短生产验收闭环：

- 不再重复长时间全量 smoke；先用 provider preflight、readiness 和证据台账判断是否值得进入 30 分钟级链路。
- 不再把 sidecar 证据当成完整验收通过；sidecar 只标记其覆盖的验收点。
- 当前代码改造只用聚焦单测和真实 smoke 证明，不追加无关重构。
- 本机最终验收改为单命令快门禁：先刷新 15 项证据台账，再立即执行最终门禁，避免拿旧台账反复判断。
- 完整生产验收只在 #3、#6、#7、#8、#9、#14 专项 sidecar 和远端反查证据补齐后重跑一次总入口。

## 2. 当前可用配置

配置真值来自 `bootstrap/src/main/resources/application.yaml`，本轮真实 provider 验收通过环境变量覆盖了 MiniMax 的协议和 base URL。

| 类型 | 当前配置来源 | 当前可用于验收的值 |
| --- | --- | --- |
| RD-Bot HTTP | `server.port` | `18080` |
| PostgreSQL | `spring.datasource.*` | 默认 `jdbc:postgresql://127.0.0.1:5432/ragent`，用户名 `postgres` |
| Redis | `spring.data.redis.*` | 默认 `127.0.0.1:6379` |
| RustFS/S3 | `rustfs.*` | 默认 `http://localhost:9000`，bucket `biz` |
| 目标仓库 | `rd.executor.repository.*` | `example-owner/example-repo`，base `main` |
| Docker executor | `rd.executor.docker.*` | image `rd-bot/claude-code:local`，work branch allowlist 含 `requirement/*` |
| Provider 1 | `rd.executor.docker.providers[0]` | `long-cat`，默认 Anthropic-compatible URL，token env `LONGCAT_API_KEY` |
| Provider 2 | `rd.executor.docker.providers[1]` | `minimax`，`application.yaml` 默认 `openai-chat-completions` + `https://api.minimaxi.com/v1`；本轮验收通过 `MINIMAX_PROTOCOL=anthropic-compatible`、`MINIMAX_BASE_URL=https://api.minimaxi.com/anthropic` 接入 Docker Claude Code provider 链 |
| GitHub | `rd.github.code-platform.*` | real mode，默认 `GH_CLI_LOCAL_SMOKE`，本机已通过 gh cli 创建真实 PR；PAT 只作为刷新专项证据时的备选 |
| Feishu alert | `rd.feishu.im.alert.*` | 通过 `FEISHU_IM_ALERT_CHAT_ID` 开启真实告警 |

## 3. 已通过的真实证据

| 验收面 | 当前结论 | 证据路径 |
| --- | --- | --- |
| Feishu 告警真实送达 | 通过专项 smoke，6 类告警均返回真实 messageId | `qa-runs/multi-agent-production-acceptance/feishu-alert-production-acceptance-20260704-030907.md` / `.json` |
| GitHub PR 远端反查 | 通过专项 smoke，真实 GitHub PR #15 body 包含交付复核、QA 证据、完整 taskId 和产物链接，并完成 secret needle 扫描 | `qa-runs/multi-agent-production-acceptance/github-pr-remote-evidence-production-acceptance-20260704-165255.md` / `.json` |
| Skill 策略与安装 | 通过专项 smoke，低风险安装、未授权拒绝、高风险等待审批、metadata 校验均成立 | `qa-runs/multi-agent-production-acceptance/skill-production-acceptance-20260704-030532.md` / `.json` |
| Provider preflight 门禁 | 通过真实 HTTP 探活，`long-cat` 与 `minimax` 均返回 200，`successfulProviderCount=2`；报告脱敏保留 provider、adapter、HTTP 状态和 response fingerprint | `qa-runs/multi-agent-production-acceptance/provider-preflight-production-acceptance-20260704-160250.md` / `.json` |
| 最小多 Agent 生产 smoke | 通过真实 RD-Bot HTTP、PostgreSQL、provider、GitHub PR 链路；主任务 `7479113030836555776` 完成四角色链路并创建 PR #15 | `qa-runs/multi-agent-production-acceptance/multi-agent-production-acceptance-20260704-103658.md` / `.json` |
| 需求评审、Docker coding、QA 阻断、交付复核失败、恢复、指标审计专项 | 六个缺口专项均已补齐并通过真实 sidecar | `requirement-review-blocker-production-acceptance-20260704-122535.json`、`docker-coding-production-acceptance-20260704-123318.json`、`qa-failure-blocker-production-acceptance-20260704-163746.json`、`delivery-review-failure-production-acceptance-20260704-124029.json`、`workflow-recovery-production-acceptance-20260704-122834.json`、`observability-metrics-production-acceptance-20260704-170114.json` |
| 经验复用 | follow-up task `7479118671961526272` 的角色上下文检索到 20 条历史经验证据，五类经验均具备 hash、source artifact 和 redacted 标记 | `qa-runs/multi-agent-production-acceptance/multi-agent-production-acceptance-20260704-103658.md` / `.json` |
| 15 项生产验收证据台账 | 最新台账汇总为 `PASSED=15`、`FAILED=0`、`NOT_RUN=0`，完整 #15 已由 #1-#14 全部通过证明 | `qa-runs/multi-agent-production-acceptance/production-acceptance-evidence-ledger-20260704-170527.md` / `.json` |
| 最终门禁 | `ProductionAcceptanceFinalGateSnapshotTest` 已接受最新台账 | `ProductionAcceptanceFinalGate` |

## 4. 当前完整验收状态

完整 15 项生产验收已经通过。

最新 15 项证据台账：

- `qa-runs/multi-agent-production-acceptance/production-acceptance-evidence-ledger-20260704-170527.md`
- 结论：`PASSED_PRODUCTION_ACCEPTANCE_LEDGER`
- PASSED：#1-#15
- FAILED：无
- NOT_RUN：无

最终报告仍保留最小 smoke 和各专项 sidecar 的边界：`multi-agent-production-acceptance-20260704-103658.md/json` 证明成功主链路；需求评审阻断、Docker coding、QA 失败阻断、交付复核失败、恢复、Feishu、Skill、远端 PR 反查和指标审计由各自专项 sidecar 证明。最终台账只聚合这些真实证据，不把本机 Maven 回归计入生产验收通过。

## 5. 剩余阻断项

无。早期 #3、#6、#7、#8、#9、#14 缺口已经由同版本、同环境、同执行人、可追溯 sidecar 补齐；后续只需要在生产环境变更、凭据轮换、provider 协议调整或目标仓库替换时重新跑对应专项 smoke。

## 6. 最短复验命令

只刷新台账、不访问外部系统：

```bash
./mvnw -pl bootstrap -am -Dtest=ProductionAcceptanceEvidenceLedgerSnapshotTest \
  -Drd.integration.evidence-ledger.enabled=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

刷新台账并执行最终门禁；当前预期通过，且只有最新台账 #1-#15 全部 `PASSED` 时才通过：

```bash
./mvnw -pl bootstrap -am -Dtest=ProductionAcceptanceEvidenceLedgerSnapshotTest,ProductionAcceptanceFinalGateSnapshotTest \
  -Drd.integration.evidence-ledger.enabled=true \
  -Drd.integration.final-acceptance-gate.enabled=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Provider 前置探活已经通过；如需重新验证同一能力，需在当前 shell 通过秘密存储或交互式方式注入 provider env，再运行：

```bash
./mvnw -pl bootstrap -am -Dtest=ProviderPreflightRealSmokeTest \
  -Drd.integration.provider-preflight.enabled=true \
  -Drd.provider.preflight.smoke.production-evidence=true \
  -Drd.provider.preflight.smoke.rd-bot-version=0.1.0-local-real-smoke \
  -Drd.provider.preflight.smoke.environment-id=local-machine \
  -Drd.provider.preflight.smoke.executed-by=codex-local \
  -Drd.provider.preflight.smoke.expected-provider-count=2 \
  -Drd.provider.preflight.smoke.providers=long-cat,minimax \
  -Drd.provider.preflight.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

完整总 smoke 如需重新跑，必须继续传入同版本、同环境、同执行人的专项 sidecar；缺少任一必要 sidecar 时不能把 #15 记为通过：

```bash
./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \
  -Drd.integration.multi-agent.enabled=true \
  -Drd.multi-agent.smoke.production-evidence=true \
  -Drd.multi-agent.smoke.rd-bot-version=0.1.0-local-real-smoke \
  -Drd.multi-agent.smoke.environment-id=local-machine \
  -Drd.multi-agent.smoke.executed-by=codex-local \
  -Drd.multi-agent.smoke.base-url=http://127.0.0.1:18080 \
  -Drd.multi-agent.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/ragent \
  -Drd.multi-agent.smoke.postgres-user=postgres \
  -Drd.multi-agent.smoke.postgres-password="$POSTGRES_PASSWORD" \
  -Drd.multi-agent.smoke.repository-url=https://github.com/example-owner/example-repo.git \
  -Drd.multi-agent.smoke.repo-owner=example-owner \
  -Drd.multi-agent.smoke.repo-name=example-repo \
  -Drd.multi-agent.smoke.task-id=7479113030836555776 \
  -Drd.multi-agent.smoke.expected-provider-count=2 \
  -Drd.multi-agent.smoke.provider-secret-env-names=LONGCAT_API_KEY,MINIMAX_API_KEY \
  -Drd.multi-agent.smoke.github-code-platform-mode=real \
  -Drd.multi-agent.smoke.github-auth-mode=GH_CLI_LOCAL_SMOKE \
  -Drd.multi-agent.smoke.provider-preflight-evidence-json=qa-runs/multi-agent-production-acceptance/provider-preflight-production-acceptance-20260704-160250.json \
  -Drd.multi-agent.smoke.feishu-alert-evidence-json=qa-runs/multi-agent-production-acceptance/feishu-alert-production-acceptance-20260704-030907.json \
  -Drd.multi-agent.smoke.recovery-evidence-json=qa-runs/multi-agent-production-acceptance/workflow-recovery-production-acceptance-20260704-122834.json \
  -Drd.multi-agent.smoke.skill-policy-evidence-json=qa-runs/multi-agent-production-acceptance/skill-production-acceptance-20260704-030532.json \
  -Drd.multi-agent.smoke.requirement-review-evidence-json=qa-runs/multi-agent-production-acceptance/requirement-review-blocker-production-acceptance-20260704-122535.json \
  -Drd.multi-agent.smoke.docker-coding-evidence-json=qa-runs/multi-agent-production-acceptance/docker-coding-production-acceptance-20260704-123318.json \
  -Drd.multi-agent.smoke.qa-failure-evidence-json=qa-runs/multi-agent-production-acceptance/qa-failure-blocker-production-acceptance-20260704-163746.json \
  -Drd.multi-agent.smoke.delivery-review-failure-evidence-json=qa-runs/multi-agent-production-acceptance/delivery-review-failure-production-acceptance-20260704-124029.json \
  -Drd.multi-agent.smoke.github-pr-remote-evidence-json=qa-runs/multi-agent-production-acceptance/github-pr-remote-evidence-production-acceptance-20260704-165255.json \
  -Drd.multi-agent.smoke.observability-metrics-evidence-json=qa-runs/multi-agent-production-acceptance/observability-metrics-production-acceptance-20260704-170114.json \
  -Drd.multi-agent.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Drd.multi-agent.smoke.request-timeout-seconds=900 \
  -Drd.multi-agent.smoke.completion-timeout-seconds=1800 \
  -Drd.multi-agent.smoke.poll-interval-seconds=5 \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 7. 当前阶段结论

当前阶段收口为“技术文档和验收文档已按最终真实证据回写，代码侧关键缺陷已通过聚焦验证，provider preflight、最小多 Agent 生产 smoke、专项 sidecar、远端 PR 反查、指标审计和最终门禁均已通过；完整 15 项生产验收为 `PASSED_PRODUCTION_ACCEPTANCE_LEDGER`”。

后续不建议继续无限跑总 smoke。下一步只需在提交前做代码评审、按功能边界拆分提交，并在生产参数或外部平台变更时重跑对应专项 smoke。
