package com.wish.rd.exec.repair;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRepairRecordRepositoryTest {

    @Test
    void shouldCreateUpdateAndQueryRepairRecordArtifactsByTicketId() {
        InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
        RepairRecord record = repository.create(new CreateRepairRecordCommand(
                "FS-1001",
                "https://ticket.example/FS-1001",
                "OrderService.create 500",
                Map.of("knowledgeBaseId", "1780000000000")
        ));

        repository.updateStatus(record.id(), RepairRecordStatus.RAG_READY, "检索到 3 个上下文块");
        repository.addArtifact(new CreateRepairRecordArtifactCommand(
                record.id(),
                "prompt-snapshot",
                "s3://rd-bot/repair/FS-1001/prompt.json",
                "RAG prompt snapshot"
        ));

        RepairRecord found = repository.findByTicketId("FS-1001").orElseThrow();
        List<RepairRecordArtifact> artifacts = repository.listArtifacts(record.id());

        assertEquals(record.id(), found.id());
        assertEquals(RepairRecordStatus.RAG_READY, found.status());
        assertFalse(artifacts.isEmpty());
        assertEquals("prompt-snapshot", artifacts.getFirst().artifactType());
    }

    @Test
    void shouldPageAndFilterByStatusPriorityAndTicketId() {
        InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
        repository.create(new CreateRepairRecordCommand("FS-1", "", "t1", Map.of("priority", "P0")));
        repository.create(new CreateRepairRecordCommand("FS-2", "", "t2", Map.of("priority", "P1")));
        RepairRecord target = repository.create(new CreateRepairRecordCommand(
                "FS-3", "", "t3", Map.of("priority", "P0")
        ));
        repository.updateStatus(target.id(), RepairRecordStatus.CONTEXT_READY, "ready");

        // 仅按 status 过滤
        RepairRecordPage byStatus = repository.query(new RepairRecordQuery(
                "", "CONTEXT_READY", "", 0L, 0L, 1, 20
        ));
        assertEquals(1, byStatus.total());
        assertEquals("FS-3", byStatus.records().getFirst().ticketId());

        // 按 priority 过滤
        RepairRecordPage byPriority = repository.query(new RepairRecordQuery(
                "", "", "P0", 0L, 0L, 1, 20
        ));
        assertEquals(2, byPriority.total());

        // 按 ticketId 过滤
        RepairRecordPage byTicket = repository.query(new RepairRecordQuery(
                "FS-2", "", "", 0L, 0L, 1, 20
        ));
        assertEquals(1, byTicket.total());

        // 分页：pageSize=2，page=1 返回 2 条，total=3
        RepairRecordPage page1 = repository.query(RepairRecordQuery.empty());
        RepairRecordPage paged = repository.query(new RepairRecordQuery(
                "", "", "", 0L, 0L, 1, 2
        ));
        assertEquals(3, paged.total());
        assertEquals(2, paged.records().size());
        assertEquals(3, page1.records().size(), "default page size covers all");
    }

    @Test
    void emptyQueryReturnsEmptyPageWhenNoMatches() {
        InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
        RepairRecordPage page = repository.query(new RepairRecordQuery(
                "missing", "", "", 0L, 0L, 1, 10
        ));
        assertTrue(page.records().isEmpty());
        assertEquals(0, page.total());
    }

    @Test
    void shouldUpdateExecutionMetadataFieldsIndependently() {
        InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
        RepairRecord record = repository.create(new CreateRepairRecordCommand(
                "FS-2001",
                "",
                "execution metadata",
                Map.of()
        ));
        repository.addArtifact(new CreateRepairRecordArtifactCommand(
                record.id(),
                "test-log",
                "file:///tmp/test.log",
                "large execution log"
        ));

        repository.updateExecutorJson(record.id(), "{\"status\":\"SUCCESS\"}");
        repository.updateDockerJson(record.id(), "{\"image\":\"rd-bot/claude-code:local\"}");
        repository.updateGithubJson(record.id(), "{\"pullRequestUrl\":\"https://github.com/acme/app/pull/1\"}");
        repository.updateTestJson(record.id(), "{\"testStatus\":\"PASSED\"}");
        repository.updateRiskJson(record.id(), "{\"riskLevel\":\"LOW\"}");
        repository.updateErrorMessage(record.id(), "validation requires manual review");

        RepairRecord updated = repository.findById(record.id()).orElseThrow();

        assertAll(
                () -> assertEquals("{\"status\":\"SUCCESS\"}", updated.executorJson()),
                () -> assertEquals("{\"image\":\"rd-bot/claude-code:local\"}", updated.dockerJson()),
                () -> assertEquals("{\"pullRequestUrl\":\"https://github.com/acme/app/pull/1\"}",
                        updated.githubJson()),
                () -> assertEquals("{\"testStatus\":\"PASSED\"}", updated.testJson()),
                () -> assertEquals("{\"riskLevel\":\"LOW\"}", updated.riskJson()),
                () -> assertEquals("validation requires manual review", updated.errorMessage()),
                () -> assertEquals(1, repository.listArtifacts(record.id()).size())
        );
    }

    @Test
    void shouldRejectInvalidJsonMetadataWithoutMutatingStoredRecord() {
        InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
        RepairRecord record = repository.create(new CreateRepairRecordCommand("FS-2002", "", "invalid json", Map.of()));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateExecutorJson(record.id(), "{bad json")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateDockerJson(record.id(), "{bad json")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateGithubJson(record.id(), "{bad json")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateTestJson(record.id(), "{bad json")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateRiskJson(record.id(), "{bad json"))
        );

        RepairRecord stored = repository.findById(record.id()).orElseThrow();
        assertAll(
                () -> assertEquals("{}", stored.executorJson()),
                () -> assertEquals("{}", stored.dockerJson()),
                () -> assertEquals("{}", stored.githubJson()),
                () -> assertEquals("{}", stored.testJson()),
                () -> assertEquals("{}", stored.riskJson())
        );
    }

    @Test
    void shouldRejectTrailingJsonTokensWithoutMutatingStoredRecord() {
        InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
        RepairRecord record = repository.create(new CreateRepairRecordCommand("FS-2003", "", "trailing json", Map.of()));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateExecutorJson(record.id(), "{} {}")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateDockerJson(record.id(), "{} trailing")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateGithubJson(record.id(), "{} {}")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateTestJson(record.id(), "{} trailing")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.updateRiskJson(record.id(), "{} {}"))
        );

        RepairRecord stored = repository.findById(record.id()).orElseThrow();
        assertAll(
                () -> assertEquals("{}", stored.executorJson()),
                () -> assertEquals("{}", stored.dockerJson()),
                () -> assertEquals("{}", stored.githubJson()),
                () -> assertEquals("{}", stored.testJson()),
                () -> assertEquals("{}", stored.riskJson())
        );
    }

    private SnowflakeIdGenerator generatorWithMovingClock() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
