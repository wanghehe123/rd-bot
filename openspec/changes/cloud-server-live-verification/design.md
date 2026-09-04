## Context

动机见 `proposal.md`。当前云端联调事实来自仓库内 `deploy/cloud-server/` 与 2026-09 在 `106.55.13.166` 上的真实需求跑通（含 CPA tunnel、Pi 资源限制、GitHub PAT publication）。`deploy/cloud-server/README.md` 仍偏「本机 spring-boot:run + 示例 yaml」，与现用 `start-backend.sh` + `application-local.server.yaml` → 工作目录 `application-local.yaml` 不完全一致。本 design 只固化连接与验收方法，不改交付引擎代码。

历史资料：对话与临时产物（如 `/tmp/codex-memory-run/`）属于运维证据，不是主 spec 真值；归档后以本 capability 与更新后的 `deploy/cloud-server/README.md` 为准。

## Goals / Non-Goals

**Goals:**

- 把 SSH、工作目录、启动脚本、隧道、overlay 路径写成可执行合同。
- 把「云上 + 真实项目 + 真实需求」定为 live 门禁，并给出最小证据集。
- 对齐 `deploy/cloud-server/README.md`（及可选 `RULE.md` 引用）与 OpenSpec。

**Non-Goals:**

- 不修复 project memory capture / reconcile / worker 缺陷（另 change）。
- 不改 `RequirementDeliveryEngine`、Pi bridge 或 publication 协议。
- 不把具体 `projectId`/`taskId`/PAT 写进主 spec。
- 不要求公网暴露 8080；继续 localhost + SSH 转发。

## Decisions

1. **新建能力路径 `ops/cloud-server-live-verification`**
   - 原因：这是运维/验收合同，不是 delivery-platform 业务行为变更；独立能力避免污染需求交付主 spec。
   - 备选：挂到 `requirement/delivery-platform` → 拒绝，以免把部署主机写进产品行为条文。

2. **规范主机与入口，不规范密钥值**
   - 主机 `106.55.13.166`、用户 `ubuntu`、目录 `~/RD-Bot`、脚本 `deploy/cloud-server/start-backend.sh` / `start-cpa-tunnel.sh` 写入 spec。
   - 密钥只描述「环境变量 / 未跟踪文件」；示例 yaml 不得含真实 secret。

3. **运行时 overlay 以 JVM cwd 为准**
   - jar 启动时读取 `~/RD-Bot/application-local.yaml`；`start-backend.sh` 从 `application-local.server.yaml` 复制。
   - 备选：只改 `src/main/resources` → 已在 live 中导致 sidecar 默认 `18080` 与 502；明确禁止该误解。

4. **Live 证据三要素**
   - 执行面：云服务器进程与其 DB/Docker。
   - 身份：真实已登记项目。
   - 行为：真实 REQUIREMENT 任务 + 可观察阶段/PR 或精确失败。
   - 本机单测与 real smoke（如 `rdbot_acceptance`）仍可作为前置，但标签必须区分。

5. **参考项目可写在 design/tasks，不写进 SHALL**
   - 例如项目「codex运行测试」曾用于联调；spec 只要求「真实已登记项目」，避免主机换项目后合同失效。

## Risks / Trade-offs

- [主机 IP/账户变更] → 变更时发新 OpenSpec delta 同步更新 README；旧证据标注主机版本。
- [把运维步骤写进产品 spec 显得过宽] → 限定 `ops/` 域；delivery 主能力不引用具体 IP。
- [操作者仍用 mock 却勾选完成] → tasks 要求证据字段清单；缺少 PR/失败锚点不得勾选。
- [CPA 隧道或配额导致失败] → 失败可记为「live 已执行但未通过」；不得改库伪装成功。

## Migration Plan

1. 合并本 change 规划产物。
2. Apply：更新 `deploy/cloud-server/README.md` 与可选 `RULE.md` 条目，使命令与 spec 一致。
3. Archive 后主 spec 出现在 `openspec/specs/ops/cloud-server-live-verification/spec.md`。
4. 回滚：删除/归档回退该 capability 文档即可，无运行时迁移。

## Open Questions

- 是否将「四角色全部 SUCCEEDED + PR」定为唯一成功标准，还是允许「精确失败到已知阶段」作为负面 live 证据：当前 spec 允许后者写入证据，但不勾选「交付成功」类任务。
