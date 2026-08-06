package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagStreamTaskRegistryCancelCasTest {

    @Test
    void cancelUsesStoreCasAndRejectsStaleExpectedStatus() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "cancel-cas", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");

        RdTask cancelled = registry.cancelTask(created.taskId(), "operator stop");
        assertEquals(RdTaskStatus.CANCELLED, cancelled.status());
        // create+material still blind-save at version 0; cancel is the first CAS bump → 1
        assertEquals(1L, store.findVersion(created.taskId()).orElseThrow());

        // Stale writer still believes task is MATERIAL_COLLECTING at version 0.
        assertThrows(IllegalStateException.class, () -> store.advanceStatusWithExpectedVersion(
                created.taskId(),
                0L,
                RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.COMPLETED,
                "late worker"
        ));
        assertEquals(RdTaskStatus.CANCELLED, registry.getTask(created.taskId()).status());
    }
}
