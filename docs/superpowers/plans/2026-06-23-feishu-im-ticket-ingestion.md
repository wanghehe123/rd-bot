# Feishu IM Ticket Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Feishu IM bot ticket provider so personal developer accounts can trigger RD-Bot repair work without Feishu Helpdesk.

**Architecture:** Keep `bootstrap -> engine -> rag`. Feishu IM HTTP parsing, ticket storage, and IM send-message calls live in `bootstrap`; the existing `TicketProviderPort`, `TicketUpdatePort`, `TicketEventIngestionEngine`, RocketMQ adapter, RAG engine, and GitHub PR adapter remain the downstream path.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, JUnit 5, Jackson, JDK `HttpClient`, existing RD-Bot ticket and queue ports.

---

## File Structure

- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImProperties.java`
  - Configuration holder for `rd.feishu.im.*`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketDraft.java`
  - Parsed ticket value object used only by the IM adapter package.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketParser.java`
  - Parses line-based IM text into a normalized draft.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketStore.java`
  - Synchronized local ticket/message store implementing registry-style behavior.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImClient.java`
  - Low-level tenant token and `POST /open-apis/im/v1/messages` client.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketAdapter.java`
  - Implements `TicketProviderPort` and `TicketUpdatePort` from the local store and IM client.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImMessageController.java`
  - Receives Feishu IM event callbacks, creates local tickets, and publishes `TicketEventInput`.
- Modify `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/MockFeishuTicketAdapter.java`
  - Make mock selection depend on `rd.repair.ticket.provider=mock` and external providers being disabled.
- Modify `bootstrap/src/main/resources/application.yaml`
  - Add `rd.repair.ticket.provider`, `rd.ticket.write-back.enabled`, and `rd.feishu.im.*`.
- Modify `engine/src/main/java/com/wish/rd/engine/ticket/TicketEventInput.java`
  - Add `TYPE_FEISHU_IM_MESSAGE_CREATED`.
- Modify `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairEngine.java`
  - Use generic write-back property with Helpdesk fallback.
- Test files:
  - `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketParserTest.java`
  - `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketStoreTest.java`
  - `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImMessageControllerTest.java`
  - `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketAdapterTest.java`
  - Existing `engine/src/test/java/com/wish/rd/engine/TicketEventIngestionEngineTest.java` for the new event constant if needed.

### Task 1: Feishu IM Parser

**Files:**
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketParserTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketDraft.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketParser.java`

- [ ] **Step 1: Write failing parser tests**

Add tests that expect:

```java
FeishuImTicketDraft draft = parser.parse("""
        系统: waimai
        仓库: github.com/example/waimai
        分支: main
        优先级: P1
        问题: 下单接口 500
        日志: NullPointerException at OrderService.create
        期望: 下单成功
        实际: 返回 500
        """);

assertEquals("P1", draft.priority());
assertEquals("waimai", draft.customFields().get("problemSystem"));
assertEquals("github.com/example/waimai", draft.customFields().get("repository"));
assertEquals("下单接口 500", draft.title());
assertTrue(draft.description().contains("下单接口 500"));
```

Also test natural-language fallback:

```java
FeishuImTicketDraft draft = parser.parse("waimai 下单接口 500，日志 NPE");

assertEquals("P2", draft.priority());
assertEquals("waimai 下单接口 500，日志 NPE", draft.title());
assertEquals("waimai 下单接口 500，日志 NPE", draft.description());
assertTrue(draft.customFields().isEmpty());
```

- [ ] **Step 2: Run RED**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuImTicketParserTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because parser classes do not exist.

- [ ] **Step 3: Implement parser**

Implement `FeishuImTicketDraft` as a package record with null normalization and `FeishuImTicketParser` as a component-free package class with `parse(String text)`.

- [ ] **Step 4: Run GREEN**

Run the same command. Expected: `FeishuImTicketParserTest` passes.

### Task 2: Local Ticket Store

**Files:**
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketStoreTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketStore.java`

- [ ] **Step 1: Write failing store tests**

Tests should create a store, register a parsed ticket with `ticketId`, `chatId`, `senderOpenId`, `messageId`, and `createdAt`, then assert:

```java
assertTrue(store.findTicket(ticketId).isPresent());
assertEquals("feishu-im", store.findTicket(ticketId).orElseThrow().source());
assertEquals("oc-chat", store.findTicket(ticketId).orElseThrow().chatId());
assertEquals(1, store.findMessages(ticketId, TicketMessageQuery.defaults()).messages().size());
```

Also assert returned collections are immutable by attempting to mutate the returned `TicketMessages.messages()`.

- [ ] **Step 2: Run RED**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuImTicketStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `FeishuImTicketStore` does not exist.

- [ ] **Step 3: Implement store**

Use `synchronized`, `LinkedHashMap`, and immutable snapshots. Convert `FeishuImTicketDraft` to `TicketSnapshot` and `TicketMessage`.

- [ ] **Step 4: Run GREEN**

Run the same command. Expected: store tests pass.

### Task 3: IM Adapter and Client

**Files:**
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketAdapterTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImProperties.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImClient.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketAdapter.java`

- [ ] **Step 1: Write failing adapter tests**

Tests should assert:

```java
TicketUpdateResult disabled = adapterWithWriteBackDisabled.sendMessage(command);
assertFalse(disabled.success());
assertEquals("WRITE_BACK_DISABLED", disabled.providerCode());
```

For enabled write-back, use a stub transport and assert:

