package com.wish.rd.rag.project.alert;

import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;

import java.util.Optional;

/** 项目告警配置持久化端口。 */
public interface RdProjectAlertConfigStore {

    /**
     * 保存配置。
     *
     * @param config 配置快照
     * @return 保存后的配置
     */
    RdProjectAlertConfig save(RdProjectAlertConfig config);

    /**
     * 按项目查询配置。
     *
     * @param projectId 项目 ID
     * @return 配置
     */
    Optional<RdProjectAlertConfig> findByProjectId(String projectId);
}
