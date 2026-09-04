package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.engine.requirement.audit.EvidenceRef;
import com.wish.rd.engine.requirement.audit.EvidenceSourceKind;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Frozen admin HTTP contract for Host-owned audited task state.
 *
 * <p>Standalone controller + in-memory store; no Spring Boot context.
 */
class RdTaskAuditedStateControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final Set<String> STATE_FIELDS = Set.of(
            "taskId",
            "projectId",
            "present",
            "stateVersion",
            "stateHash",
            "lastAuditRunId",
            "records",
            "contractRef",
            "completionBinding"
    );
    private static final Set<String> RECORD_FIELDS = Set.of(
            "id", "kind", "blocking", "text", "status", "evidenceRefs", "sourceStageRunId", "blockedReason");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("auditRunId", "sourceKind", "uri", "sha256");
    private static final Set<String> RUN_LIST_FIELDS = Set.of("taskId", "projectId", "runs");
    private static final Set<String> RUN_FIELDS = Set.of(
            "auditRunId",
            "subjectStageRunId",
            "subjectRole",
            "commandId",
            "completion",
            "integrity",
            "contractAudit",
            "verified",
            "missing",
            "untrusted",
            "blockers",
            "sourceRefs",
            "createdAtEpochMillis"
    );

    private RagStreamTaskRegistry registry;
    private InMemoryAuditedTaskStateStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = RagStreamTaskRegistry.inMemory();
        store = new InMemoryAuditedTaskStateStore();
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskAuditedStateController(registry, store)).build();
    }

    @Test
    void shouldReturnEmptyStateForExistingRequirementWithoutHead() throws Exception {
        RdRequirementTask task = createTask("空审计任务", "project-owned");

        MvcResult result = mockMvc.perform(get("/admin/rd-tasks/{taskId}/audited-state", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.taskId()))
                .andExpect(jsonPath("$.projectId").value("project-owned"))
                .andExpect(jsonPath("$.present").value(false))
                .andExpect(jsonPath("$.records").isEmpty())
                .andExpect(jsonPath("$.completionBinding").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contractRef").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(STATE_FIELDS, fieldNames(body));
        assertFalse(containsForbiddenFields(result.getResponse().getContentAsString()));
    }

    @Test
    void shouldReturnHeadRecordsWithoutPromptOrExecutionPayloads() throws Exception {
        RdRequirementTask task = createTask("有审计任务", "project-owned");
        seedHeadAndRun(task, "must-not-appear-as-prompt");

        MvcResult result = mockMvc.perform(get("/admin/rd-tasks/{taskId}/audited-state", task.taskId())
                        .param("projectId", "project-owned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true))
                .andExpect(jsonPath("$.stateVersion").value(2))
                .andExpect(jsonPath("$.lastAuditRunId").value("audit-1"))
                .andExpect(jsonPath("$.records[0].id").value("AC-001"))
                .andExpect(jsonPath("$.records[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.records[0].evidenceRefs[0].uri")
                        .value("qa-evidence://artifacts/current.log"))
                .andExpect(jsonPath("$.completionBinding.auditRunId").value("audit-1"))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(STATE_FIELDS, fieldNames(body));
        assertEquals(RECORD_FIELDS, fieldNames(body.get("records").get(0)));
        assertEquals(EVIDENCE_FIELDS, fieldNames(body.get("records").get(0).get("evidenceRefs").get(0)));
        assertFalse(containsForbiddenFields(result.getResponse().getContentAsString()));
    }

    @Test
    void shouldListAuditRunsOwnedByTask() throws Exception {
        RdRequirementTask task = createTask("审计轮次任务", "project-owned");
        seedHeadAndRun(task, "secret-prompt");

        MvcResult result = mockMvc.perform(get("/admin/rd-tasks/{taskId}/audit-runs", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.taskId()))
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].auditRunId").value("audit-1"))
                .andExpect(jsonPath("$.runs[0].commandId").value("cmd-1"))
                .andExpect(jsonPath("$.runs[0].completion").value("INCOMPLETE"))
                .andExpect(jsonPath("$.runs[0].blockers[0]").value("GATE-BUILD"))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(RUN_LIST_FIELDS, fieldNames(body));
        assertEquals(RUN_FIELDS, fieldNames(body.get("runs").get(0)));
        assertFalse(containsForbiddenFields(result.getResponse().getContentAsString()));
    }

    @Test
    void shouldReturn404WhenProjectIdDoesNotMatch() throws Exception {
        RdRequirementTask owner = createTask("属主审计任务", "project-owned");
        seedHeadAndRun(owner, "AC-LEAK-MUST-NOT-APPEAR");

        MvcResult state = mockMvc.perform(get("/admin/rd-tasks/{taskId}/audited-state", owner.taskId())
                        .param("projectId", "project-other"))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult runs = mockMvc.perform(get("/admin/rd-tasks/{taskId}/audit-runs", owner.taskId())
                        .param("projectId", "project-other"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertFalse(state.getResponse().getContentAsString().contains("AC-LEAK-MUST-NOT-APPEAR"));
        assertFalse(runs.getResponse().getContentAsString().contains("audit-1"));
        assertFalse(containsForbiddenFields(state.getResponse().getContentAsString()));
    }

    @Test
    void shouldReturn404ForUnknownTask() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/audited-state", "9999999999999999"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/audit-runs", "9999999999999999"))
                .andExpect(status().isNotFound());
    }

    private RdRequirementTask createTask(String title, String projectId) {
        return registry.createRequirementTask(new CreateRequirementTaskCommand(
                title,
                "P1",
                "ADMIN",
                "",
                "",
                projectId,
                "owned-key",
                "Owned Project",
                "https://github.com/acme/web.git",
                "",
                "",
                "main",
                "页面可用",
                List.of("真实浏览器通过"),
                List.of(),
                false
        ));
    }

    private void seedHeadAndRun(RdRequirementTask task, String criterionText) {
        EvidenceRef evidence = new EvidenceRef(
                "audit-1",
                EvidenceSourceKind.QA_EVIDENCE,
                "qa-evidence://artifacts/current.log",
                HASH);
        AuditedRecord record = new AuditedRecord(
                "AC-001",
                AuditedRecordKind.REQUIREMENT,
                true,
                criterionText,
                AuditedRecordStatus.COMPLETED,
                List.of(evidence),
                "",
                "");
        AuditedTaskState initial = new AuditedTaskState(
                task.taskId(),
                1L,
                HASH,
                new AuditedContractRef(HASH, Math.max(0L, task.version()), Math.max(1L, task.fencingToken())),
                List.of(record),
                "");
        store.initializeIfAbsent(initial);
        AuditedTaskState next = initial.withRevision(2L, HASH, initial.records(), "audit-1");
        store.appendRevision(2L, next, new AuditRun(
                "audit-1",
                task.taskId(),
                "stage-1",
                "QA_AGENT",
                "cmd-1",
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of("AC-001"),
                List.of("GATE-BUILD"),
                List.of(),
                List.of("GATE-BUILD"),
                List.of("qa-evidence://artifacts/current.log"),
                1_700L
        ));
        store.bindCompletion(task.taskId(), "audit-1", 2L, HASH);
    }

    private static boolean containsForbiddenFields(String json) {
        String lower = json.toLowerCase();
        return lower.contains("prompt_snapshot")
                || lower.contains("promptsnapshot")
                || lower.contains("execution_result_json")
                || lower.contains("executionresultjson");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }
}
