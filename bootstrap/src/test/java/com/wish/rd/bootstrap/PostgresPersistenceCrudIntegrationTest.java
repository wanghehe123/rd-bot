package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.user.controller.vo.UserVO;
import com.wish.rd.bootstrap.user.service.UserAdminService;
import com.wish.rd.exec.repair.CreateRepairAssetCommand;
import com.wish.rd.exec.repair.CreateRepairRecordArtifactCommand;
import com.wish.rd.exec.repair.CreateRepairRecordCommand;
import com.wish.rd.exec.repair.RepairAsset;
import com.wish.rd.exec.repair.RepairAssetType;
import com.wish.rd.exec.repair.RepairRecord;
import com.wish.rd.exec.repair.RepairRecordArtifact;
import com.wish.rd.exec.repair.RepairRecordRepository;
import com.wish.rd.exec.repair.RepairRecordStatus;
import com.wish.rd.engine.agent.AgentRole;
import com.wish.rd.engine.agent.AgentStageRun;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageStatus;
import com.wish.rd.engine.agent.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.WorkflowExperienceType;
import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextEvidence;
import com.wish.rd.rag.context.RoleContextPackage;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.ingestion.IngestionStatus;
import com.wish.rd.rag.ingestion.IngestionTaskStore;
import com.wish.rd.rag.ingestion.ManagedIngestionTask;
import com.wish.rd.rag.ingestion.ManagedIngestionTaskNode;
import com.wish.rd.rag.knowledge.KnowledgeBase;
import com.wish.rd.rag.knowledge.KnowledgeChunk;
import com.wish.rd.rag.knowledge.KnowledgeDocument;
import com.wish.rd.rag.knowledge.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.vector.VectorStore;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "rd.integration.postgres.enabled", matches = "true")
class PostgresPersistenceCrudIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private UserAdminService userAdminService;
    @Autowired
    private KnowledgeBaseStore baseStore;
    @Autowired
    private KnowledgeDocumentStore documentStore;
    @Autowired
    private KnowledgeChunkStore chunkStore;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private IngestionTaskStore ingestionTaskStore;
    @Autowired
    private RdTaskStore rdTaskStore;
    @Autowired
    private AgentStageRunStore agentStageRunStore;
    @Autowired
    private RoleContextPackageStore roleContextPackageStore;
    @Autowired
    private WorkflowExperienceStore workflowExperienceStore;
    @Autowired
    private RepairRecordRepository repairRecordRepository;

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("rd.knowledge.store", () -> "postgres");
        registry.add("rd.storage.mode", () -> "memory");
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.postgres.url",
                "jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8"
        ));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.postgres.username",
                "postgres"
        ));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.postgres.password",
                "postgres"
        ));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Test
    void should_crud所有Postgres实体_当启用本地数据库() {
        String marker = "crud-it-" + UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();

        try {
            UserVO user = verifyAdminUserCrud(marker);
            KnowledgeBase base = verifyKnowledgeBaseCrud(marker, now);
            KnowledgeDocument document = verifyKnowledgeDocumentCrud(marker, base.id(), now);
            verifyKnowledgeChunkCrud(marker, base.id(), document.id());
            verifyVectorCrud(marker, base.id());
            verifyIngestionTaskCrud(marker, base.id(), document.id(), now);
            verifyRdTaskCrud(marker, now);
            verifyMultiAgentOrchestrationCrud(marker, now);
            verifyRepairRecordCrud(marker);
            verifyKnowledgeDeletes(base.id(), document.id());

            assertDoesNotThrow(() -> userAdminService.delete(user.id()));
        } finally {
            cleanup(marker);
        }
    }

    private UserVO verifyAdminUserCrud(String marker) {
        UserVO created = userAdminService.create(marker, "secret-123", "user", "avatar-a");
        UserVO updated = userAdminService.update(created.id(), marker + "-updated", "secret-456", "admin", "avatar-b");
        List<UserVO> records = userAdminService.list(1, 20, marker).records();

        assertAll(
                () -> assertEquals(marker, created.username()),
                () -> assertEquals(marker + "-updated", updated.username()),
                () -> assertEquals("admin", updated.role()),
                () -> assertTrue(records.stream().anyMatch(user -> user.id().equals(created.id())))
        );
        return updated;
    }

    private KnowledgeBase verifyKnowledgeBaseCrud(String marker, long now) {
        String baseId = id();
        KnowledgeBase created = new KnowledgeBase(baseId, marker + "-kb", "desc-a", true, now);
        baseStore.save(created);
        KnowledgeBase updated = created.withName(marker + "-kb-updated").withEnabled(false);
        baseStore.save(updated);

        assertAll(
                () -> assertEquals(marker + "-kb-updated", baseStore.findById(baseId).orElseThrow().name()),
                () -> assertFalse(baseStore.findById(baseId).orElseThrow().enabled()),
                () -> assertTrue(baseStore.list().stream().anyMatch(base -> base.id().equals(baseId)))
        );
        return updated;
    }

    private KnowledgeDocument verifyKnowledgeDocumentCrud(String marker, String baseId, long now) {
        String documentId = id();
        KnowledgeDocument created = new KnowledgeDocument(
                documentId,
                baseId,
                marker + ".md",
                "api",
                "text/markdown",
                KnowledgeDocumentStatus.INDEXED,
                true,
                1,
                List.of(),
                now,
                "feishu",
                marker + "-token",
                "https://example.com/" + marker,
                "rev-a",
                "checksum-a",
                "preview-a",
                now,
                now + Duration.ofHours(1).toMillis()
        );
        documentStore.save(created, "raw-a " + marker);
        KnowledgeDocument updated = created
                .withDocumentFields(marker + "-updated.md", "runtime-log")
                .withEnabled(false)
                .withChunkCount(2)
                .withSyncState("rev-b", "checksum-b", "preview-b", now + 1, now + 2);
        documentStore.save(updated, "raw-b " + marker);

        assertAll(
                () -> assertEquals(marker + "-updated.md", documentStore.findById(documentId).orElseThrow().sourceName()),
                () -> assertEquals("raw-b " + marker, documentStore.rawContent(documentId)),
                () -> assertEquals(documentId, documentStore.findBySource(baseId, "FEISHU", marker + "-token", "").orElseThrow().id()),
                () -> assertTrue(documentStore.listByKnowledgeBaseId(baseId).stream()
                        .anyMatch(document -> document.id().equals(documentId))),
                () -> assertTrue(documentStore.listAll().stream().anyMatch(document -> document.id().equals(documentId)))
        );
        return updated;
    }

    private void verifyKnowledgeChunkCrud(String marker, String baseId, String documentId) {
        String chunkId = id();
        KnowledgeChunk created = new KnowledgeChunk(
                chunkId,
                documentId,
                baseId,
                0,
                "chunk-a " + marker,
                "api",
                marker + ".md",
                true,
                Map.of("marker", marker)
        );
        chunkStore.save(created);
        KnowledgeChunk updated = created
                .withContent("chunk-b " + marker)
                .withDocumentFields("runtime-log", marker + "-updated.md")
                .withEnabled(false);
        chunkStore.save(updated);

        assertAll(
                () -> assertEquals("chunk-b " + marker, chunkStore.findById(chunkId).orElseThrow().content()),
                () -> assertFalse(chunkStore.findById(chunkId).orElseThrow().enabled()),
                () -> assertTrue(chunkStore.listByDocumentId(documentId).stream()
                        .anyMatch(chunk -> chunk.id().equals(chunkId))),
                () -> assertTrue(chunkStore.listAll().stream().anyMatch(chunk -> chunk.id().equals(chunkId)))
        );

        chunkStore.delete(chunkId);
        assertTrue(chunkStore.findById(chunkId).isEmpty());

        String secondChunkId = id();
        chunkStore.save(new KnowledgeChunk(
                secondChunkId,
                documentId,
                baseId,
                1,
                "chunk-c " + marker,
                "api",
                marker + ".md",
                true,
                Map.of("marker", marker)
        ));
        chunkStore.deleteByDocumentId(documentId);
        assertFalse(chunkStore.listByDocumentId(documentId).stream().anyMatch(chunk -> chunk.id().equals(secondChunkId)));
    }

    private void verifyVectorCrud(String marker, String baseId) {
        String chunkId = id();
        RetrievedChunk created = new RetrievedChunk(
                chunkId,
                "vector content alpha " + marker,
                baseId,
                "api",
                marker + ".md",
                0.0d,
                Map.of("marker", marker)
        );
        vectorStore.index(List.of(created));

        assertAll(
                () -> assertTrue(vectorStore.allChunks().stream().anyMatch(chunk -> chunk.chunkId().equals(chunkId))),
                () -> assertTrue(vectorStore.keywordSearch(marker, List.of(baseId), 3).stream()
                        .anyMatch(chunk -> chunk.chunkId().equals(chunkId))),
                () -> assertTrue(vectorStore.vectorSearch("alpha " + marker, List.of(baseId), 3).stream()
                        .anyMatch(chunk -> chunk.chunkId().equals(chunkId)))
        );

        vectorStore.replace(new RetrievedChunk(
                chunkId,
                "vector content beta " + marker,
                baseId,
                "api",
                marker + "-updated.md",
                0.0d,
                Map.of("marker", marker)
        ));
        assertEquals("vector content beta " + marker, vectorStore.allChunks().stream()
                .filter(chunk -> chunk.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow()
                .content());

        vectorStore.removeChunks(List.of(chunkId));
        assertFalse(vectorStore.allChunks().stream().anyMatch(chunk -> chunk.chunkId().equals(chunkId)));
    }

    private void verifyIngestionTaskCrud(String marker, String baseId, String documentId, long now) {
        String taskId = id();
        ManagedIngestionTask created = new ManagedIngestionTask(
                taskId,
                "pipeline-" + marker,
                baseId,
                documentId,
                "LOCAL",
                "/tmp/" + marker,
                marker + ".md",
                IngestionStatus.RUNNING,
                0,
                "",
                Map.of("marker", marker),
                now,
                null,
                "integration-test",
                now,
                now
        );
        ingestionTaskStore.saveTask(created);
        ManagedIngestionTask updated = new ManagedIngestionTask(
                taskId,
                "pipeline-" + marker,
                baseId,
                documentId,
                "LOCAL",
                "/tmp/" + marker,
                marker + ".md",
                IngestionStatus.COMPLETED,
                2,
                "",
                Map.of("marker", marker, "updated", true),
                now,
                now + 1,
                "integration-test",
                now,
                now + 2
        );
        ingestionTaskStore.saveTask(updated);

        assertAll(
                () -> assertEquals(IngestionStatus.COMPLETED, ingestionTaskStore.findTask(taskId).orElseThrow().status()),
                () -> assertEquals(2, ingestionTaskStore.findTask(taskId).orElseThrow().chunkCount()),
                () -> assertTrue(ingestionTaskStore.listTasks().stream().anyMatch(task -> task.id().equals(taskId)))
        );

        ingestionTaskStore.saveTaskNodes(taskId, List.of(node(taskId, marker, "parse", 0, "SUCCESS")));
        assertEquals("parse", ingestionTaskStore.listTaskNodes(taskId).getFirst().nodeId());

        ingestionTaskStore.saveTaskNodes(taskId, List.of(node(taskId, marker, "index", 0, "SUCCESS")));
        List<ManagedIngestionTaskNode> replaced = ingestionTaskStore.listTaskNodes(taskId);
        assertAll(
                () -> assertEquals(1, replaced.size()),
                () -> assertEquals("index", replaced.getFirst().nodeId())
        );
    }

    private ManagedIngestionTaskNode node(String taskId, String marker, String nodeId, int order, String status) {
        long now = System.currentTimeMillis();
        return new ManagedIngestionTaskNode(
                id(),
                taskId,
                "pipeline-" + marker,
                nodeId,
                "TEST",
                order,
                status,
                12L,
                "message-" + marker,
                "",
                Map.of("marker", marker, "nodeId", nodeId),
                now,
                now
        );
    }

    private void verifyRdTaskCrud(String marker, long now) {
        String taskId = id();
        RdBugFixTask created = RdBugFixTask.created(taskId, marker + "-ticket", marker + "-title", "P1", now);
        rdTaskStore.saveBugFixTask(created);
        RdBugFixTask updated = created.withState(
                RdTaskStatus.COMMITTED,
                marker + "-message",
                marker + "-display-title",
                "prompt " + marker,
                "{\"status\":\"ok\"}",
                "https://github.com/example/repo/pull/1",
                "",
                now + 1
        );
        rdTaskStore.saveBugFixTask(updated);

        assertAll(
                () -> assertEquals(RdTaskStatus.COMMITTED, rdTaskStore.findBugFixTask(taskId).orElseThrow().status()),
                () -> assertEquals("https://github.com/example/repo/pull/1",
                        rdTaskStore.findBugFixTask(taskId).orElseThrow().pullRequestUrl()),
                () -> assertTrue(rdTaskStore.listBugFixTasks().stream().anyMatch(task -> task.taskId().equals(taskId)))
        );
    }

    private void verifyMultiAgentOrchestrationCrud(String marker, long now) {
        String taskId = id();
        rdTaskStore.saveBugFixTask(RdBugFixTask.created(
                taskId,
                marker + "-agent-ticket",
                marker + "-agent-title",
                "P1",
                now
        ));
        RoleContextPackage context = new RoleContextPackage(
                id(),
                taskId,
                AgentRole.QA_AGENT.name(),
                1,
                List.of(new RoleContextEvidence(
                        "mat-" + marker,
                        "MANUAL_TEXT",
                        "https://example.com/" + marker,
                        "真实验收材料",
                        "sha256:" + marker,
                        "QA 真实验收日志",
                        now
                )),
                List.of("真实验收通过"),
                List.of("不得泄露密钥"),
                8_000,
                32,
                List.of(),
                now
        );
        roleContextPackageStore.save(context);

        AgentStageRun stageRun = AgentStageRun.pending(
                id(),
                taskId,
                AgentRole.QA_AGENT,
                1,
                taskId + ":QA_AGENT:1",
                now
        );
        agentStageRunStore.save(stageRun);
        AgentStageRun contextReady = agentStageRunStore.transition(
                stageRun.stageRunId(),
                AgentStageStatus.CONTEXT_READY,
                "",
                "",
                now + 1
        );
        WorkflowExperienceEntry experience = new WorkflowExperienceEntry(
                id(),
                taskId,
                stageRun.stageRunId(),
                "",
                AgentRole.QA_AGENT,
                WorkflowExperienceType.QA_REPORT,
                "QA 验收 " + marker,
                "真实测试通过 " + marker,
                "{\"status\":\"PASSED\",\"marker\":\"" + marker + "\"}",
                true,
                false,
                true,
                now + 2
        );
        workflowExperienceStore.save(experience);

        assertAll(
                () -> assertEquals(context.packageId(), roleContextPackageStore.findById(context.packageId()).orElseThrow().packageId()),
                () -> assertEquals(List.of(context), roleContextPackageStore.listByTaskAndRole(taskId, "qa_agent")),
                () -> assertEquals(AgentStageStatus.CONTEXT_READY, contextReady.status()),
                () -> assertTrue(agentStageRunStore.listByTask(taskId).stream()
                        .anyMatch(stage -> stage.stageRunId().equals(stageRun.stageRunId()))),
                () -> assertEquals(List.of(experience), workflowExperienceStore.listByTask(taskId))
        );
    }

    private void verifyRepairRecordCrud(String marker) {
        RepairRecord created = repairRecordRepository.create(new CreateRepairRecordCommand(
                marker + "-ticket",
                "https://ticket.example.com/" + marker,
                marker + "-repair",
                Map.of("marker", marker, "priority", "P1", "traceId", "trace-" + marker)
        ));
        // P1 新增状态轮转：QUEUED -> CONTEXT_COLLECTING -> CONTEXT_READY
        RepairRecord queued = repairRecordRepository.updateStatus(
                created.id(), RepairRecordStatus.QUEUED, "queued"
        );
        RepairRecord collecting = repairRecordRepository.updateStatus(
                created.id(), RepairRecordStatus.CONTEXT_COLLECTING, "collecting"
        );
        RepairRecord ready = repairRecordRepository.updateStatus(
                created.id(), RepairRecordStatus.CONTEXT_READY, "rag summary " + marker
        );
        RepairRecordArtifact eventArtifact = repairRecordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                created.id(), "FEISHU_EVENT", "", "event-" + marker
        ));
        RepairRecordArtifact snapshotArtifact = repairRecordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                created.id(), "FEISHU_TICKET_SNAPSHOT", "", "snapshot-" + marker
        ));
        RepairRecordArtifact messagesArtifact = repairRecordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                created.id(), "FEISHU_MESSAGES", "", "messages-" + marker
        ));
        RepairRecordArtifact ragArtifact = repairRecordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                created.id(), "RAG_CONTEXT", "", "rag-" + marker
        ));
        RepairAsset rootCauseAsset = repairRecordRepository.addAsset(new CreateRepairAssetCommand(
                created.id(),
                RepairAssetType.BUG_CAUSE,
                "root-cause-" + marker,
                "client/server field mismatch",
                "{\"field\":\"address\",\"marker\":\"" + marker + "\"}",
                ragArtifact.id(),
                true
        ));
        RepairAsset planAsset = repairRecordRepository.addAsset(new CreateRepairAssetCommand(
                created.id(),
                RepairAssetType.ACCEPTANCE_PLAN,
                "acceptance-plan-" + marker,
                "planner output",
                "{\"status\":\"READY\",\"marker\":\"" + marker + "\"}",
                "",
                false
        ));
        repairRecordRepository.updateExecutorJson(created.id(), "{\"status\":\"SUCCESS\"}");
        repairRecordRepository.updateDockerJson(created.id(), "{\"image\":\"rd-bot/claude-code:local\"}");
        repairRecordRepository.updateGithubJson(created.id(),
                "{\"pullRequestUrl\":\"https://github.com/example/repo/pull/1\"}");
        repairRecordRepository.updateTestJson(created.id(), "{\"testStatus\":\"PASSED\"}");
        repairRecordRepository.updateRiskJson(created.id(), "{\"riskLevel\":\"LOW\"}");
        repairRecordRepository.updateErrorMessage(created.id(), "manual review note " + marker);
        RepairRecord metadata = repairRecordRepository.findById(created.id()).orElseThrow();

        // 第二条记录用于验证分页查询和跨记录 source artifact 拒绝
        RepairRecord otherRecord = repairRecordRepository.create(new CreateRepairRecordCommand(
                marker + "-ticket-2", "", marker + "-repair-2",
                Map.of("priority", "P0")
        ));
        RepairRecordArtifact foreignArtifact = repairRecordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                otherRecord.id(), "RAG_CONTEXT", "", "foreign-rag-" + marker
        ));
        IllegalArgumentException foreignArtifactException = assertThrows(
                IllegalArgumentException.class,
                () -> repairRecordRepository.addAsset(new CreateRepairAssetCommand(
                        created.id(),
                        RepairAssetType.ACCEPTANCE_PLAN,
                        "foreign-asset-" + marker,
                        "should fail",
                        "{}",
                        foreignArtifact.id(),
                        false
                ))
        );

        assertAll(
                () -> assertEquals(RepairRecordStatus.QUEUED, queued.status()),
                () -> assertEquals(RepairRecordStatus.CONTEXT_COLLECTING, collecting.status()),
                () -> assertEquals(RepairRecordStatus.CONTEXT_READY, ready.status()),
                () -> assertEquals("rag summary " + marker, ready.ragSummary()),
                () -> assertEquals(ready.id(), repairRecordRepository.findById(created.id()).orElseThrow().id()),
                () -> assertEquals(ready.id(), repairRecordRepository.findByTicketId(marker + "-ticket").orElseThrow().id()),
                () -> assertEquals(4, repairRecordRepository.listArtifacts(created.id()).size()),
                () -> assertEquals(2, repairRecordRepository.listAssets(created.id()).size()),
                () -> assertEquals(rootCauseAsset.id(), repairRecordRepository.listAssets(created.id()).getFirst().id()),
                () -> assertEquals(planAsset.id(), repairRecordRepository.listAssets(created.id()).get(1).id()),
                () -> assertEquals(RepairAssetType.BUG_CAUSE,
                        repairRecordRepository.listAssets(created.id()).getFirst().assetType()),
                () -> assertTrue(foreignArtifactException.getMessage().contains("source artifact must belong")),
                () -> assertTrue(metadata.executorJson().contains("\"status\"")),
                () -> assertTrue(metadata.executorJson().contains("SUCCESS")),
                () -> assertTrue(metadata.dockerJson().contains("rd-bot/claude-code:local")),
                () -> assertTrue(metadata.githubJson().contains("https://github.com/example/repo/pull/1")),
                () -> assertTrue(metadata.testJson().contains("PASSED")),
                () -> assertTrue(metadata.riskJson().contains("LOW")),
                () -> assertEquals("manual review note " + marker, metadata.errorMessage()),
                // 分页查询：按 status 过滤
                () -> assertTrue(repairRecordRepository.query(new com.wish.rd.exec.repair.RepairRecordQuery(
                        "", "CONTEXT_READY", "", 0L, 0L, 1, 20
                )).records().stream().anyMatch(record -> record.id().equals(created.id()))),
                // 分页查询：按 priority 过滤（存于 extension_json）
                () -> assertEquals(1, repairRecordRepository.query(new com.wish.rd.exec.repair.RepairRecordQuery(
                        "", "", "P0", 0L, 0L, 1, 20
                )).total()),
                // 分页查询：按 ticketId 过滤
                () -> assertEquals(1, repairRecordRepository.query(new com.wish.rd.exec.repair.RepairRecordQuery(
                        marker + "-ticket-2", "", "", 0L, 0L, 1, 20
                )).total())
        );
    }

    private void verifyKnowledgeDeletes(String baseId, String documentId) {
        documentStore.delete(documentId);
        assertTrue(documentStore.findById(documentId).isEmpty());

        baseStore.delete(baseId);
        assertTrue(baseStore.findById(baseId).isEmpty());
    }

    private void cleanup(String marker) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM repair_record_artifacts WHERE summary LIKE '%" + marker + "%'");
            statement.executeUpdate("DELETE FROM repair_assets WHERE summary LIKE '%" + marker + "%'");
            statement.executeUpdate("DELETE FROM repair_records WHERE ticket_id LIKE '" + marker + "%'");
            statement.executeUpdate("DELETE FROM rd_tasks WHERE ticket_id LIKE '" + marker + "%'");
            statement.executeUpdate("DELETE FROM ingestion_task_nodes WHERE pipeline_id = 'pipeline-" + marker + "'");
            statement.executeUpdate("DELETE FROM ingestion_tasks WHERE pipeline_id = 'pipeline-" + marker + "'");
            statement.executeUpdate("DELETE FROM knowledge_vectors WHERE metadata_json->>'marker' = '" + marker + "'");
            statement.executeUpdate("DELETE FROM knowledge_chunks WHERE metadata_json->>'marker' = '" + marker + "'");
            statement.executeUpdate("DELETE FROM knowledge_documents WHERE source_token = '" + marker + "-token'");
            statement.executeUpdate("DELETE FROM knowledge_bases WHERE name LIKE '" + marker + "%'");
            statement.executeUpdate("DELETE FROM admin_users WHERE username LIKE '" + marker + "%'");
        } catch (Exception exception) {
            throw new IllegalStateException("failed to clean postgres integration data: " + marker, exception);
        }
    }

    private String id() {
        return idGenerator.nextIdString();
    }
}
