package com.wish.rd.exec.repair;

import java.util.List;
import java.util.Optional;

/**
 * 修复记录仓储端口。
 */
public interface RepairRecordRepository {

    RepairRecord create(CreateRepairRecordCommand command);

    RepairRecord updateStatus(String repairRecordId, RepairRecordStatus status, String ragSummary);

    Optional<RepairRecord> findById(String repairRecordId);

    Optional<RepairRecord> findByTicketId(String ticketId);

    RepairRecordArtifact addArtifact(CreateRepairRecordArtifactCommand command);

    List<RepairRecordArtifact> listArtifacts(String repairRecordId);
}
