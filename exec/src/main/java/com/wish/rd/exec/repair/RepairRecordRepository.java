package com.wish.rd.exec.repair;

import java.util.List;
import java.util.Optional;

/**
 * 修复记录仓储端口。
 */
public interface RepairRecordRepository {

    RepairRecord create(CreateRepairRecordCommand command);

    RepairRecord updateStatus(String repairRecordId, RepairRecordStatus status, String ragSummary);

    RepairRecord updateExecutorJson(String repairRecordId, String executorJson);

    RepairRecord updateDockerJson(String repairRecordId, String dockerJson);

    RepairRecord updateGithubJson(String repairRecordId, String githubJson);

    RepairRecord updateTestJson(String repairRecordId, String testJson);

    RepairRecord updateRiskJson(String repairRecordId, String riskJson);

    RepairRecord updateErrorMessage(String repairRecordId, String errorMessage);

    Optional<RepairRecord> findById(String repairRecordId);

    Optional<RepairRecord> findByTicketId(String ticketId);

    /**
     * 按条件分页查询修复记录。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    RepairRecordPage query(RepairRecordQuery query);

    RepairRecordArtifact addArtifact(CreateRepairRecordArtifactCommand command);

    List<RepairRecordArtifact> listArtifacts(String repairRecordId);
}