```java
assertEquals("/open-apis/im/v1/messages?receive_id_type=chat_id", requestPathAndQuery);
assertTrue(requestBody.contains("\"receive_id\":\"oc-chat\""));
assertTrue(requestBody.contains("\"msg_type\":\"text\""));
assertFalse(requestBody.contains("app-secret"));
```

- [ ] **Step 2: Run RED**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuImTicketAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because adapter/client classes do not exist.

- [ ] **Step 3: Implement properties, client, adapter**

`FeishuImProperties` binds `rd.feishu.im`. `FeishuImClient` uses app id/secret only for tenant token and sends text messages with `receive_id_type=chat_id`. `FeishuImTicketAdapter` delegates reads to `FeishuImTicketStore`; writes find the ticket's `chatId` and call the client when enabled.

- [ ] **Step 4: Run GREEN**

Run the same command. Expected: adapter tests pass.

### Task 4: Event Controller

**Files:**
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImMessageControllerTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImMessageController.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketEventInput.java`

- [ ] **Step 1: Write failing controller tests**

Use `MockMvcBuilders.standaloneSetup(controller)` and a capturing `RepairQueuePublisher`.

Assertions:

```java
mockMvc.perform(post("/feishu/im/events").content("{\"type\":\"url_verification\",\"challenge\":\"c1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.challenge").value("c1"));
```

For a text mention event:

```java
assertEquals("feishu.im.message.created_v1", published.eventType());
assertEquals("feishu-im", published.source());
assertTrue(adapter.findTicket(published.ticketId()).isPresent());
```

For a non-mentioned group message when `requireAtMention=true`, assert `ignored=true` and no queue publication.

- [ ] **Step 2: Run RED**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuImMessageControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because controller and event constant do not exist.

- [ ] **Step 3: Implement controller and event constant**

Parse Feishu v2 event envelopes under `event.message` and `event.sender`. Remove mention keys from text before parsing. Generate ticket IDs from Feishu `message_id` as `FI-<sanitized-message-id>` when present; otherwise generate `FI-<short-uuid>`.

- [ ] **Step 4: Run GREEN**

Run the same command. Expected: controller tests pass.

### Task 5: Wiring and Config Compatibility

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/MockFeishuTicketAdapter.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairEngine.java`
- Add or modify tests as needed to prove bean wiring.

- [ ] **Step 1: Write failing wiring tests**

Add tests that start a narrow Spring context or instantiate conditions to prove:

```java
rd.repair.ticket.provider=feishu-im
rd.feishu.im.enabled=true
```

wires the IM adapter and does not wire the mock adapter as a competing `TicketProviderPort`.

- [ ] **Step 2: Run RED**

Run the wiring test. Expected: fails because mock still loads by Helpdesk-disabled default.

- [ ] **Step 3: Implement wiring**

Use provider-based conditions:

- Mock loads when provider is `mock` and external providers are disabled.
- IM adapter loads when provider is `feishu-im`.
- Helpdesk remains available under `rd.feishu.helpdesk.enabled=true`.

Change `TicketRepairEngine` `@Value` to read:

```java
@Value("${rd.ticket.write-back.enabled:${rd.feishu.helpdesk.write-back.enabled:false}}")
```

- [ ] **Step 4: Run GREEN**

Run focused wiring tests. Expected: one provider bean for IM mode.

### Task 6: Verification and Commits

**Files:**
- Modify: `docs/smoke/rd-bot-bugfix-real-smoke-report-2026-06-23.md`

- [ ] **Step 1: Run focused tests**

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuImTicketParserTest,FeishuImTicketStoreTest,FeishuImTicketAdapterTest,FeishuImMessageControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl engine -Dtest=TicketRepairEngineTest test
```

Expected: all focused tests pass.

- [ ] **Step 2: Run broader tests**

```bash
./mvnw -pl bootstrap -am test
./mvnw test
```

Expected: all tests pass or failures are documented with exact external dependency reason.

- [ ] **Step 3: Update smoke report**

Record:

- Helpdesk personal-account blocker.
- IM replacement design and enabled properties.
- Focused test output.
- Whether full Maven passed.
- Remaining real Feishu callback requirement: public URL or deployment.

- [ ] **Step 4: Commit in batches**

```bash
git add -f docs/superpowers/specs/2026-06-23-feishu-im-ticket-ingestion-design.md docs/superpowers/plans/2026-06-23-feishu-im-ticket-ingestion.md
git commit -m "docs(feishu): specify im ticket replacement"

git add bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketParserTest.java bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketStoreTest.java bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketDraft.java bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketParser.java bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImTicketStore.java
git commit -m "feat(feishu): add im ticket parsing store"

git add bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImTicketAdapterTest.java bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuImMessageControllerTest.java bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im bootstrap/src/main/resources/application.yaml engine/src/main/java/com/wish/rd/engine/ticket/TicketEventInput.java engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairEngine.java
git commit -m "feat(feishu): ingest im tickets"

git add -f docs/smoke/rd-bot-bugfix-real-smoke-report-2026-06-23.md
git commit -m "docs(smoke): record feishu im replacement"
```

## Self-Review

- Spec coverage: the plan covers provider selection, IM event parsing, local ticket storage, write-back, generic write-back property, tests, and smoke report updates.
- Placeholder scan: no `TBD`, `TODO`, or unbounded "add tests" steps remain.
- Type consistency: all new class names share package `com.wish.rd.bootstrap.feishu.im`; existing engine/rag port names match current repository code.
