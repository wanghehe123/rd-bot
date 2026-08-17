## 1. Domain model and projection

- [x] 1.1 Add failing `AgentStrategyProfileServiceTest` covering four-role save, missing-role 400, stable `{projectId}:{strategyId}:{role}` projection, and bind-all-roles default
- [x] 1.2 Implement rag models (`AgentStrategyProfile`, `AgentStrategyRoleSlot`, `AgentStrategyImageMode`), store port, in-memory store, and `AgentStrategyProfileService` that projects through `AgentExecutionProfileService`
- [x] 1.3 Add failing test that empty strategy table synthesizes `legacy-current` from existing role bindings
- [x] 1.4 Implement legacy synthesis in the service (read-only; reject writes to `legacy-current`)

## 2. Persistence and Admin API

- [x] 2.1 Add failing SQL policy test then `p14_agent_strategy_profiles.sql`; update `sql/postgres/README.md`
- [x] 2.2 Implement Postgres mapper/row/store and wire beans in `AgentRuntimeControlPlaneConfiguration`
- [x] 2.3 Add failing `AgentStrategyAdminControllerTest` for GET/POST/PUT/default, 403/503 token, and four-role 400
- [x] 2.4 Implement `AgentStrategyAdminController` under `/admin/projects/{projectId}/agent-strategies` using `X-RD-Agent-Runtime-Token`
- [x] 2.5 Add failing image tests then Claude CUSTOM via `ProjectRuntimeProfileUploadService`, Pi CUSTOM persist-only, LOCAL_DEFAULT deletes Claude runtime profile

## 3. Frontend page

- [x] 3.1 Add failing `agentStrategyForm.test.ts` for defaults, four-role validation, local-default save without file, and per-role error copy
- [x] 3.2 Implement `agentStrategyForm.ts` and `projectService.ts` strategy types/APIs
- [x] 3.3 Add `AgentStrategyPage.tsx` at `/admin/projects/:projectId/agent-strategy` with list+editor, single token, agent-runtime banner, Pi custom-image notice
- [x] 3.4 Wire `App.tsx`, `AdminLayout` breadcrumb, `AdminFrontendController` SPA mapping; add failing SPA route test
- [x] 3.5 Replace project-list runtime/agent dialogs with navigate; remove 「运行镜像」; assert in frontend tests

## 4. Verification

- [x] 4.1 Run focused Maven tests for strategy service/controller/SQL/SPA
- [x] 4.2 Run `cd frontend && node --experimental-strip-types --test test/agentStrategyForm.test.ts test/viteProxy.test.ts && npm run typecheck`
- [x] 4.3 Run `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`
