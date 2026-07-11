package com.wish.rd.rag.project.alert;

import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfigCommand;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * 项目告警配置领域服务。
 *
 * <p>供项目管理 API 与告警路由读取 PostgreSQL 真值。
 */
public class RdProjectAlertConfigService {

    private final RdProjectAlertConfigStore store;
    private final Consumer<String> projectValidator;

    public RdProjectAlertConfigService(RdProjectAlertConfigStore store) {
        this(store, ignored -> { });
    }

    public RdProjectAlertConfigService(RdProjectAlertConfigStore store, Consumer<String> projectValidator) {
        this.store = java.util.Objects.requireNonNull(store, "alert config store must not be null");
        this.projectValidator = projectValidator == null ? ignored -> { } : projectValidator;
    }

    /**
     * 查询项目配置，不存在时返回未启用默认值。
     *
     * @param projectId 项目 ID
     * @return 项目配置
     */
    public RdProjectAlertConfig get(String projectId) {
        String safeProjectId = requireProjectId(projectId);
        projectValidator.accept(safeProjectId);
        return store.findByProjectId(safeProjectId).orElseGet(() -> new RdProjectAlertConfig(
                safeProjectId,
                false,
                List.of(),
                java.util.Set.of(),
                BigDecimal.ZERO,
                1,
                0L,
                0L
        ));
    }

    /**
     * 更新项目配置。
     *
     * @param projectId 项目 ID
     * @param command   更新命令
     * @return 保存后的配置
     */
    public RdProjectAlertConfig update(String projectId, RdProjectAlertConfigCommand command) {
        String safeProjectId = requireProjectId(projectId);
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        RdProjectAlertConfig existing = get(safeProjectId);
        long now = System.currentTimeMillis();
        RdProjectAlertConfig next = new RdProjectAlertConfig(
                safeProjectId,
                command.enabled(),
                deduplicate(command.recipients()),
                command.eventTypes(),
                command.budgetThresholdCny(),
                command.failureThreshold(),
                existing.createTimeEpochMillis() > 0 ? existing.createTimeEpochMillis() : now,
                now
        );
        return store.save(next);
    }

    private static List<RdAlertRecipient> deduplicate(List<RdAlertRecipient> recipients) {
        LinkedHashMap<String, RdAlertRecipient> unique = new LinkedHashMap<>();
        for (RdAlertRecipient recipient : recipients) {
            if (recipient != null) {
                unique.putIfAbsent(recipient.key(), recipient);
            }
        }
        return List.copyOf(unique.values());
    }

    private static String requireProjectId(String projectId) {
        String safeProjectId = projectId == null ? "" : projectId.strip();
        if (safeProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        return safeProjectId;
    }
}
