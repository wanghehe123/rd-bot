# 2026-08-12 ragent Docker PostgreSQL 启动与迁移验收规范

## 问题

- 症状：后端以 PostgreSQL 模式启动时，若使用默认 `rdbot` 数据库会直接失败；切换到本机 Docker 的 `ragent` 后，旧 schema 会在真实任务路径中报“列/表不存在”。
- 用户影响：应用可能监听 `18080`，但任务创建后的异步派发或 retry 持久化仍会失败，不能把“Spring 已启动”当作可用。
- 本次已验证：Docker 容器 `postgres`（`pgvector/pgvector:pg16`）内的数据库为 `ragent`。完整 migration 后，缺失的 `rd_tasks.host_assertion_bundle_json`、`rd_requirement_stage_commands`、retry checkpoint/binding 对象均已存在，相关 retry 约束均为 validated。

## 已验证链路

1. 本机配置：被 Git 忽略的 `bootstrap/src/main/resources/application-local.yaml` 指向 `jdbc:postgresql://127.0.0.1:5432/ragent`。
2. 启动边界：从根目录使用 `./mvnw -f bootstrap/pom.xml spring-boot:run` 时，Maven 会以 `bootstrap/` 为运行目录；因此 `SPRING_CONFIG_ADDITIONAL_LOCATION` 必须使用**绝对路径**，否则相对路径失效并回退至 `application.yaml` 的默认 `rdbot`。
3. 数据库边界：本机没有 `psql` 客户端时，使用 `docker exec -i postgres psql -U postgres -d ragent` 从 Docker 容器执行仓库的 SQL 文件。
4. 任务入口：`POST /admin/rd-tasks/requirements` 由 `RdTaskController` 创建任务，`POST /admin/rd-tasks/{taskId}/submit` 进入 `RequirementDeliveryDispatchService`。
5. continuation 持久化：`RequirementDeliveryDispatchService -> PostgresRequirementStageFinalizationAdapter.finalize -> RequirementStageCommandMapper.enqueue`。因此 schema migration 后仍必须提交一条真实任务，不能只检查建表成功。

## 操作规则

- 必须确认目标库是 `ragent`，且容器名是 `postgres`；不得在未确认目标库时执行 DDL。
- 必须按 `bootstrap/src/main/resources/sql/postgres/` 的数字顺序执行全部 `pN_*.sql`；`p8_` 的两个文件都要执行，`p10` 在 `p9` 之后。
- 迁移完成后必须重跑 `p1_multi_agent_orchestration.sql` 与 `p3_task_retry_ai_review.sql` 一次，验证其幂等性。
- 不得把“Tomcat 已监听”或“DDL 无报错”作为通过条件；至少创建并提交一条真实需求任务，检查任务详情、时间线、stage command 与后端日志。
- 配置文件与 spec 不得记录数据库密码；只允许记录数据库名、容器名、环境变量名和命令结构。

## Docker 操作流程

### 1. 只读预检

```bash
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
docker exec postgres psql -U postgres -d ragent -v ON_ERROR_STOP=1 -Atc \
  "SELECT current_database();
   SELECT 'host_assertion_bundle_json=' || EXISTS (
     SELECT 1 FROM information_schema.columns
      WHERE table_schema='public' AND table_name='rd_tasks'
        AND column_name='host_assertion_bundle_json'
   );
   SELECT 'stage_commands=' || COALESCE(
     to_regclass('public.rd_requirement_stage_commands')::text, 'missing'
   );"
```

预期：容器为 `postgres`，数据库为 `ragent`；迁移前允许列/表显示缺失，但必须先停在这里确认目标无误。

### 2. 在 Docker 内按仓库顺序迁移

从仓库根目录执行。此命令修改 `ragent` schema；执行前须获得数据库写入授权。

```bash
for sql in \
  bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql \
  bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql \
  bootstrap/src/main/resources/sql/postgres/p2_rag_retrieval_state.sql \
  bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql \
  bootstrap/src/main/resources/sql/postgres/p4_web_evaluation_console.sql \
  bootstrap/src/main/resources/sql/postgres/p5_qa_evidence.sql \
  bootstrap/src/main/resources/sql/postgres/p6_evaluation_data_quality.sql \
  bootstrap/src/main/resources/sql/postgres/p7_project_runtime_profiles.sql \
  bootstrap/src/main/resources/sql/postgres/p8_pi_agent_runtime.sql \
  bootstrap/src/main/resources/sql/postgres/p8_zz_default_qa_v2.sql \
  bootstrap/src/main/resources/sql/postgres/p9_default_qa_v2_gate.sql \
  bootstrap/src/main/resources/sql/postgres/p10_skill_hub.sql; do
  docker exec -i postgres psql -U postgres -d ragent -v ON_ERROR_STOP=1 < "$sql"
done

for sql in \
  bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql \
  bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql; do
  docker exec -i postgres psql -U postgres -d ragent -v ON_ERROR_STOP=1 < "$sql" > /dev/null
done
```

### 3. Schema 验收

