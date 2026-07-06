package com.wish.rd.rag.context;

import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;

class RoleContextPackageStoreTest {

    @Test
    void shouldSaveFindAndListContextPackagesByTaskAndRole() {
        RoleContextPackageStore store = new InMemoryRoleContextPackageStore();
        RoleContextPackage reviewer = context("ctx-1", "task-1001", "REQUIREMENT_REVIEWER");
        RoleContextPackage qa = context("ctx-2", "task-1001", "QA_AGENT");

        store.save(reviewer);
        store.save(qa);

        assertEquals(reviewer, store.findById("ctx-1").orElseThrow());
        assertEquals(List.of(reviewer, qa), store.listByTask("task-1001"));
        assertEquals(List.of(qa), store.listByTaskAndRole("task-1001", "qa_agent"));
        assertTrue(store.listByTask("missing").isEmpty());
    }

    private RoleContextPackage context(String packageId, String taskId, String role) {
        return new RoleContextPackage(
                packageId,
                taskId,
                role,
                1,
                List.of(new RoleContextEvidence(
                        "mat-1",
                        "MANUAL_TEXT",
                        "",
                        "需求材料",
                        "sha256:mat-1",
                        "证据摘要",
                        1_783_000_000_000L
                )),
                List.of("验收通过"),
                List.of(),
                8_000,
                12,
                List.of(),
                1_783_000_000_000L
        );
    }
}
