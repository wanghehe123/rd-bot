package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.TaskAuditRunRow;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditedStateHeadRow;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditedStateRevisionRow;
import com.wish.rd.bootstrap.persistence.entity.TaskCompletionBindingRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskAuditedStateMapper;
import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.audit.CompletionBinding;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mapper-backed PostgreSQL audited-state store tests (no live database). */
class PostgresAuditedTaskStateStoreTest {

    @Test
    void storeIsTransactionalAndNotAFinalClass() throws Exception {
        assertFalse(Modifier.isFinal(PostgresAuditedTaskStateStore.class.getModifiers()));
        assertNotNull(PostgresAuditedTaskStateStore.class.getMethod(
                "appendRevision", long.class, AuditedTaskState.class, AuditRun.class)
                .getAnnotation(Transactional.class));
        String source = Files.readString(Path.of(
                "src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresAuditedTaskStateStore.java"));
        assertFalse(source.contains("LinkedHashMap"),
                "PostgreSQL store must not keep LinkedHashMap as a source of truth");
    }

    @Test
    void mapperSqlUsesUniqueAndHeadCasPredicates() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskAuditedStateMapper.java"));
        assertTrue(mapper.contains("ON CONFLICT (task_id, state_version) DO NOTHING"));
        assertTrue(mapper.contains("ON CONFLICT (command_id) DO NOTHING"));
        assertTrue(mapper.contains("AND state_version = #{expectedPreviousVersion}"));
        assertTrue(mapper.contains("ON CONFLICT (task_id) DO NOTHING"));
    }

    @Test
    void ddlDefinesCascadingTablesAndHostVerifyFix() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/sql/postgres/p20_task_audited_state.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_audited_state_heads"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_audited_state_revisions"));
        assertTrue(sql.contains("UNIQUE (task_id, state_version)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_audit_runs"));
        assertTrue(sql.contains("UNIQUE (command_id)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_completion_bindings"));
        assertTrue(sql.contains("REFERENCES rd_tasks(id) ON DELETE CASCADE"));
        assertTrue(sql.contains("HOST_VERIFY_FIX"));
        assertTrue(sql.contains("remediation_no BETWEEN 1 AND 2"));
    }

    @Test
    void appendsHeadRevisionAndListsAuditRuns() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        AuditRun run = auditRun("2001", first);
        AuditedTaskState stored = store.appendRevision(1L, first, run);
        assertEquals(first.stateHash(), stored.stateHash());
        assertEquals(first, store.head("1001").orElseThrow());
        assertEquals(List.of(run.auditRunId()), store.listAuditRuns("1001").stream()
                .map(AuditRun::auditRunId)
                .toList());
    }

    @Test
    void sameVersionSameHashIsIdempotent() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        AuditRun run = auditRun("2001", first);
        store.appendRevision(1L, first, run);
        AuditedTaskState replay = store.appendRevision(1L, first, run);
        assertEquals(first.stateHash(), replay.stateHash());
        assertEquals(1, store.listAuditRuns("1001").size());
    }

    @Test
    void sameVersionDifferentHashIsRejected() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        store.appendRevision(1L, first, auditRun("2001", first));
        AuditedTaskState conflict = sealed(1L, "AC-002");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.appendRevision(1L, conflict, auditRun("2002", conflict)));
        assertTrue(failure.getMessage().toLowerCase().contains("hash"));
        assertEquals(first.stateHash(), store.head("1001").orElseThrow().stateHash());
    }

    @Test
    void expectedVersionMismatchIsRejected() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        store.appendRevision(1L, first, auditRun("2001", first));
        AuditedTaskState second = sealed(2L, "AC-001");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.appendRevision(1L, second, auditRun("2002", second)));
        assertTrue(failure.getMessage().toLowerCase().contains("expected"));
        assertEquals(1L, store.head("1001").orElseThrow().stateVersion());
    }

    @Test
    void bindCompletionIsIdempotentForSameBinding() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        AuditRun run = auditRun("2001", first);
        store.appendRevision(1L, first, run);
        store.bindCompletion("1001", run.auditRunId(), first.stateVersion(), first.stateHash());
        store.bindCompletion("1001", run.auditRunId(), first.stateVersion(), first.stateHash());
        CompletionBinding binding = store.completionBinding("1001").orElseThrow();
        assertEquals(run.auditRunId(), binding.auditRunId());
        assertEquals(first.stateHash(), binding.stateHash());
        AuditedTaskState other = sealed(1L, "AC-009");
        assertThrows(IllegalStateException.class, () -> store.bindCompletion(
                "1001", run.auditRunId(), other.stateVersion(), other.stateHash()));
    }

    private static AuditedTaskStateStore newStore() {
        return new PostgresAuditedTaskStateStore(new FakeTaskAuditedStateMapper());
    }

    private static AuditedTaskState sealed(long version, String requirementId) {
        return new AuditedTaskStateCodec().seal(new AuditedTaskState(
                "1001",
                version,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        requirementId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + requirementId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                ""));
    }

    private static AuditRun auditRun(String commandId, AuditedTaskState state) {
        return new AuditRun(
                "audit-" + commandId,
                state.taskId(),
                "stage-1",
                "HOST_VERIFY",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(state.records().getFirst().id()),
                List.of(),
                List.of(),
                List.of(),
                1_700_000_000_000L
        );
    }

    private static final class FakeTaskAuditedStateMapper implements TaskAuditedStateMapper {
        private final Map<Long, TaskAuditedStateHeadRow> heads = new LinkedHashMap<>();
        private final Map<String, TaskAuditedStateRevisionRow> revisions = new LinkedHashMap<>();
        private final Map<Long, TaskAuditRunRow> runsByCommand = new LinkedHashMap<>();
        private final Map<String, TaskAuditRunRow> runsById = new LinkedHashMap<>();
        private final Map<Long, TaskCompletionBindingRow> bindings = new LinkedHashMap<>();

        @Override
        public TaskAuditedStateHeadRow findHead(long taskId) {
            return copyHead(heads.get(taskId));
        }

        @Override
        public int insertHeadIfAbsent(TaskAuditedStateHeadRow row) {
            if (heads.containsKey(row.taskId)) {
                return 0;
            }
            heads.put(row.taskId, copyHead(row));
            return 1;
        }

        @Override
        public int casHead(
                long taskId,
                long expectedPreviousVersion,
                long nextVersion,
                String stateHash,
                String lastAuditRunId,
                OffsetDateTime updatedAt
        ) {
            TaskAuditedStateHeadRow current = heads.get(taskId);
            if (current == null || current.stateVersion != expectedPreviousVersion) {
                return 0;
            }
            current.stateVersion = nextVersion;
            current.stateHash = stateHash;
            current.lastAuditRunId = lastAuditRunId;
            current.updatedAt = updatedAt;
            return 1;
        }

        @Override
        public TaskAuditedStateRevisionRow findRevision(long taskId, long stateVersion) {
            return copyRevision(revisions.get(taskId + ":" + stateVersion));
        }

        @Override
        public int insertRevisionIfAbsent(TaskAuditedStateRevisionRow row) {
            String key = row.taskId + ":" + row.stateVersion;
            if (revisions.containsKey(key)) {
                return 0;
            }
            revisions.put(key, copyRevision(row));
            return 1;
        }

        @Override
        public TaskAuditRunRow findRunByCommandId(long commandId) {
            return copyRun(runsByCommand.get(commandId));
        }

        @Override
        public List<TaskAuditRunRow> listRunsByTaskId(long taskId) {
            List<TaskAuditRunRow> rows = new ArrayList<>();
            for (TaskAuditRunRow row : runsById.values()) {
                if (Objects.equals(row.taskId, taskId)) {
                    rows.add(copyRun(row));
                }
            }
            return rows;
        }

        @Override
        public int insertRunIfAbsent(TaskAuditRunRow row) {
            if (runsByCommand.containsKey(row.commandId)) {
                return 0;
            }
            TaskAuditRunRow copy = copyRun(row);
            runsByCommand.put(row.commandId, copy);
            runsById.put(row.auditRunId, copy);
            return 1;
        }

        @Override
        public TaskCompletionBindingRow findBinding(long taskId) {
            TaskCompletionBindingRow row = bindings.get(taskId);
            if (row == null) {
                return null;
            }
            TaskCompletionBindingRow copy = new TaskCompletionBindingRow();
            copy.taskId = row.taskId;
            copy.auditRunId = row.auditRunId;
            copy.stateVersion = row.stateVersion;
            copy.stateHash = row.stateHash;
            copy.boundAt = row.boundAt;
            return copy;
        }

        @Override
        public int insertBindingIfAbsent(TaskCompletionBindingRow row) {
            if (bindings.containsKey(row.taskId)) {
                return 0;
            }
            TaskCompletionBindingRow copy = new TaskCompletionBindingRow();
            copy.taskId = row.taskId;
            copy.auditRunId = row.auditRunId;
            copy.stateVersion = row.stateVersion;
            copy.stateHash = row.stateHash;
            copy.boundAt = row.boundAt;
            bindings.put(row.taskId, copy);
            return 1;
        }

        private static TaskAuditedStateHeadRow copyHead(TaskAuditedStateHeadRow row) {
            if (row == null) {
                return null;
            }
            TaskAuditedStateHeadRow copy = new TaskAuditedStateHeadRow();
            copy.taskId = row.taskId;
            copy.stateVersion = row.stateVersion;
            copy.stateHash = row.stateHash;
            copy.lastAuditRunId = row.lastAuditRunId;
            copy.updatedAt = row.updatedAt;
            return copy;
        }

        private static TaskAuditedStateRevisionRow copyRevision(TaskAuditedStateRevisionRow row) {
            if (row == null) {
                return null;
            }
            TaskAuditedStateRevisionRow copy = new TaskAuditedStateRevisionRow();
            copy.taskId = row.taskId;
            copy.stateVersion = row.stateVersion;
            copy.stateHash = row.stateHash;
            copy.stateJson = row.stateJson;
            copy.auditRunId = row.auditRunId;
            copy.commandId = row.commandId;
            copy.createdAt = row.createdAt;
            return copy;
        }

        private static TaskAuditRunRow copyRun(TaskAuditRunRow row) {
            if (row == null) {
                return null;
            }
            TaskAuditRunRow copy = new TaskAuditRunRow();
            copy.auditRunId = row.auditRunId;
            copy.taskId = row.taskId;
            copy.subjectStageRunId = row.subjectStageRunId;
            copy.subjectRole = row.subjectRole;
            copy.commandId = row.commandId;
            copy.completion = row.completion;
            copy.integrity = row.integrity;
            copy.contractAudit = row.contractAudit;
            copy.reportJson = row.reportJson;
            copy.reportHash = row.reportHash;
            copy.createdAt = row.createdAt;
            return copy;
        }
    }
}
