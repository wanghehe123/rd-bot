## Purpose

让管理员按供应商在管理台写入 API key，执行时优先使用已保存密钥，HTTP 与档案模型永不回发明文。

## ADDED Requirements

### Requirement: 档案不得保存或回传密钥值

`ModelProviderProfile` MUST 只保存路由元数据与环境变量名。`credentialValue()` MUST 恒为空字符串。系统 MUST NOT 把 API key 写入 `rd_model_provider_profiles` 或任何 GET 响应字段。

#### Scenario: 注册档案时拒绝把密钥当环境变量名

- **WHEN** 创建或更新供应商时 `credentialEnvironmentVariable` 不是 `[A-Z_][A-Z0-9_]*`
- **THEN** 系统拒绝保存并返回校验错误

#### Scenario: 列表不包含密钥

- **WHEN** 客户端 GET `/admin/model-provider-profiles`
- **THEN** 每条记录可以包含 `credentialConfigured` 与 `credentialUpdatedAt`，MUST NOT 包含 `apiKey` 或非空 `credentialValue`

### Requirement: 按供应商写入与清除密钥

系统 MUST 将密钥保存在独立于档案的存储中，主键为 `providerId`。写入与清除 MUST 校验 `X-RD-Agent-Runtime-Token`。空 `apiKey` MUST 删除该供应商已保存密钥。

#### Scenario: 保存后列表显示已配置

- **WHEN** 管理员对已存在的供应商 PUT `/admin/model-provider-profiles/{providerId}/credential` 且 body 为非空 `apiKey`
- **THEN** 随后 GET 列表中该供应商 `credentialConfigured` 为 true，且响应中没有密钥原文

#### Scenario: 清除后回退环境变量

- **WHEN** 管理员对该供应商 PUT credential 且 `apiKey` 为空白
- **THEN** `credentialConfigured` 为 false，执行解析不再使用该条库存密钥

#### Scenario: 无令牌不得写密钥

- **WHEN** PUT credential 未带合法 mutation token
- **THEN** 系统不写入密钥并返回 403 或 503（与现网 mutation 策略一致）

### Requirement: 执行解析优先使用已保存密钥

仅模型执行器与 Docker / Pi 的鉴权解析 MUST 先查找与请求环境变量名匹配的已保存供应商密钥，未命中再读进程环境变量与 `launchctl`。

#### Scenario: 未 export 时评审可用库存密钥

- **WHEN** 进程环境没有 `OPENCODE_API_KEY`，但某供应商档案的 `credentialEnvironmentVariable` 为 `OPENCODE_API_KEY` 且已保存密钥
- **THEN** `REQUIREMENT_REVIEWER` 的仅模型执行 MUST NOT 因 missing API key env 失败

#### Scenario: 库存未配置时仍可读环境变量

- **WHEN** 没有任何供应商为该环境变量名保存密钥，且进程环境有该变量
- **THEN** 执行器使用环境变量中的值

### Requirement: 元数据与密钥分接口

创建或更新供应商元数据 MUST 使用不含密钥的请求体。请求体若包含 `apiKey`，系统 MUST 拒绝，以免密钥进入档案 JSON。

#### Scenario: 元数据 PUT 携带 apiKey 被拒绝

- **WHEN** PUT `/admin/model-provider-profiles/{providerId}` 的 JSON 含有 `apiKey`
- **THEN** 系统不更新档案、不写入密钥，并返回 400
