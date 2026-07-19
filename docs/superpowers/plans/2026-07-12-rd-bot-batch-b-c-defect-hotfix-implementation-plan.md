# RD-Bot Batch B+C Defect Hotfix Implementation Plan

> **For agentic workers:** Execute after Batch A. Do not commit unless the user asks.

**Goal:** Complete semantic alignment (Batch B) and structural/UX/test closeout (Batch C) from the 2026-07-12 audit.

**Architecture:** Prefer focused changes in existing engines/stores; only move dispatch into `engine` if the bootstrap adapter can stay thin without a large rewrite.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, Vite/React.

**Source:** `docs/superpowers/specs/2026-07-12-rd-bot-defect-audit-solutions-and-acceptance.md`

---

## Batch B Tasks

### B1 Stage/task needs-human alignment (CP-06)
- Fail tests in `RequirementDeliveryEngineTest` for non-QA FAILED_NEEDS_HUMAN → task FAILED_NEEDS_HUMAN
- Fix aggregation/`markRequirementFailedNeedsHuman`

### B2 RECOVERING recovery edges (CP-07)
- Allow `RECOVERING → WAITING_APPROVAL` (and needed policy edges) OR force safe recovery path
- Tests in `RdTaskTransitionPolicyTest` + engine recovery submit

### B3 Dead-letter requeue (CP-08)
- `enqueue` allows DEAD_LETTERED/CANCELLED → PENDING with attempt reset
- Tests in InMemory + mapper semantics

### B4 Retrieval idempotency + failure terminals (RAG-03..11)
- Recorder reuse/retry; Lifecycle failRetryable/failNeedsHuman
- Pipeline try/catch/finally; channel-all-fail vs empty-hit
- create/retry `@Transactional`; double-retry idempotent
- Operator retry: document or async dispatch minimal hook

### B5 Detail sequence + refresh (FE-04/05)
- loadSeq on RdTaskDetailPage; refresh runs after control actions

---

## Batch C Tasks

### C1 Thin dispatch / @Service (CP-11/20)
- Prefer extracting `RequirementDeliveryDispatchEngine` in engine if feasible in-session; else leave follow-up note and at least `@Service` on control engine

### C2 deleteTask atomic audit (CP-12)
- Transactional delete that retains DELETED event or documented archive

### C3 submit response job snapshot (CP-13)
- Controller returns dispatchAccepted + job fields

### C4 recover continue / recency / cancel not retryable (CP-14..16)
- recover continues on reject; shared recency comparator; CANCELLED/SKIPPED not auto-retry

### C5 Test net + Coordinated warning (CP-17..19)
- Real rollback assertion where possible; dispatch dead-letter/double-run tests; Coordinated docs/log

### C6 FE/RAG closeout (FE-06+/RAG-13+)
- delivery-job 404 + panel; quality labels; stop button if time; RRF topK; policy trim; parent FK optional

### C7 Gate
```bash
./mvnw -q -pl rag,engine,bootstrap -Dtest=*Transition*,*Dispatch*,*Retrieval*,*KnowledgeMap*,*Prometheus*,*Control* test
cd frontend && node --test test/viteProxy.test.ts && npm run typecheck && npm run build
```
