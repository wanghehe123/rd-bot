package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditRunRow;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditedStateHeadRow;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditedStateRevisionRow;
import com.wish.rd.bootstrap.persistence.entity.TaskCompletionBindingRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskAuditedStateMapper;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.audit.CompletionBinding;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL audited-state store with revision CAS.
 *
 * <p>Must not be {@code final}: {@code @Transactional} uses CGLIB subclassing.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresAuditedTaskStateStore implements AuditedTaskStateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final TaskAuditedStateMapper mapper;
    private final AuditedTaskStateCodec codec;

    @Autowired
    public PostgresAuditedTaskStateStore(TaskAuditedStateMapper mapper) {
        this(mapper, new AuditedTaskStateCodec());
    }

    PostgresAuditedTaskStateStore(TaskAuditedStateMapper mapper, AuditedTaskStateCodec codec) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper must not be null");
        this.codec = codec == null ? new AuditedTaskStateCodec() : codec;
    }

    @Override
    public Optional<AuditedTaskState> head(String taskId) {
        long id = PostgresPersistenceSupport.parseId(taskId);
        TaskAuditedStateHeadRow head = mapper.findHead(id);
        if (head == null) {
            return Optional.empty();
        }
        TaskAuditedStateRevisionRow revision = mapper.findRevision(id, head.stateVersion);
        if (revision == null) {
            throw new IllegalStateException("audited state head is missing its revision: " + taskId);
        }
        return Optional.of(codec.decode(revision.stateJson));
    }

    @Override
    @Transactional
    public AuditedTaskState initializeIfAbsent(AuditedTaskState initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial state must not be null");
        }
        if (initial.stateVersion() != 1L) {
            throw new IllegalArgumentException("initializeIfAbsent requires state_version=1");
        }
        long taskId = PostgresPersistenceSupport.parseId(initial.taskId());
        TaskAuditedStateHeadRow head = mapper.findHead(taskId);
        if (head != null) {
            AuditedTaskState current = head(initial.taskId()).orElseThrow();
            if (!current.contractRef().acceptanceCriteriaHash()
                    .equals(initial.contractRef().acceptanceCriteriaHash())) {
                throw new IllegalStateException("audited contractRef mismatch: " + initial.taskId());
            }
            return current;
        }
        OffsetDateTime now = OffsetDateTime.now();
        TaskAuditedStateRevisionRow revision = new TaskAuditedStateRevisionRow();
        revision.taskId = taskId;
        revision.stateVersion = 1L;
        revision.stateHash = initial.stateHash();
        revision.stateJson = codec.encodeCanonical(initial);
        revision.auditRunId = "";
        revision.commandId = 0L;
        revision.createdAt = now;
        if (mapper.insertRevisionIfAbsent(revision) != 1) {
            return initializeIfAbsent(initial);
        }
        TaskAuditedStateHeadRow created = new TaskAuditedStateHeadRow();
        created.taskId = taskId;
        created.stateVersion = 1L;
        created.stateHash = initial.stateHash();
        created.lastAuditRunId = initial.lastAuditRunId();
        created.updatedAt = now;
        if (mapper.insertHeadIfAbsent(created) != 1) {
            return initializeIfAbsent(initial);
        }
        return initial;
    }

    @Override
    @Transactional
    public AuditedTaskState appendRevision(
            long expectedStateVersion,
            AuditedTaskState next,
            AuditRun auditRun
    ) {
        if (next == null) {
            throw new IllegalArgumentException("next state must not be null");
        }
        if (auditRun == null) {
            throw new IllegalArgumentException("audit run must not be null");
        }
        if (!next.taskId().equals(auditRun.taskId())) {
            throw new IllegalArgumentException("audit run taskId must match state taskId");
        }
        if (expectedStateVersion != next.stateVersion()) {
            throw new IllegalStateException(
                    "expectedStateVersion mismatch: expected " + expectedStateVersion
                            + " but next was " + next.stateVersion());
        }
        long taskId = PostgresPersistenceSupport.parseId(next.taskId());
        long commandId = PostgresPersistenceSupport.parseId(auditRun.commandId());
        TaskAuditedStateRevisionRow existingRevision = mapper.findRevision(taskId, next.stateVersion());
        if (existingRevision != null) {
            if (existingRevision.stateHash.equals(next.stateHash())) {
                rememberRun(auditRun, taskId, commandId);
                return head(next.taskId()).orElseThrow(
                        () -> new IllegalStateException("audited state head missing after idempotent replay: "
                                + next.taskId()));
            }
            throw new IllegalStateException(
                    "same version has a different hash: " + next.taskId() + "#" + next.stateVersion());
        }
        TaskAuditedStateHeadRow head = mapper.findHead(taskId);
        if (next.stateVersion() == 1L) {
            if (head != null) {
                throw new IllegalStateException("expectedStateVersion mismatch: head already exists for "
                        + next.taskId());
            }
        } else if (head == null || head.stateVersion != next.stateVersion() - 1L) {
            throw new IllegalStateException(
                    "expectedStateVersion mismatch: cannot write " + next.stateVersion()
                            + " when head is " + (head == null ? "absent" : head.stateVersion));
        }
        rememberRun(auditRun, taskId, commandId);
        OffsetDateTime now = PostgresPersistenceSupport.toDateTime(Math.max(0L, auditRun.createdAtEpochMillis()));
        TaskAuditedStateRevisionRow revision = new TaskAuditedStateRevisionRow();
        revision.taskId = taskId;
        revision.stateVersion = next.stateVersion();
        revision.stateHash = next.stateHash();
        revision.stateJson = codec.encodeCanonical(next);
        revision.auditRunId = auditRun.auditRunId();
        revision.commandId = commandId;
        revision.createdAt = now;
        if (mapper.insertRevisionIfAbsent(revision) != 1) {
            TaskAuditedStateRevisionRow raced = mapper.findRevision(taskId, next.stateVersion());
            if (raced != null && raced.stateHash.equals(next.stateHash())) {
                return head(next.taskId()).orElseThrow();
            }
            throw new IllegalStateException(
                    "same version has a different hash: " + next.taskId() + "#" + next.stateVersion());
        }
        if (next.stateVersion() == 1L) {
            TaskAuditedStateHeadRow created = new TaskAuditedStateHeadRow();
            created.taskId = taskId;
            created.stateVersion = next.stateVersion();
            created.stateHash = next.stateHash();
            created.lastAuditRunId = next.lastAuditRunId();
            created.updatedAt = now;
            if (mapper.insertHeadIfAbsent(created) != 1) {
                throw new IllegalStateException("expectedStateVersion mismatch: head already exists for "
                        + next.taskId());
            }
        } else if (mapper.casHead(
                taskId,
                next.stateVersion() - 1L,
                next.stateVersion(),
                next.stateHash(),
                next.lastAuditRunId(),
                now
        ) != 1) {
            throw new IllegalStateException(
                    "expectedStateVersion mismatch: cannot write " + next.stateVersion()
                            + " when head is " + (head == null ? "absent" : head.stateVersion));
        }
        return next;
    }

    @Override
    public List<AuditRun> listAuditRuns(String taskId) {
        return mapper.listRunsByTaskId(PostgresPersistenceSupport.parseId(taskId)).stream()
                .map(this::toAuditRun)
                .toList();
    }

    @Override
    @Transactional
    public void bindCompletion(String taskId, String auditRunId, long stateVersion, String stateHash) {
        CompletionBinding binding = new CompletionBinding(taskId, auditRunId, stateVersion, stateHash);
        AuditedTaskState current = head(binding.taskId()).orElseThrow(
                () -> new IllegalStateException("completion binding does not match audited head: "
                        + binding.taskId()));
        if (current.stateVersion() != binding.stateVersion()
                || !current.stateHash().equals(binding.stateHash())) {
            throw new IllegalStateException("completion binding does not match audited head: " + binding.taskId());
        }
        boolean knownRun = listAuditRuns(binding.taskId()).stream()
                .anyMatch(run -> run.auditRunId().equals(binding.auditRunId()));
        if (!knownRun) {
            throw new IllegalStateException("completion binding audit run is unknown: " + binding.auditRunId());
        }
        TaskCompletionBindingRow existing = mapper.findBinding(PostgresPersistenceSupport.parseId(binding.taskId()));
        if (existing != null) {
            CompletionBinding stored = new CompletionBinding(
                    PostgresPersistenceSupport.idString(existing.taskId),
                    existing.auditRunId,
                    existing.stateVersion,
                    existing.stateHash);
            if (!stored.equals(binding)) {
                throw new IllegalStateException("completion binding already exists for " + binding.taskId());
            }
            return;
        }
        TaskCompletionBindingRow row = new TaskCompletionBindingRow();
        row.taskId = PostgresPersistenceSupport.parseId(binding.taskId());
        row.auditRunId = binding.auditRunId();
        row.stateVersion = binding.stateVersion();
        row.stateHash = binding.stateHash();
        row.boundAt = OffsetDateTime.now();
        if (mapper.insertBindingIfAbsent(row) != 1) {
            TaskCompletionBindingRow raced = mapper.findBinding(row.taskId);
            if (raced == null) {
                throw new IllegalStateException("completion binding insert failed for " + binding.taskId());
            }
            CompletionBinding stored = new CompletionBinding(
                    PostgresPersistenceSupport.idString(raced.taskId),
                    raced.auditRunId,
                    raced.stateVersion,
                    raced.stateHash);
            if (!stored.equals(binding)) {
                throw new IllegalStateException("completion binding already exists for " + binding.taskId());
            }
        }
    }

    @Override
    public Optional<CompletionBinding> completionBinding(String taskId) {
        TaskCompletionBindingRow row = mapper.findBinding(PostgresPersistenceSupport.parseId(taskId));
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new CompletionBinding(
                PostgresPersistenceSupport.idString(row.taskId),
                row.auditRunId,
                row.stateVersion,
                row.stateHash));
    }

    private void rememberRun(AuditRun auditRun, long taskId, long commandId) {
        TaskAuditRunRow existing = mapper.findRunByCommandId(commandId);
        if (existing != null) {
            if (!existing.auditRunId.equals(auditRun.auditRunId())) {
                throw new IllegalStateException("duplicate audit run commandId: " + auditRun.commandId());
            }
            return;
        }
        TaskAuditRunRow row = toRunRow(auditRun, taskId, commandId);
        if (mapper.insertRunIfAbsent(row) != 1) {
            TaskAuditRunRow raced = mapper.findRunByCommandId(commandId);
            if (raced == null || !raced.auditRunId.equals(auditRun.auditRunId())) {
                throw new IllegalStateException("duplicate audit run commandId: " + auditRun.commandId());
            }
        }
    }

    private TaskAuditRunRow toRunRow(AuditRun auditRun, long taskId, long commandId) {
        String reportJson = encodeRun(auditRun);
        TaskAuditRunRow row = new TaskAuditRunRow();
        row.auditRunId = auditRun.auditRunId();
        row.taskId = taskId;
        row.subjectStageRunId = auditRun.subjectStageRunId();
        row.subjectRole = auditRun.subjectRole();
        row.commandId = commandId;
        row.completion = auditRun.completion().name();
        row.integrity = auditRun.integrity().name();
        row.contractAudit = auditRun.contractAudit().name();
        row.reportJson = reportJson;
        row.reportHash = CanonicalJsonSha256.digest(reportJson);
        row.createdAt = PostgresPersistenceSupport.toDateTime(auditRun.createdAtEpochMillis());
        return row;
    }

    private AuditRun toAuditRun(TaskAuditRunRow row) {
        try {
            return MAPPER.readValue(CanonicalJsonSha256.canonicalize(row.reportJson), AuditRun.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("invalid audit run JSON: " + row.auditRunId, invalid);
        }
    }

    private static String encodeRun(AuditRun auditRun) {
        try {
            return CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(auditRun));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("audit run cannot be encoded", invalid);
        }
    }
}
