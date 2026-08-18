## Why

需求评审等仅模型角色在本地稳定失败：`missing API key env: OPENCODE_API_KEY`。密钥只能从进程环境变量读取，管理台不能按供应商写入。每次不 `export` 就 FAILED_NEEDS_HUMAN。

## What Changes

- 供应商档案继续只存路由元数据；密钥写入独立表，按 `providerId` 保存。
- 管理 API 允许创建/更新供应商元数据，并按供应商写入/清除密钥。GET 只返回是否已配置，永不回明文。
- 执行解析叠加：库中密钥优先于 `System.getenv` / `launchctl`。仅模型执行器与 Docker / Pi 共用同一 resolver。
- 管理台新增「供应商配置」页（另一份前端计划实施）。本 change 的后端必须提供 HTTP 契约与 Vite 已有 `/admin/model-provider-profiles` 代理。

**非目标：** 不把密钥列并进 `rd_model_provider_profiles`；不做静止加密；不管理 OpenViking / MinIO 密钥；不删除供应商；不自动从 yaml 灌档案。

## Capabilities

### New Capabilities

- `requirement/provider-credential-console`: 按供应商保存密钥、管理 API、执行时叠加解析。

### Modified Capabilities

- （无。当前主 spec 只有 `knowledge/openviking-projection-admin`。）

## Impact

- **rag：** `ModelProviderCredentialStore` / Service；档案 service 仍拒绝把密钥当环境变量名。
- **exec：** `AuthEnvironmentResolver` 可被组合；Pi / Claude 继续按环境变量名解析。
- **bootstrap：** `p16`、Postgres 适配、管理 API、把 resolver 接到仅模型执行器与 Docker/Pi。
- **frontend（另一计划）：** 导航 + `/admin/model-providers` 页。
- **历史资料：** `docs/superpowers/specs/2026-08-17-provider-credential-console-design.md`；Pi 运行时设计中的 `ModelProviderProfile`（只作背景）。
- **当前锚点：** `ModelProviderProfile`、`OpenAiChatCompletionsRepairExecutor`、`AuthEnvironmentResolver`、`AgentExecutionProfileAdminController`。
