package com.wish.rd.engine.bugfix.acceptance;

import java.util.List;

/**
 * 验收执行策略占位对象。P3 再实现真实治理。
 *
 * @param enabled                是否启用验收执行
 * @param environmentAllowlist   环境白名单
 * @param maxSteps               最大步骤数
 * @param writeOperationsEnabled 是否允许写操作
 */
public record AcceptanceExecutionPolicy(
        boolean enabled,
        List<String> environmentAllowlist,
        int maxSteps,
        boolean writeOperationsEnabled
) {

    public AcceptanceExecutionPolicy {
        environmentAllowlist = environmentAllowlist == null ? List.of() : List.copyOf(environmentAllowlist);
        maxSteps = maxSteps <= 0 ? 20 : maxSteps;
    }

    public static AcceptanceExecutionPolicy disabled() {
        return new AcceptanceExecutionPolicy(false, List.of("local", "sit"), 20, false);
    }
}
