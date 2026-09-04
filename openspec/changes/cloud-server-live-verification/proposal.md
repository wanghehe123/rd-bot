## Why

近期在云服务器 `106.55.13.166` 上完成了真实需求交付联调（Pi + CPA + GitHub publication），但连接方式、启动约定与「必须在云上用真实项目做真实需求验证」的门禁仍散落在对话与 `deploy/cloud-server/` 脚本中。后续 agent/开发者容易退回本机 mock、跳过真实交付，或误用本地 mirror rewrite 导致「BRANCH_CONFIRMED 但 GitHub 无分支」这类假阳性。现在需要把云服务器连接与真实验证合同写入 OpenSpec，成为可复验约束。

## What Changes

- 新增能力 `ops/cloud-server-live-verification`：记录云服务器 SSH 连接、工作目录、后端启动与本地隧道约定。
- 规定凡涉及需求交付、Pi 执行、credential-relay、publication 或 project memory capture 的验收，**必须**在云服务器上对**真实已登记项目**提交**真实 REQUIREMENT 任务**并跑完可观察阶段；本机单元/集成测试只能作为前置证据，不能单独宣告 live 通过。
- 明确禁止项：不得用 mock executor、空项目、伪造 stage SUCCEEDED、或仅 curl health 代替真实四角色/发布验收。
- 同步更新 `deploy/cloud-server/README.md` 与（如需要）`RULE.md` 的可验证引用，使文档与 OpenSpec 一致。
- 本 change **不修改** 交付引擎运行时行为；它是运维/验收合同建档，外加文档对齐任务。

## Capabilities

### New Capabilities

- `ops/cloud-server-live-verification`: 云服务器连接方式，以及在该环境用真实项目做真实需求验证的强制验收合同。

### Modified Capabilities

- （无）本轮不修改 `requirement/delivery-platform` 等现有主能力的需求条文；交叉引用放在 design/tasks 与文档同步中。

## Impact

- OpenSpec：新增 `openspec/changes/cloud-server-live-verification/` 与未来归档后的 `openspec/specs/ops/cloud-server-live-verification/spec.md`。
- 文档：`deploy/cloud-server/README.md`；可选追加 `RULE.md` 中与 live 验收相关的强制条目。
- 运行时：无代码行为变更。
- 运维依赖：`ubuntu@106.55.13.166`、`~/RD-Bot`、`application-local.yaml`、`deploy/cloud-server/start-backend.sh`、CPA SSH tunnel、真实 GitHub PAT/provider 凭据（仅环境变量，不入库）。
