package com.wish.rd.exec.repair;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private SnowflakeIdGenerator generatorWithMovingClock() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
