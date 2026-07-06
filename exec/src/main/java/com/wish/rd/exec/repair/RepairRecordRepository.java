package com.wish.rd.exec.repair;

import java.util.List;
import java.util.Optional;
import com.wish.rd.exec.repair.model.CreateRepairAssetCommand;
import com.wish.rd.exec.repair.model.CreateRepairRecordArtifactCommand;
import com.wish.rd.exec.repair.model.CreateRepairRecordCommand;
import com.wish.rd.exec.repair.model.RepairAsset;
import com.wish.rd.exec.repair.model.RepairRecord;
import com.wish.rd.exec.repair.model.RepairRecordArtifact;
import com.wish.rd.exec.repair.model.RepairRecordPage;
import com.wish.rd.exec.repair.model.RepairRecordQuery;
import com.wish.rd.exec.repair.model.RepairRecordStatus;

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

    RepairAsset addAsset(CreateRepairAssetCommand command);

    List<RepairAsset> listAssets(String repairRecordId);
}
