# P3.0 协议冲突与风险接受记录（2026-08-01）

## 状态

`ACCEPTED_FOR_OFFLINE_PREP` — 允许开始 P3 硬门与 V0–V2 离线准备；**不**启用生产 `request v2/root-only` 灰度，**不**扩大 `dynamicStateEnabled` / `FACTS_V1`。

## 触发依据

维护者基于 canary `7489272835593080832` 的审计结论批准开工顺序：

1. 两项硬门（QA `default-qa@2`、Input Manifest 原子不可变写入）
2. P3-V0/V1（无模型）
3. P3-V2 Docker 离线 preflight
4. 仅 V0–V2 全绿后再考虑付费 Pi sentinel

对应方案：`docs/superpowers/specs/2026-08-01-role-context-optimization-validation-and-improvement-plan.markdown` §Phase 3。

## 替代审查说明（model-escalation）

本阶段属于协议/并发一致性高风险变更。仓库要求 `model-escalation` 或维护者书面风险接受。

- **替代审查人**：仓库维护者（本会话用户）
- **证据**：canary-d evidence-summary；硬门清单见下
- **风险接受范围**：仅离线准备与契约测试；不启用 v2 生产路由；Claude 保持 UNAVAILABLE
- **明确不接受**：同 attempt 回写旧 snapshot；新镜像中悄悄恢复隐式嵌套发现；调大 raw event 16 MiB 上限

## 冲突裁决落地

### 冲突一：旧 D17 vs 新 D4

- 旧 D17（2026-07-26）：允许 Pi 隐式发现任意层级 `AGENTS.md/CLAUDE.md`
- 新 D4（2026-08-01）：repo root-only，嵌套仅精确 allowlist
- **裁决**：以 D4 为当前真值。在旧设计文档 D17 后追加 superseded 指针；v1 历史可读，新 attempt 目标为 `rd-pi-request/v2`；新镜像默认拒绝 v1。

### 冲突二：Input vs Runtime Manifest

- Input Manifest 在 dispatch 前冻结；Runtime Manifest 在 loader 后生成
- Input 只含 expected policy/hash；Runtime 回引 `inputManifestHash` + `policyHash`
- **saveImmutable** 必须改为原子 insert / conflict 后读比对，禁止 `ON CONFLICT DO UPDATE` 覆盖不可变产物

## 硬门完成定义

1. **QA 策略**：新增 `default-qa@2`（无 edit/write）；`pi-qa-nextjs-kbr` 绑定 v2 并升 profile version；无模型快照验证 `effectiveAllow` 不含 edit/write；不改写旧 snapshot。
2. **不可变写入**：Postgres `saveImmutable` 并发下先写者胜出，后写者若 hash 冲突则失败，若相同则返回已有行；补并发测试。

## 明确表述边界

“QA 冻结”在本硬门仅指 **禁止 edit/write 工具**。`bash` 仍可写文件；完整仓库写守卫是后续决策，不在本轮硬门范围。

## 验证命令（完成后）

```bash
# QA policy unit + resolver
./mvnw -pl rag,bootstrap -am -Dtest=AgentToolPolicyServiceTest,EngineRequirementExecutionProfileResolverTest -Dsurefire.failIfNoSpecifiedTests=false test

# Immutable store
./mvnw -pl bootstrap -am -Dtest=PostgresAgentStageArtifactStoreTest -Dsurefire.failIfNoSpecifiedTests=false test

# Pi V0/V1 (after schemas/loader land)
cd bootstrap/src/main/resources/executor/pi && npm test
```
