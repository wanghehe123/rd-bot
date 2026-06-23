# Ragent RAG Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the RAG-only capabilities from ragent into RD-Bot using Java 21, Spring Boot 3, Maven modules, and virtual threads.

**Architecture:** RD-Bot is initialized as one Spring Boot service split into five Maven layers: `rag`, `engine`, `exec`, `skill`, and `bootstrap`. The `rag` layer owns parser/chunker/document ingestion, lightweight knowledge base/document/chunk management, intent classification, ambiguity guidance, retrieval channels, post-processing, and context packaging. The `engine` layer wires a complete repair-context flow and aggregates admin overview data. The `exec` and `skill` layers are placeholders for repair execution and reusable skills. Execution-layer task creation remains intentionally unimplemented. `knowledge` and `admin` are not separate modules or services.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven Wrapper, JUnit 5, in-memory mock vector store, deterministic text scoring, Java virtual threads.

---

### Task 1: Project Skeleton And Red Test

**Files:**
- Create: `pom.xml`
- Create: `rag/pom.xml`
- Create: `engine/pom.xml`
- Create: `exec/pom.xml`
- Create: `skill/pom.xml`
- Create: `bootstrap/pom.xml`
- Create: `rag/src/test/java/com/wish/rd/rag/RepairRagPipelineMockTest.java`

- [x] **Step 1: Write the failing mock end-to-end test**

The test must express the target flow: intent recognition, ambiguity guidance, intent-directed vector retrieval, keyword retrieval, adapter-port retrieval, deduplication, score sorting, and context packaging.

- [x] **Step 2: Run test to verify red**

Run: `./mvnw -pl rag test -Dtest=RepairRagPipelineMockTest`

Expected: FAIL because production classes are not implemented yet.

### Task 2: Core Framework And Adapter Ports

**Files:**
- Create: `rag/src/main/java/com/wish/rd/framework/convention/RetrievedChunk.java`
- Create: `rag/src/main/java/com/wish/rd/framework/trace/RagTraceNode.java`
- Create: `rag/src/main/java/com/wish/rd/adapter/TicketSystemPort.java`
- Create: `rag/src/main/java/com/wish/rd/adapter/LogCenterPort.java`
- Create: `rag/src/main/java/com/wish/rd/adapter/CodeRepositorySearchPort.java`
- Create: `rag/src/main/java/com/wish/rd/rag/pipeline/RepairTaskContextPort.java`

Status: updated. External system ports are currently housed inside the `rag` layer package to keep the service lightweight and avoid extra modules; repair task handoff stays in the RAG pipeline boundary until `exec` is designed.

### Task 3: RAG Domain, Ingestion, And Vector Store

**Files:**
- Create focused classes under `rag/src/main/java/com/wish/rd/rag/**`.
- Implement document parser, chunking, in-memory vector store, and deterministic text scoring.

Status: updated. `DocumentIngestionService` uses ragent-style parser/chunker abstractions, records parser/chunker/indexer node logs, and indexes `RetrievedChunk` records into `InMemoryVectorStore`.

### Task 4: Intent, Guidance, Retrieval, And Packaging

**Files:**
- Create focused classes under `rag/src/main/java/com/wish/rd/rag/**`.
- Implement intent tree classifier, ambiguity guidance, virtual-thread multi-channel retrieval, BM25-like keyword channel, adapter-port channels, deduplication, score sorting, and repair context package creation.

Status: complete for MVP. Implemented intent-directed vector search, global vector search fallback, keyword search, log center search, code repository search, deduplication, and context packaging. Retrieval channels execute through Java 21 virtual threads.

### Task 5: Verification

**Commands:**
- `./mvnw test`

Expected: PASS. The mock data test proves the migrated RAG flow works without real ticket system, log center, code repository, database, or MCP.

Status: complete. `RepairRagPipelineMockTest` covers clear intent retrieval, ambiguity prompting, global fallback, and log/code port participation with mock data. `RagFullFlowEngineTest` and `RagTestChannelControllerTest` cover the single-service full-flow test channel.

### Task 6: Embedded Knowledge And Admin MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/**`
- Create: `engine/src/main/java/com/wish/rd/engine/KnowledgeAdminEngine.java`
- Create: `engine/src/main/java/com/wish/rd/engine/KnowledgeAdminOverview.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/KnowledgeAdminController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `engine/src/test/java/com/wish/rd/engine/KnowledgeAdminFlowTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/KnowledgeAdminControllerTest.java`

- [x] **Step 1: Write failing tests**

Run: `./mvnw -pl engine,bootstrap -am -Dtest=KnowledgeAdminFlowTest,KnowledgeAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `com.wish.rd.rag.knowledge` and `KnowledgeAdminEngine` do not exist.

