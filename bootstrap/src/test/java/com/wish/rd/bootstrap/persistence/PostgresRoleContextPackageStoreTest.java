package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresRoleContextPackageStore;

import com.wish.rd.bootstrap.persistence.entity.RdRoleContextPackageRow;
import com.wish.rd.bootstrap.persistence.mapper.RdRoleContextPackageMapper;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRoleContextPackageStoreTest {

    private final RdRoleContextPackageMapper mapper = mock(RdRoleContextPackageMapper.class);
    private final PostgresRoleContextPackageStore store = new PostgresRoleContextPackageStore(
            mapper,
            new com.fasterxml.jackson.databind.ObjectMapper()
    );

    @Test
    void shouldSaveFindAndListRoleContextPackages() {
        RoleContextPackage context = context("7478000000000001001", "7478000000000000000", "QA_AGENT");
        when(mapper.selectById(7478000000000001001L)).thenReturn(row(context));
        when(mapper.selectList(any())).thenReturn(List.of(row(context)));

        store.save(context);

        assertEquals(context, store.findById(context.packageId()).orElseThrow());
        assertEquals(List.of(context), store.listByTask(context.taskId()));
        assertEquals(List.of(context), store.listByTaskAndRole(context.taskId(), "qa_agent"));
        verify(mapper).upsertContextPackage(any(RdRoleContextPackageRow.class));
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
                        "https://example.test/mat-1",
                        "需求材料",
                        "sha256:mat-1",
                        "证据摘要",
                        1_783_000_000_000L
                )),
                List.of("真实验收通过"),
                List.of("不得泄露密钥"),
                8_000,
                64,
                List.of("mat-2"),
                1_783_000_000_000L
        );
    }

    private RdRoleContextPackageRow row(RoleContextPackage context) {
        RdRoleContextPackageRow row = new RdRoleContextPackageRow();
        row.id = Long.parseLong(context.packageId());
        row.taskId = Long.parseLong(context.taskId());
        row.role = context.role();
        row.packageVersion = context.packageVersion();
        row.evidenceJson = """
                [{"evidenceId":"mat-1","sourceType":"MANUAL_TEXT","sourceUri":"https://example.test/mat-1","title":"需求材料","contentHash":"sha256:mat-1","summary":"证据摘要","collectedAtEpochMillis":1783000000000}]
                """;
        row.acceptanceJson = "[\"真实验收通过\"]";
        row.riskHintsJson = "[\"不得泄露密钥\"]";
        row.contextBudgetJson = "{\"maxChars\":8000,\"usedChars\":64}";
        row.omittedEvidenceJson = "[\"mat-2\"]";
        row.contentHash = "sha256:ctx";
        row.createdAt = PostgresPersistenceSupport.toDateTime(context.createdAtEpochMillis());
        return row;
    }
}
