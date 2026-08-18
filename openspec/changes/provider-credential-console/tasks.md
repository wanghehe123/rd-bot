## 1. 领域与存储

- [ ] 1.1 `ModelProviderCredential` / Store / in-memory / Service：保存、清除、按 env 名解析最新一条
- [ ] 1.2 档案 service 保持「只存 env 名」；元数据命令拒绝 `apiKey`
- [ ] 1.3 `p16_model_provider_credentials.sql` + README 行 + Postgres store

## 2. 执行解析

- [ ] 2.1 组合 `AuthEnvironmentResolver`：库存 → getenv → launchctl
- [ ] 2.2 `OpenAiChatCompletionsRepairExecutor` 使用该 resolver，而不是裸 `System.getenv`
- [ ] 2.3 Docker Claude / Pi 的 Spring 装配改用同一 resolver

## 3. 管理 API

- [ ] 3.1 GET 列表/详情附带 `credentialConfigured` / `credentialUpdatedAt`
- [ ] 3.2 PUT 元数据（mutation token；拒绝 body 里的 `apiKey`）
- [ ] 3.3 PUT `/credential` 写入或清除
- [ ] 3.4 缺 key 失败文案指向供应商配置页

## 4. 前端（另一计划）

- [ ] 4.1 不在本后端任务里改页面。交接：`docs/superpowers/plans/2026-08-17-provider-credential-console-frontend.md`