- [x] **Step 2: Implement the embedded knowledge workspace**

Implemented in-memory knowledge bases, documents, chunks, raw content preview, document search, document/chunk enabled flags, ingestion node logs, and vector-store indexing inside the `rag` layer.

- [x] **Step 3: Implement admin overview and REST endpoints**

Implemented admin counts in `engine` and Spring MVC endpoints in `bootstrap`, backed by shared singleton Spring beans in the same process.

- [x] **Step 4: Verify focused behavior**

Run: `./mvnw -pl engine,bootstrap -am -Dtest=KnowledgeAdminFlowTest,KnowledgeAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 7: Ragent-Style Ingestion Pipeline MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/PipelineDefinition.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/NodeConfig.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionStatus.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionTaskCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionTaskResult.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/TaskIngestionEngine.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/ingestion/DocumentIngestionService.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionNodeType.java`
- Create: `engine/src/main/java/com/wish/rd/engine/IngestionPipelineTestEngine.java`
- Create: `engine/src/main/java/com/wish/rd/engine/IngestionPipelineTestResult.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/IngestionTestChannelController.java`
- Test: `rag/src/test/java/com/wish/rd/rag/ingestion/TaskIngestionEngineTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/IngestionTestChannelControllerTest.java`

- [x] **Step 1: Write failing ingestion pipeline tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=TaskIngestionEngineTest,IngestionTestChannelControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `TaskIngestionEngine`, `PipelineDefinition`, `IngestionTaskCommand`, and the default pipeline test endpoint do not exist.

- [x] **Step 2: Implement Fetcher -> Parser -> Chunker -> Indexer chain**

Implemented default pipeline definition, node config, cycle validation, task command/result, task status, node logs, parser/chunker execution, vector-store indexing, and one-shot delegation from `DocumentIngestionService.write`.

- [x] **Step 3: Expose a test channel**

Implemented `POST /test/ingestion/default-pipeline`, returning task ID, pipeline ID, status, node types, chunk count, indexed chunk IDs, and message.

- [x] **Step 4: Add document ingestion logs endpoint**

Implemented `GET /knowledge-base/docs/{documentId}/chunk-logs`, backed by the node logs stored on `KnowledgeDocument`.

### Task 8: Query Rewrite And Prompt Plan MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/**`
- Create: `rag/src/main/java/com/wish/rd/rag/prompt/**`
- Create: `engine/src/main/java/com/wish/rd/engine/RagPromptFlowTestEngine.java`
- Create: `engine/src/main/java/com/wish/rd/engine/RagPromptFlowTestResult.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagPromptFlowController.java`
- Test: `rag/src/test/java/com/wish/rd/rag/prompt/RepairPromptServiceTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagPromptFlowControllerTest.java`

- [x] **Step 1: Write failing tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=RepairPromptServiceTest,RagPromptFlowControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `RuleBasedQueryRewriteService`, `RepairPromptService`, `RepairPromptPlan`, `PromptScene`, and `/test/rag/prompt-flow` do not exist.

- [x] **Step 2: Implement deterministic rewrite and split**

Implemented enabled query-term mappings with priority ordering, safe replacement, rule-based punctuation splitting, and `RewriteResult`.

- [x] **Step 3: Implement repair prompt planning**

Implemented prompt scene selection, system prompt construction, knowledge/runtime-log/code evidence sections, split-question section rendering, and evidence chunk IDs.

- [x] **Step 4: Expose prompt-flow test channel**

Implemented `POST /test/rag/prompt-flow`, which runs document ingestion, intent retrieval, mock log/code channels, query rewrite, and prompt-plan construction.

