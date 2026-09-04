## 1. 文档与合同对齐

- [ ] 1.1 重写 `deploy/cloud-server/README.md`：写入 SSH（`ubuntu@106.55.13.166`）、`~/RD-Bot`、本机 `-L 8080:127.0.0.1:8080` 隧道、`deploy/cloud-server/start-backend.sh`、`start-cpa-tunnel.sh`、工作目录 `application-local.yaml` overlay、日志 `/tmp/rd-bot-backend.log`；删除或降级与现用不一致的「仅 spring-boot:run + 只改 src/main/resources」表述；验证：`rg -n '106\.55\.13\.166|start-backend\.sh|application-local\.server\.yaml' deploy/cloud-server/README.md` 均有命中且无密钥字面量。
- [ ] 1.2 在 `RULE.md` 追加一条【强制】live 验收引用：指向 `openspec/changes/cloud-server-live-verification/specs/ops/cloud-server-live-verification/spec.md`（归档后改为 `openspec/specs/ops/cloud-server-live-verification/spec.md`），写明「云服务器 + 真实项目 + 真实 REQUIREMENT」；验证：`rg -n 'cloud-server-live-verification|106\\.55\\.13\\.166' RULE.md`。
- [ ] 1.3 确认 `deploy/cloud-server/application-local.example.yaml` 与 `application-local.server.yaml` 头部注释指向同一连接约定，且不含真实密钥；验证：`rg -n 'GH_TOKEN|CPA_API_KEY|sk-|github_pat' deploy/cloud-server/` 无真实值命中。

## 2. OpenSpec 校验与证据模板

- [ ] 2.1 运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --change cloud-server-live-verification --strict` 与 `OPENSPEC_NO_UPDATE_CHECK=1 openspec status --change cloud-server-live-verification`，确认 proposal/specs/design/tasks 齐全。
- [ ] 2.2 在 change 下新增 `verification-evidence.md` 模板：字段含主机、项目 ID/名称、任务 ID、provider/model、各角色终态、PR URL 或失败日志路径、是否使用 mock（必须为否）；验证：文件存在且含上述字段标题。

## 3. 云上真实需求验收（apply 后必跑，本机单测不算）

- [ ] 3.1 在 `106.55.13.166` 确认后端按 `start-backend.sh` 监听 `127.0.0.1:8080`，CPA 隧道（若策略需要）可达；验证：`curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health` 为 2xx（在云主机或经 SSH 隧道执行）。
- [ ] 3.2 选择云库中真实已登记项目（不得用 example 占位仓库），经 `POST /admin/rd-tasks/requirements`（或管理台等价路径）创建并启动一条新的真实 REQUIREMENT 任务；记录 projectId/taskId。
- [ ] 3.3 等待流水线产生可观察结果：成功则记录 GitHub PR URL 与关键变更；失败则记录精确失败阶段与 `/tmp/rd-bot-backend.log` 锚点；禁止 mock executor 与手工改库标成功。
- [ ] 3.4 将 3.1–3.3 证据填入 `verification-evidence.md`；缺任一最小字段不得勾选本节完成。

## 4. 收尾

- [ ] 4.1 实现与文档一致后运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`；仅在用户明确要求时再 archive 本 change。
