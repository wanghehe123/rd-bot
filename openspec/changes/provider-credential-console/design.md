## Context

`ModelProviderProfile` 是公开路由元数据。执行器按 `credentialEnvironmentVariable` 向 `AuthEnvironmentResolver` 要值。当前实现只读进程环境和 macOS `launchctl`。仅模型路径把缺 key 写成角色失败。

本地库是 `ragent`。写控制面已有 `X-RD-Agent-Runtime-Token`。

## Goals / Non-Goals

**Goals**

- 按供应商在管理台保存 API key，重启后仍有效。
- GET 永不回密钥；档案模型永不带密钥值。
- 评审 / 架构（仅模型）与 Coding / QA（Docker、Pi）都吃到同一份密钥。
- 环境变量仍可用，作为未在界面配置时的回退。

**Non-Goals**

- 静止加密、KMS、多租户 ACL。
- OpenViking / 对象存储密钥。
- 删除供应商、从 yaml 自动灌档。

## Decisions

1. **独立凭证表** `rd_model_provider_credentials(provider_id PK, secret TEXT, updated_at)`。外键到 `rd_model_provider_profiles(provider_id)` ON DELETE CASCADE。
2. **按环境变量名解析叠加。** 执行器往往只有 env 名（例如 `OPENCODE_API_KEY`）。Service 找出 `credentialEnvironmentVariable` 相等且已存密钥的档案，取 `updated_at` 最新的一条。再回退 getenv / launchctl。
3. **写接口与策略页同令牌。** 不新造认证。
4. **元数据 PUT 拒绝 `apiKey` 字段。** 避免有人把密钥写进档案 JSON。
5. **失败文案** 改为提示去供应商配置页或 export，仍不打印密钥。

## Risks / Trade-offs

- Postgres 超级用户能读明文。接受：本机管理台与现网「环境变量持有密钥」同级；不做无主密钥的假加密。
- 多个档案共用同一 env 名时，取最近更新。写在设计里，UI 提示「该环境变量名被多个供应商共用」。

## Migration Plan

`p16_model_provider_credentials.sql`。对现有 `ragent` 手工执行（`bootstrap-db.sh` 默认库是 `rdbot`，本地要用 `POSTGRES_DB=ragent`）。

## Open Questions

无。v1 不做加密、不做删除。
