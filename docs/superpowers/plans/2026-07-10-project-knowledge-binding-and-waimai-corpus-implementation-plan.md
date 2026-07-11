# Project Knowledge Binding and Waimai Corpus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 为项目增加可持久化的单知识库绑定，让 Bug RAG 使用该范围，并将 Waimai 仓库的白名单源码资料幂等写入现有知识库。

**Architecture:** `rag` 承载项目绑定领域字段与检索范围合并规则，`bootstrap` 负责 PostgreSQL/REST 适配，`frontend` 负责项目表展示和绑定弹窗。仓库资料由可重放脚本通过既有知识库写入 HTTP API 导入，不引入新的生产外部 SDK。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、PostgreSQL、JUnit 5、React 18、TypeScript、Vite、Bash/Python 标准库。

---

## 文件结构

- Modify: `rag/src/main/java/com/wish/rd/rag/project/model/RdProject.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/project/model/RdProjectCommand.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/project/RdProjectService.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/pipeline/model/RepairRagRequest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/retrieval/model/RetrievalRequest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/pipeline/RepairRagPipeline.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/retrieval/impl/GlobalVectorSearchChannel.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/retrieval/impl/IntentDirectedVectorSearchChannel.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdProjectRow.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdProjectStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/project/RdProjectController.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- Modify: `engine/src/main/java/com/wish/rd/engine/rag/RagBugFixEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`
- Modify: `frontend/src/services/projectService.ts`
- Modify: `frontend/src/pages/admin/project/ProjectListPage.tsx`
- Create: `frontend/src/pages/admin/project/projectKnowledgeBinding.ts`
- Create: `scripts/seed-waimai-repository-knowledge.sh`
- Create/modify focused JUnit, Node, schema-policy and QA-report tests.

### Task 1: Persist and validate the optional project binding

- [x] Write failing `RdProjectServiceTest` cases for an existing enabled knowledge base, a missing knowledge base, a disabled knowledge base, and clearing an existing binding.
- [x] Run `./mvnw -q -pl rag -Dtest=RdProjectServiceTest test` and confirm RED because the project command has no knowledge-base field or validation.
- [x] Add `knowledgeBaseId` to the project record/command, retain overloaded old constructors for existing callers, and validate nonblank IDs with `KnowledgeBaseStore.findById` plus `enabled`.
- [x] Add `knowledge_base_id` to `RdProjectRow` and `PostgresRdProjectStore` conversions.
- [x] Add idempotent column/index/foreign-key migration using `ON DELETE SET NULL`.
- [x] Run the focused rag test and `./mvnw -q -pl bootstrap -am -Dtest=RdProjectPostgresSchemaPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test` to confirm GREEN.

### Task 2: Expose binding through the project API

- [x] Extend `RdProjectControllerTest` first: create/update/get/list must round-trip `knowledgeBaseId`; unknown or disabled IDs must produce HTTP 400.
- [x] Run `./mvnw -q -pl bootstrap -am -Dtest=RdProjectControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm RED.
- [x] Extend controller request/view records and the command mapper; update test setup with a deterministic `KnowledgeBaseStore` fake.
- [x] Re-run the focused controller test and confirm GREEN.

### Task 3: Apply the binding to Bug RAG scope

- [x] Write a failing `RepairRagPipelineMockTest` proving an unmatched intent plus project KB returns only chunks from that KB, while an empty project binding retains global fallback.
- [x] Run `./mvnw -q -pl rag -Dtest=RepairRagPipelineMockTest test` and confirm RED.
- [x] Add optional project IDs to `RepairRagRequest` and `RetrievalRequest`; union them before intent IDs, update direct/global channel enablement, and keep three-argument constructors for old callers.
- [x] Add an overload in `RagBugFixEngine` and resolve the binding from `RdBotFixEngine` using the task project ID; callers without a project pass an empty list.
- [x] Run `./mvnw -q -pl rag,engine test` and confirm GREEN.

### Task 4: Add project-management binding controls

- [x] Write a failing Node test for binding normalization: only known enabled knowledge base IDs are accepted, and an empty selection clears the value.
- [x] Run `node --experimental-strip-types --test test/projectKnowledgeBinding.test.ts` from `frontend` and confirm RED.
- [x] Add the optional field/service payload, knowledge-base directory load, a knowledge-base column, and a database-icon dialog with an explicit clear option, Tooltip, accessible label and visible save feedback.
- [x] Run the Node test, `npm run typecheck`, and `npm run build` to confirm GREEN; sync static resources to `bootstrap/target/classes`.

### Task 5: Seed the Waimai repository corpus

- [x] Add a source-file manifest to `scripts/seed-waimai-repository-knowledge.sh`, fixed to the supplied GitHub repository and a resolved `main` SHA.
- [x] Implement source headers, common-secret redaction, deletion of only previous `waimai-repo-` documents, and writes through `/knowledge-base/{kbId}/docs/write`.
- [x] Verify the script syntax with `bash -n scripts/seed-waimai-repository-knowledge.sh`.
- [x] Read the existing Waimai project and knowledge-base IDs through the API, bind them with `PUT /admin/projects/{id}`, then run the script with explicit `KB_ID` and resolved SHA. The implementation must not hard-code local IDs.
- [x] Re-run once to prove idempotency, then record counts, source names, response codes and an RAG retrieval in `docs/qa/rd-project-knowledge-binding-acceptance-2026-07-10.md`.

### Task 6: Finish verification

- [x] Run `git diff --check`.
- [x] Run focused module tests plus `./mvnw -q -pl rag,engine,bootstrap test` where dependency setup permits.
- [x] Run `cd frontend && npm run typecheck && npm run build`.
- [x] Use real HTTP `GET -> PUT -> GET`, PostgreSQL query, and browser checks for the Waimai binding; document any external/provider limit explicitly.

No commit or push is included because the repository instruction requires explicit user authorization.
