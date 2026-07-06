package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.ticket.model.CreateRepairRecordArtifactCommand;
import com.wish.rd.engine.ticket.model.CreateRepairRecordCommand;
import com.wish.rd.engine.ticket.model.RepairRecord;
import com.wish.rd.engine.ticket.model.RepairRecordArtifact;
import com.wish.rd.engine.ticket.model.RepairRecordStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 将 engine 工单修复记录端口适配到 exec 修复记录仓储。
 */
@Component
@ConditionalOnBean(com.wish.rd.exec.repair.RepairRecordRepository.class)
public class ExecRepairRecordRepositoryAdapter implements com.wish.rd.engine.ticket.RepairRecordRepository {

    private final com.wish.rd.exec.repair.RepairRecordRepository delegate;

    public ExecRepairRecordRepositoryAdapter(com.wish.rd.exec.repair.RepairRecordRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public RepairRecord create(CreateRepairRecordCommand command) {
        return toEngine(delegate.create(new com.wish.rd.exec.repair.model.CreateRepairRecordCommand(
                command.ticketId(),
                command.ticketUrl(),
                command.title(),
                command.extensionJson()
        )));
    }

    @Override
    public RepairRecord updateStatus(String repairRecordId, RepairRecordStatus status, String ragSummary) {
        return toEngine(delegate.updateStatus(
                repairRecordId,
                toExec(status),
                ragSummary
        ));
    }

    @Override
    public Optional<RepairRecord> findById(String repairRecordId) {
        return delegate.findById(repairRecordId).map(this::toEngine);
    }

    @Override
    public Optional<RepairRecord> findByTicketId(String ticketId) {
        return delegate.findByTicketId(ticketId).map(this::toEngine);
    }

    @Override
    public RepairRecordArtifact addArtifact(CreateRepairRecordArtifactCommand command) {
        return toEngine(delegate.addArtifact(new com.wish.rd.exec.repair.model.CreateRepairRecordArtifactCommand(
                command.repairRecordId(),
                command.artifactType(),
                command.artifactUri(),
                command.summary()
        )));
    }

    @Override
    public List<RepairRecordArtifact> listArtifacts(String repairRecordId) {
        return delegate.listArtifacts(repairRecordId).stream()
                .map(this::toEngine)
                .toList();
    }

    private RepairRecord toEngine(com.wish.rd.exec.repair.model.RepairRecord record) {
        return new RepairRecord(
                record.id(),
                record.ticketId(),
                record.ticketUrl(),
                record.title(),
                toEngine(record.status()),
                record.ragSummary(),
                record.extensionJson(),
                record.createdAtEpochMillis(),
                record.updatedAtEpochMillis()
        );
    }

    private RepairRecordArtifact toEngine(com.wish.rd.exec.repair.model.RepairRecordArtifact artifact) {
        return new RepairRecordArtifact(
                artifact.id(),
                artifact.repairRecordId(),
                artifact.artifactType(),
                artifact.artifactUri(),
                artifact.summary(),
                artifact.createdAtEpochMillis()
        );
    }

    private RepairRecordStatus toEngine(com.wish.rd.exec.repair.model.RepairRecordStatus status) {
        return RepairRecordStatus.valueOf(status.name());
    }

    private com.wish.rd.exec.repair.model.RepairRecordStatus toExec(RepairRecordStatus status) {
        RepairRecordStatus safeStatus = status == null ? RepairRecordStatus.CREATED : status;
        return com.wish.rd.exec.repair.model.RepairRecordStatus.valueOf(safeStatus.name());
    }
}