### Task 9: Trace-Lite Run And Node Records

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/trace/**`
- Modify: `engine/src/main/java/com/wish/rd/engine/RagPromptFlowTestEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/RagPromptFlowTestResult.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagPromptFlowController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagTraceController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/trace/RagTraceStoreTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagPromptFlowControllerTest.java`

- [x] **Step 1: Write failing trace tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=RagTraceStoreTest,RagPromptFlowControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the trace store and trace REST endpoints do not exist.

- [x] **Step 2: Implement in-memory trace store**

Implemented run start/finish records, ordered node records, run listing, detail lookup, node listing, and reset-on-same-trace-ID semantics for deterministic test channels.

- [x] **Step 3: Wire prompt-flow trace**

Implemented trace capture for document ingestion, repair context retrieval, query rewrite, and prompt planning. `POST /test/rag/prompt-flow` now returns `traceId`.

- [x] **Step 4: Expose trace REST endpoints**

Implemented `GET /rag/traces/runs`, `GET /rag/traces/runs/{traceId}`, and `GET /rag/traces/runs/{traceId}/nodes` in the same Spring Boot service.

### Task 10: Conversation Memory And Two-Turn Chat Flow

**Files:**
- Create: `rag/src/main/java/com/wish/rd/framework/convention/ChatMessage.java`
- Create: `rag/src/main/java/com/wish/rd/rag/memory/**`
- Modify: `rag/src/main/java/com/wish/rd/rag/rewrite/QueryRewriteService.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/prompt/RepairPromptService.java`
- Create: `engine/src/main/java/com/wish/rd/engine/RagChatFlowTestEngine.java`
- Create: `engine/src/main/java/com/wish/rd/engine/RagChatFlowTestResult.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagChatFlowController.java`
- Test: `rag/src/test/java/com/wish/rd/rag/memory/ConversationMemoryServiceTest.java`
- Test: `rag/src/test/java/com/wish/rd/rag/prompt/RepairPromptServiceTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagChatFlowControllerTest.java`

- [x] **Step 1: Write failing memory and chat-flow tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=ConversationMemoryServiceTest,RepairPromptServiceTest,RagChatFlowControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `ChatMessage`, conversation memory, prompt history support, and `/test/rag/chat-flow` do not exist.

- [x] **Step 2: Implement in-memory conversation memory**

Implemented ragent-style `load`, `append`, and `loadAndAppend` semantics with an in-memory store. Memory loading uses a Java virtual-thread executor. JDBC persistence and summary compression are intentionally not included in this single-service MVP slice.

- [x] **Step 3: Include conversation memory in prompt planning**

Implemented a history-aware `RepairPromptService.build(...)` overload. Existing callers remain compatible, while chat-flow can prepend a `对话记忆` prompt section before knowledge, logs, code, and split-question sections.

- [x] **Step 4: Expose a two-turn chat-flow test channel**

Implemented `POST /test/rag/chat-flow`, which runs first-turn ingestion/retrieval/prompt/answer, appends the assistant response, runs a second follow-up question, and verifies the prior user/assistant history is present in the second prompt.

### Task 11: Ragent-Style `/rag/v3/chat` Entry Point

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/RagV3ChatEngine.java`
- Create: `engine/src/main/java/com/wish/rd/engine/RagV3ChatStreamResult.java`
- Create: `engine/src/main/java/com/wish/rd/engine/RagV3StopResult.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagV3ChatController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagV3ChatControllerTest.java`

- [x] **Step 1: Write failing controller tests**

Run: `./mvnw -pl bootstrap -am -Dtest=RagV3ChatControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL with HTTP 404 because `/rag/v3/chat` and `/rag/v3/stop` do not exist.

- [x] **Step 2: Implement deterministic SSE wire-format chat**

Implemented `RagV3ChatEngine` to execute the same MVP repair flow: memory load/append, document ingestion, intent-guided retrieval, prompt planning, deterministic answer, and assistant memory append. The controller returns SSE-formatted `meta`, `delta`, and `done` events using `text/event-stream`.

- [x] **Step 3: Implement stop endpoint**

Implemented `POST /rag/v3/stop`, returning the requested task ID and `STOPPED` status. This preserves the public ragent route shape. Task state tracking is extended in Task 19.

- [x] **Step 4: Verify focused behavior**

Run: `./mvnw -pl bootstrap -am -Dtest=RagV3ChatControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 19: RagV3 Stream Task State And Rate-Limit Reject MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTask.java`
- Create: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/RagV3ChatEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagV3ChatController.java`
- Test: `rag/src/test/java/com/wish/rd/rag/runtime/RagStreamTaskRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagV3ChatRuntimeControllerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagV3ChatRateLimitControllerTest.java`

- [x] **Step 1: Write failing stream-task and rate-limit tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=RagStreamTaskRegistryTest,RagV3ChatRuntimeControllerTest,RagV3ChatRateLimitControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `RagStreamTaskRegistry`, task state models, task-state
endpoint, and rate-limit reject behavior do not exist.

- [x] **Step 2: Implement in-memory stream task registry**

Implemented `RUNNING`, `DONE`, `CANCELLED`, and `REJECTED` task states with
timestamps, conversation ID, message ID, title, and error message. This mirrors
ragent's task-manager semantics in a single process without Redis pub/sub.

- [x] **Step 3: Wire task state into `/rag/v3/chat` and `/rag/v3/stop`**

`/rag/v3/chat` now registers a task before running the deterministic RAG flow and
marks it `DONE` after appending the assistant message. `/rag/v3/stop` records
`CANCELLED` state in the registry while preserving the existing public
`STOPPED` response. `GET /rag/v3/tasks/{taskId}` exposes the task state as a
local test/debug channel.

- [x] **Step 4: Implement in-process global reject path**

When `rag.rate-limit.global.enabled=true` and
`rag.rate-limit.global.max-concurrent=0`, `/rag/v3/chat` writes the user turn and
assistant reject message into conversation memory, records the task as
`REJECTED`, and returns SSE `meta`, `reject`, `finish`, and `done` events.

- [x] **Step 5: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=RagStreamTaskRegistryTest,RagV3ChatRuntimeControllerTest,RagV3ChatRateLimitControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 12: Query-Term Mapping Admin MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/QueryTermMappingCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/ManagedQueryTermMapping.java`
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/QueryTermMappingRegistry.java`
- Create: `engine/src/main/java/com/wish/rd/engine/QueryTermMappingAdminEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/QueryTermMappingController.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/RagV3ChatEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/rewrite/QueryTermMappingRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/QueryTermMappingControllerTest.java`

- [x] **Step 1: Write failing registry and controller tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=QueryTermMappingRegistryTest,QueryTermMappingControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the in-memory registry and `/mappings` endpoints do not exist.

- [x] **Step 2: Implement an in-memory mapping registry**

Implemented create, update, get, list, delete, default rules, enabled filtering,
priority ordering, and a `rewriteService()` adapter so existing prompt planning
can consume the active mapping set.

- [x] **Step 3: Expose ragent-style mapping admin endpoints**

Implemented `GET /mappings`, `GET /mappings/{id}`, `POST /mappings`,
`PUT /mappings/{id}`, and `DELETE /mappings/{id}` inside the same Spring Boot
service. Missing mappings return HTTP 404 with a JSON `message`.

- [x] **Step 4: Connect mappings to `/rag/v3/chat`**

`RagV3ChatEngine` now reads query-term mappings from the shared registry instead
of a hard-coded list. The SSE `meta` event includes `rewrittenQuestion` so a
test or curl smoke can verify that an admin-created mapping is active.

- [x] **Step 5: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=QueryTermMappingRegistryTest,QueryTermMappingControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 13: Sample Question Welcome And Admin MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/sample/SampleQuestionCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/sample/ManagedSampleQuestion.java`
- Create: `rag/src/main/java/com/wish/rd/rag/sample/SampleQuestionPage.java`
- Create: `rag/src/main/java/com/wish/rd/rag/sample/SampleQuestionRegistry.java`
- Create: `engine/src/main/java/com/wish/rd/engine/SampleQuestionAdminEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/SampleQuestionController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/sample/SampleQuestionRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/SampleQuestionControllerTest.java`

- [x] **Step 1: Write failing registry and controller tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=SampleQuestionRegistryTest,SampleQuestionControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `SampleQuestionRegistry`, `SampleQuestionCommand`,
`SampleQuestionPage`, and the sample-question controller do not exist.

- [x] **Step 2: Implement in-memory sample-question management**

Implemented create, update, get, keyword page query, welcome-list limiting, and
delete semantics inside the `rag` layer. This keeps the ragent welcome/admin
surface available without introducing database or extra service dependencies.

- [x] **Step 3: Expose ragent-style sample-question endpoints**

Implemented `GET /rag/sample-questions`, `GET /sample-questions`,
`GET /sample-questions/{id}`, `POST /sample-questions`,
`PUT /sample-questions/{id}`, and `DELETE /sample-questions/{id}` in the same
Spring Boot process. Missing records return HTTP 404 with a JSON `message`.

- [x] **Step 4: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=SampleQuestionRegistryTest,SampleQuestionControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 14: Conversation Sidebar And Message History MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/memory/ManagedConversation.java`
- Create: `rag/src/main/java/com/wish/rd/rag/memory/ManagedConversationMessage.java`
- Create: `rag/src/main/java/com/wish/rd/rag/memory/ConversationRegistry.java`
- Create: `engine/src/main/java/com/wish/rd/engine/ConversationAdminEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/ConversationController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/memory/ConversationRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/ConversationControllerTest.java`

- [x] **Step 1: Write failing registry and controller tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=ConversationRegistryTest,ConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `ConversationRegistry`, `ManagedConversation`, the
conversation admin engine, and the conversation controller do not exist.

- [x] **Step 2: Implement a shared in-memory conversation registry**

Implemented a registry that satisfies the existing `ConversationMemoryStore`
contract while also storing conversation metadata and message view records. This
lets `/rag/v3/chat` and the conversation REST endpoints use the same in-memory
state.

- [x] **Step 3: Expose ragent-style conversation endpoints**

Implemented `GET /conversations`, `GET /conversations/{conversationId}/messages`,
`PUT /conversations/{conversationId}`, and `DELETE /conversations/{conversationId}`.
The MVP uses the local default user `test-user`, matching the deterministic
`/rag/v3/chat` flow.

- [x] **Step 4: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=ConversationRegistryTest,ConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 15: Intent Tree Admin And RagV3 Classifier Registry

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/intent/IntentNodeCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/intent/ManagedIntentNode.java`
- Create: `rag/src/main/java/com/wish/rd/rag/intent/IntentTreeRegistry.java`
- Create: `engine/src/main/java/com/wish/rd/engine/IntentTreeAdminEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/IntentTreeController.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/RagV3ChatEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/intent/IntentTreeRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/IntentTreeControllerTest.java`

- [x] **Step 1: Write failing registry and controller tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=IntentTreeRegistryTest,IntentTreeControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `IntentTreeRegistry`, managed intent-node commands, and
the `/intent-tree` endpoints do not exist.

- [x] **Step 2: Implement an in-memory intent-tree registry**

Implemented create, update, tree building, get, delete with descendants,
batch-enable, batch-disable, batch-delete, default payment-system seed data, and
an `intentTree()` adapter so the existing classifier can consume the active
admin-managed nodes.

- [x] **Step 3: Expose ragent-style intent-tree admin endpoints**

Implemented `GET /intent-tree/trees`, `POST /intent-tree`,
`PUT /intent-tree/{id}`, `DELETE /intent-tree/{id}`,
`POST /intent-tree/batch/enable`, `POST /intent-tree/batch/disable`, and
`POST /intent-tree/batch/delete` inside the same Spring Boot process. Missing
nodes return HTTP 404 with a JSON `message`.

- [x] **Step 4: Connect intent tree to `/rag/v3/chat`**

`RagV3ChatEngine` now builds its classifier from the shared
`IntentTreeRegistry` instead of a hard-coded tree. The SSE `meta` event includes
`intentSystemId` and `intentName`, letting the test channel prove that an
admin-created intent node is active.

- [x] **Step 5: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=IntentTreeRegistryTest,IntentTreeControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 16: Ingestion Pipeline And Task Admin MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionPipelineCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionPipelineNodeCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/ManagedIngestionPipeline.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/ManagedIngestionPipelineNode.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/ManagedIngestionTaskCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/ManagedIngestionTask.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/ManagedIngestionTaskNode.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionPipelinePage.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionTaskPage.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java`
- Create: `engine/src/main/java/com/wish/rd/engine/IngestionAdminEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/IngestionAdminController.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeWorkspace.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/ingestion/IngestionAdminRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/IngestionAdminControllerTest.java`

- [x] **Step 1: Write failing registry and controller tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=IngestionAdminRegistryTest,IngestionAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the ingestion admin registry, managed pipeline/task
models, engine, and controller do not exist.

- [x] **Step 2: Implement in-memory pipeline and task registry**

Implemented default pipeline seeding, pipeline create/update/get/page/delete,
task execution, task page/query, and node-level task log views. Pipeline
validation uses a temporary in-memory vector store so admin validation does not
pollute the runtime vector store.

- [x] **Step 3: Execute ingestion tasks into the shared knowledge workspace**

`KnowledgeWorkspace` now supports writing a document with a selected
`PipelineDefinition`. The ingestion task registry executes the selected
pipeline, then stores the resulting document, chunks, raw preview, and node logs
in the same workspace used by the knowledge REST endpoints.

- [x] **Step 4: Expose ragent-style ingestion endpoints**

Implemented `GET/POST/PUT/DELETE /ingestion/pipelines`,
`POST /ingestion/tasks`, `GET /ingestion/tasks`,
`GET /ingestion/tasks/{id}`, and `GET /ingestion/tasks/{id}/nodes` in the same
Spring Boot process. The MVP supports JSON inline text/Markdown sources.

- [x] **Step 5: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=IngestionAdminRegistryTest,IngestionAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 17: Multipart Upload To RustFS And Shared RagV3 Retrieval

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/ObjectStorageService.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/StoredIngestionFile.java`
- Create: `rag/src/main/java/com/wish/rd/rag/ingestion/InMemoryObjectStorageService.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/S3ObjectStorageService.java`
- Create: `docs/smoke/payment-upload-smoke.md`
- Modify: `pom.xml`
- Modify: `bootstrap/pom.xml`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/IngestionAdminController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/RagV3ChatEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagV3ChatController.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/IngestionAdminControllerTest.java`

- [x] **Step 1: Write failing multipart upload test**

Run: `./mvnw -pl bootstrap -am -Dtest=IngestionAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL with HTTP 405 because `/ingestion/tasks/upload` does not exist.

- [x] **Step 2: Implement RustFS-compatible object storage**

Added an object-storage port in `rag`, a memory implementation for tests, and a
RustFS/S3 implementation in `bootstrap` using AWS SDK S3. Runtime defaults match
ragent: `rustfs.url=http://localhost:9000`, access key and secret
`rustfsadmin`, and bucket `biz`.

- [x] **Step 3: Implement multipart upload task endpoint**

Implemented `POST /ingestion/tasks/upload`, which uploads the multipart file to
RustFS-compatible S3, records the `s3://bucket/key` source location in task
metadata, then runs the selected ingestion pipeline over the uploaded file
content.

- [x] **Step 4: Connect RagV3 chat to uploaded documents**

`RagV3ChatEngine` now reads from the shared `KnowledgeWorkspace.vectorStore()`.
It seeds a default payment document only when the workspace has no indexed
chunks, so uploaded or written documents can be retrieved by `/rag/v3/chat`.
The SSE response is now emitted with UTF-8 charset.

- [x] **Step 5: Verify focused and full behavior**

Run:
- `./mvnw -pl bootstrap -am -Dtest=IngestionAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `./mvnw test`

Expected: PASS. The focused test proves file upload, document preview, and
uploaded-document retrieval through `/rag/v3/chat`.

### Task 18: RAG Settings And Message Feedback MVP

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/feedback/MessageFeedbackCommand.java`
- Create: `rag/src/main/java/com/wish/rd/rag/feedback/MessageFeedback.java`
- Create: `rag/src/main/java/com/wish/rd/rag/feedback/MessageFeedbackRegistry.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/memory/ConversationRegistry.java`
- Create: `engine/src/main/java/com/wish/rd/engine/MessageFeedbackAdminEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/MessageFeedbackController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/RagSettingsController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/RdBotRuntimeConfiguration.java`
- Test: `rag/src/test/java/com/wish/rd/rag/feedback/MessageFeedbackRegistryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RagSettingsControllerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/MessageFeedbackControllerTest.java`

- [x] **Step 1: Write failing feedback/settings tests**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=MessageFeedbackRegistryTest,RagSettingsControllerTest,MessageFeedbackControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `MessageFeedbackRegistry`, feedback commands/models, and
the `/rag/settings` plus `/conversations/messages/{messageId}/feedback`
controllers do not exist.

- [x] **Step 2: Implement in-memory feedback upsert**

Implemented assistant-message-only feedback validation, vote validation
(`1` or `-1`), per-user/per-message upsert, and conversation message `vote`
projection. This mirrors ragent's feedback semantics while staying synchronous
and in-process for the single-service MVP.

- [x] **Step 3: Expose ragent-style feedback endpoint**

Implemented `POST /conversations/messages/{messageId}/feedback`. Missing
messages return HTTP 404, invalid votes return HTTP 400, and successful
submissions return the current feedback record.

- [x] **Step 4: Expose read-only RAG settings**

Implemented `GET /rag/settings`, returning upload, RAG default, query rewrite,
rate-limit, memory, provider, model, selection, and stream settings. Provider
API keys are masked before returning.

- [x] **Step 5: Verify focused behavior**

Run: `./mvnw -pl rag,bootstrap -am -Dtest=MessageFeedbackRegistryTest,RagSettingsControllerTest,MessageFeedbackControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 20: Knowledge Document And Chunk Lifecycle Admin MVP

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/vector/InMemoryVectorStore.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeChunk.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeDocument.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeWorkspace.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/KnowledgeAdminController.java`
- Modify: `engine/src/test/java/com/wish/rd/engine/KnowledgeAdminFlowTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/KnowledgeAdminControllerTest.java`

- [x] **Step 1: Write failing document and chunk lifecycle tests**

Run: `./mvnw -pl engine,bootstrap -am -Dtest=KnowledgeAdminFlowTest,KnowledgeAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because document detail/search/update/delete, manual chunk
CRUD, and batch chunk enablement are not implemented yet.

- [x] **Step 2: Implement lifecycle operations in the shared workspace**

Implemented document detail, global document search, document update, document
delete, manual chunk create/update/delete, batch chunk enablement, and vector
store replacement/removal. `KnowledgeWorkspace.writeDocument(...)` now removes
the ingestion engine's temporary `sourceName#index` vector IDs and indexes the
document-managed `doc-id-index` IDs, so admin counts and retrieval state remain
consistent.

- [x] **Step 3: Expose ragent-style REST routes**

Implemented `GET /knowledge-base/docs/search`, `GET/PUT/DELETE
/knowledge-base/docs/{documentId}`, `POST/PUT/DELETE
/knowledge-base/docs/{documentId}/chunks/{chunkId}`, `PATCH
/knowledge-base/docs/{documentId}/enable`, `PATCH
/knowledge-base/docs/{documentId}/chunks/{chunkId}/enable`, and `PATCH
/knowledge-base/docs/{documentId}/chunks/batch-enable` in the same Spring Boot
process. Existing `/enabled` compatibility routes remain available.

- [x] **Step 4: Verify focused behavior**

Run: `./mvnw -pl engine,bootstrap -am -Dtest=KnowledgeAdminFlowTest,KnowledgeAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 21: Knowledge Admin Frontend MVP

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeBase.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeWorkspace.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/KnowledgeAdminController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/AdminFrontendController.java`
- Create: `bootstrap/src/main/resources/static/admin/index.html`
- Create: `bootstrap/src/main/resources/static/admin/admin-knowledge.css`
- Create: `bootstrap/src/main/resources/static/admin/admin-knowledge.js`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/KnowledgeAdminControllerTest.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/AdminFrontendControllerTest.java`
- Create: `lark-output/knowledge-admin-desktop.png`
- Create: `lark-output/knowledge-admin-mobile.png`

- [x] **Step 1: Write failing frontend route and API compatibility tests**

Run: `./mvnw -pl bootstrap -am -Dtest=KnowledgeAdminControllerTest,AdminFrontendControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `GET /knowledge-base`, `GET/PUT/DELETE
/knowledge-base/{knowledgeBaseId}`, and `/admin/knowledge` frontend routes are
not implemented yet.

- [x] **Step 2: Implement knowledge-base compatibility endpoints**

Implemented knowledge-base page query, detail, rename, and delete endpoints.
Delete cascades through documents, chunks, raw previews, and vector entries so
the frontend can safely remove a knowledge base in the single-service MVP.

- [x] **Step 3: Implement the single-service static admin frontend**

Implemented a lightweight ragent-style knowledge admin UI served by Spring
Boot. It covers three routes: `/admin/knowledge`, `/admin/knowledge/{kbId}`,
and `/admin/knowledge/{kbId}/docs/{docId}`. The UI supports knowledge-base
create/search/rename/delete, document text write, RustFS upload entry, preview,
edit, enable/disable/delete, chunk create/edit/delete, and chunk batch
enable/disable.

- [x] **Step 4: Verify focused tests**

Run: `./mvnw -pl bootstrap -am -Dtest=KnowledgeAdminControllerTest,AdminFrontendControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [x] **Step 5: Verify browser smoke**

Run the Spring Boot service, then use Playwright with local Chrome to open the
three admin routes and execute create knowledge base -> write document ->
preview -> open chunks -> create chunk -> batch disable. Expected: no blank
page, no frontend error box, no top-level horizontal overflow, and no console
errors. Desktop and mobile screenshots are stored under `lark-output/`.
