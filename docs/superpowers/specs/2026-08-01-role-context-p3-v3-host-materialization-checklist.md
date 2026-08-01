# P3-V3 前：Host 物化 / 镜像重建清单（2026-08-01）

## 状态

`READY_FOR_SENTINEL_REHEARSAL` — Host 可 opt-in 物化 `rd-pi-request/v2`；生产默认仍为 `v1`。
未启用生产灰度，未扩大 `dynamicStateEnabled` / `FACTS_V1`。

对应方案：`docs/superpowers/specs/2026-08-01-role-context-optimization-validation-and-improvement-plan.markdown` §P3-V2→V3。

---

## 1. Host 物化清单（已完成）

| 项 | 状态 | 路径 / 开关 |
| --- | --- | --- |
| request protocol 配置默认 v1 | 完成 | `rd.executor.pi.request-protocol-version`（默认 `v1`） |
| v2 写入固定 Input Manifest 路径 | 完成 | `/work/input/role-execution-input-manifest.json` via `PiRequestV2Materializer` |
| v2 写入完整 `contextPolicy`（非仅 hash） | 完成 | `DockerPiAgentExecutor.writeRequest` when v2 |
| Orchestrator 透传 manifest/policy JSON | 完成 | `RequirementExecutionRequest.inputManifestJson/contextPolicyJson` |
| Adapter 透传至 `contextJson` | 完成 | `EngineRequirementExecutorAdapter.contextJson` |
| Host 校验 runtime-context-manifest | 完成 | `RuntimeContextPreflightValidator` after container exit (v2 only) |
| 生产默认仍 v1 / LEGACY policy | 完成 | builder 仍 `LEGACY_OBSERVE_ONLY`；未开灰度 |

启用 V3 sentinel rehearsal（仅本机/受控环境）：

```yaml
rd:
  executor:
    pi:
      request-protocol-version: v2
```

或环境变量 / 属性：`RD_EXECUTOR_PI_REQUEST_PROTOCOL_VERSION=v2`（若已绑定）/
`rd.executor.pi.request-protocol-version=v2`。

v2 要求 `inputManifestHash` + 完整 `contextPolicyJson`；缺失时 Host 在发容器前失败。

---

## 2. Bridge / 离线契约（V0–V2，已完成）

| 项 | 状态 |
| --- | --- |
| `rd-pi-request/v2` + policy/manifest schemas | 完成 |
| `discoverContextFiles` ROOT_ONLY / allowlist / LEGACY | 完成 |
| `context-preflight.mjs` provider 启动前 fail-closed | 完成 |
| fixture repo `test/fixtures/context-policy-repo` | 完成 |

---

## 3. 镜像重建（已完成，记录 digest）

构建命令：

```bash
cd bootstrap/src/main/resources/executor/pi
docker build -f Dockerfile -t rd-bot/pi-agent:local -t rd-bot-pi-agent:context-v2 .
docker build -f Dockerfile.qa -t rd-bot/pi-agent-qa:local -t rd-bot-pi-qa:context-v2 .
docker image inspect rd-bot/pi-agent:local --format '{{.Id}}'
docker image inspect rd-bot/pi-agent-qa:local --format '{{.Id}}'
```

| 镜像 tag | Image Id（不可变） | Created (UTC) |
| --- | --- | --- |
| `rd-bot/pi-agent:local` / `rd-bot-pi-agent:context-v2` | `sha256:a1fc49c086ed8ca8970e9688fd46848793a9f992c340de6dd256f5a15550cc1e` | 2026-08-01T15:01:15Z |
| `rd-bot/pi-agent-qa:local` / `rd-bot-pi-qa:context-v2` | `sha256:ae44977212ba3ad443d4a6691777355069fa8e407e3fa9d441b0d16dfecd167e` | 2026-08-01T15:09:34Z |

Profile snapshot 必须保存上表 **Id**，不能只存可移动 tag。

容器内抽检：

- `/opt/rd-pi-bridge/src/context-preflight.mjs` 存在
- `/opt/rd-pi-bridge/protocol/rd-pi-request-v2.schema.json` 存在
- `REQUEST_PROTOCOL` 仍为 `rd-pi-request/v1`（默认）；`REQUEST_PROTOCOL_V2` 可用

---

## 4. V3 sentinel 仍缺（付费 / 可控 fake provider）

1. 用 fixture repo 写不同 sentinel 文本到多层 `AGENTS.md`/`CLAUDE.md`。
2. Host 以 **v2 + ROOT_ONLY（或精确 allowlist）** 物化 expected hashes（需在 rehearsal 路径构造 policy，不是生产 LEGACY builder）。
3. 跑一次 Pi（真实或 fake provider），证明模型实际输入只含 expected sentinel。
4. 核对 `RESOURCES_LOADED` / runtime manifest / Host artifact 路径·hash·order 一致。
5. **不**在失败时把 Profile 默认切到 context v2。

---

## 5. 明确不做

- 生产默认 `request-protocol-version=v2`
- 同步扩大 `dynamicStateEnabled` / `FACTS_V1`
- Claude V4（可记 `UNAVAILABLE`，不阻塞 Pi）
- 同 attempt 回写旧 snapshot / 在新镜像恢复隐式嵌套发现

---

## 6. 定向验收数据（2026-08-01）

### Node

```bash
cd bootstrap/src/main/resources/executor/pi && npm test
```

| 指标 | 值 |
| --- | --- |
| pass | **58** |
| fail | 0 |
| duration_ms | ~1256 |

### Java（定向套件）

```bash
./mvnw -pl rag,exec,engine,bootstrap -am \
  -Dtest=AgentToolPolicyServiceTest,PostgresAgentStageArtifactStoreTest,InMemoryAgentStageArtifactStoreTest,AgentManifestCanonicalJsonTest,RuntimeContextPolicyTest,RoleExecutionInputManifestTest,RuntimeContextPreflightValidatorTest,PiRequestV2MaterializerTest,PiAgentExecutorPropertiesTest,PiProtocolResourceTest,DockerPiAgentExecutorTest,EngineRequirementExecutionProfileResolverTest,MultiAgentOrchestrationSqlPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

| 模块 | Tests run | 结果 |
| --- | ---: | --- |
| rag | 10 | SUCCESS |
| engine | 4 | SUCCESS |
| exec | 22 | SUCCESS |
| bootstrap | 23 | SUCCESS |
| **合计** | **59** | **BUILD SUCCESS**（~5.1s） |

关键新增/相关：`PiRequestV2MaterializerTest` 6、`DockerPiAgentExecutorTest` 14、`RuntimeContextPreflightValidatorTest` 2、`PostgresAgentStageArtifactStoreTest` 6。