```bash
docker exec postgres psql -U postgres -d ragent -v ON_ERROR_STOP=1 -Atc \
  "SELECT 'host_assertion_bundle_json=' || EXISTS (
     SELECT 1 FROM information_schema.columns
      WHERE table_schema='public' AND table_name='rd_tasks'
        AND column_name='host_assertion_bundle_json'
   );
   SELECT 'stage_commands=' || COALESCE(to_regclass('public.rd_requirement_stage_commands')::text, 'missing');
   SELECT 'retry_checkpoints=' || COALESCE(to_regclass('public.rd_task_retry_checkpoints')::text, 'missing');
   SELECT 'retry_bindings=' || COALESCE(to_regclass('public.rd_task_retry_attempt_bindings')::text, 'missing');
   SELECT 'unvalidated_retry_constraints=' || COUNT(*)
     FROM pg_constraint
    WHERE conrelid IN ('rd_task_retry_checkpoints'::regclass,
                       'rd_requirement_stage_commands'::regclass,
                       'rd_task_retry_attempt_bindings'::regclass)
      AND NOT convalidated;"
```

预期：列值为 `true`，三张表均返回名称，`unvalidated_retry_constraints=0`。

### 4. 使用绝对 local 配置启动后端

```bash
SPRING_PROFILES_ACTIVE=local \
SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/absolute/path/to/RD-Bot/bootstrap/src/main/resources/application-local.yaml \
./mvnw -q -f bootstrap/pom.xml spring-boot:run
```

预期：日志含 `Tomcat started on port 18080` 和 `Started RdBotApplication`。停机时向该进程发送 `SIGINT`，等待 Hikari 正常 shutdown；不要用强杀作为常规停机方式。

## 真实任务验收

1. `POST /admin/rd-tasks/requirements` 创建一个含材料、仓库 URL、owner、repo、`baseBranch` 的需求任务；创建请求不得设置 `autoExecute=true`，便于先记录任务 ID。
2. `GET /admin/rd-tasks/{taskId}` 与 `GET /admin/rd-tasks/{taskId}/timeline` 确认创建事件已持久化。
3. 仅提交该受控任务：`POST /admin/rd-tasks/{taskId}/submit`。
4. 核对任务、`rd_requirement_delivery_jobs`、`rd_requirement_stage_commands` 和后端日志。正常路径至少应产生持久化的 stage command 与推进后的任务/事件，不得只停在 `CREATED`。

## 本次发现的应用问题（未修改）

迁移后，真实任务 `7493218297043881984` 创建成功，但提交后停留在 `CREATED`。日志中的直接失败为：

```text
ERROR: null value in column "business_generation" of relation
"rd_requirement_stage_commands" violates not-null constraint
```

已追踪到 `PostgresRequirementStageFinalizationAdapter` 的私有 `toRow(RequirementStageCommand)`：它在 continuation 入库前没有映射 `retryCheckpointId`、`businessGeneration`、`targetRetryBindingId`。同类正确映射已存在于 `PostgresRequirementStageCommandStore.toRow`。此外该 finalization adapter 的 `toCommand` 仍使用旧的兼容构造器，未从 row 回填这三个 retry 字段。

因此普通 continuation 即使是 normal command（generation 应为 `0`），也向 `RequirementStageCommandMapper.enqueue` 绑定 `NULL`；迁移后的数据库拒绝该行。该异常发生在已领取的首个 `MATERIAL_COLLECTING` command 完成后、`MATERIAL_READY` continuation 入库时，本次任务留下 `CREATED` task、`RUNNING` delivery job 与 `RUNNING` 首 command。

## 后续修复验收（待授权）

- [ ] 在 finalization adapter 的 row 双向映射中完整传递上述三个字段；normal command 映射 `businessGeneration=0`，retry command 保留 checkpoint/generation/binding identity。
- [ ] 增加 PostgreSQL finalization regression：成功的 normal continuation 必须写入 `business_generation=0`，retry continuation 必须保留 checkpoint 绑定。
- [ ] 使用新建的受控真实任务再次执行 create + submit，检查任务不再卡在 `CREATED`，并检查时间线与 command 状态。
- [ ] 保持本 spec 中的 Docker schema 验收命令通过。

## 故障速查

| 症状 | 已验证原因 | 处理方式 |
| --- | --- | --- |
| `database \"rdbot\" does not exist` | relative local config 在 Maven `bootstrap/` 工作目录失效，回退到了默认值 | 使用绝对 `SPRING_CONFIG_ADDITIONAL_LOCATION` |
| `host_assertion_bundle_json does not exist` | `ragent` 未执行当前 p0 migration | 在 Docker 中执行完整 p0–p10 序列 |
| `rd_requirement_stage_commands does not exist` | `ragent` 未执行当前 p1 migration | 在 Docker 中执行完整 p0–p10 序列 |
| `business_generation ... violates not-null` | finalization adapter 的 continuation row mapping 漏传 retry 字段 | 不要改数据库约束；修正映射并补回归测试后复测 |
