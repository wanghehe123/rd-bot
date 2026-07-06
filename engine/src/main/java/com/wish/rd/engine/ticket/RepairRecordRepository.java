package com.wish.rd.engine.ticket;

import java.util.List;
import java.util.Optional;
import com.wish.rd.engine.ticket.model.CreateRepairRecordArtifactCommand;
import com.wish.rd.engine.ticket.model.CreateRepairRecordCommand;
import com.wish.rd.engine.ticket.model.RepairRecord;
import com.wish.rd.engine.ticket.model.RepairRecordArtifact;
import com.wish.rd.engine.ticket.model.RepairRecordStatus;

/**
 * 工单修复记录仓储端口。
 *
 * <p>engine 只依赖该编排端口；exec/bootstrap 负责把它适配到真实修复记录持久化。
 */
public interface RepairRecordRepository {

    /**
     * 创建修复记录。
     *
     * @param command 创建命令
     * @return 创建后的记录
     */
    RepairRecord create(CreateRepairRecordCommand command);

    /**
     * 更新修复记录状态。
     *
     * @param repairRecordId 修复记录 ID
     * @param status         新状态
     * @param ragSummary     RAG 摘要
     * @return 更新后的记录
     */
    RepairRecord updateStatus(String repairRecordId, RepairRecordStatus status, String ragSummary);

    /**
     * 按 ID 查询记录。
     *
     * @param repairRecordId 修复记录 ID
     * @return 记录
     */
    Optional<RepairRecord> findById(String repairRecordId);

    /**
     * 按工单 ID 查询记录。
     *
     * @param ticketId 工单 ID
     * @return 记录
     */
    Optional<RepairRecord> findByTicketId(String ticketId);

    /**
     * 新增修复记录产物。
     *
     * @param command 创建产物命令
     * @return 创建后的产物
     */
    RepairRecordArtifact addArtifact(CreateRepairRecordArtifactCommand command);

    /**
     * 查询修复记录产物。
     *
     * @param repairRecordId 修复记录 ID
     * @return 产物列表
     */
    List<RepairRecordArtifact> listArtifacts(String repairRecordId);
}
