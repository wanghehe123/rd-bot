## 1. Brownfield evidence

- [x] 1.1 对照 `RULE.md` §1.2、`RdTaskController`、`FeishuImMessageController`、`EngineRequirementExecutionProfileResolver.compatibilityRuntime` 和 `AgentRuntimeRouter`，确认写入面只接受需求、兼容默认是 Pi、未注册运行时失败关闭。
- [x] 1.2 记录本 worktree 缺少 `docs/openspec/historical-spec-provenance-audit.md`；把 `docs/superpowers/plans/2026-07-26-pi-agent-runtime-integration.md` 和评测/工单队列 superpowers 文档列为 superseded，不写入主 spec。

## 2. OpenSpec artifacts

- [x] 2.1 写 `proposal.md`，声明新能力 `requirement/delivery-platform`，并标明本 change 不改运行时。
- [x] 2.2 写 `specs/requirement/delivery-platform/spec.md`，覆盖需求写入、历史 BugFix 只读、飞书忽略非需求、Pi 唯一执行、已下线控制面。
- [x] 2.3 写 `design.md`，记录入口链路、历史枚举保留原因、非目标和未归档 strategy-console 草案冲突。

## 3. Validation and archive

- [x] 3.1 运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate converge-requirement-delivery-platform --strict`。
- [x] 3.2 运行 `./mvnw -pl rag -Dtest=RdTaskTypeTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 3.3 运行 `./mvnw -pl bootstrap -am -Dtest=EngineRequirementExecutionProfileResolverTest,FeishuImMessageControllerTest,FeishuImBeanWiringTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 3.4 运行 `cd frontend && node --experimental-strip-types --test test/rdTaskDraft.test.ts && npm run typecheck`。
- [x] 3.5 运行 `rg -n 'RdBotFixEngine|TicketRepairEngine|RagBugFixEngine|RepairRagPipeline|OpenAiChatCompletionsRepairExecutor|DockerClaudeCodeExecutor' --glob '!**/target/**' --glob '!openspec/**' --glob '!docs/**' --glob '!RULE.md'`，确认生产代码无命中。
- [x] 3.6 归档 delta 到 `openspec/specs/requirement/delivery-platform/spec.md`，再运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`。
