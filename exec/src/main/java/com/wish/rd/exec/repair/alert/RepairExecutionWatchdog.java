package com.wish.rd.exec.repair.alert;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;

/**
 * 修复执行观察器，按配置策略发出超时和预算告警，不直接依赖 Docker、进程或通知实现。
 */
public final class RepairExecutionWatchdog {

    private static final String TIMEOUT_MESSAGE = "repair execution elapsed millis exceeded warning threshold";
    private static final String BUDGET_MESSAGE = "repair execution estimated spend exceeded warning threshold";

    private final Policy policy;
    private final RepairAlertSinkPort alertSink;
    private final Set<AlertKey> publishedAlertKeys = new LinkedHashSet<>();

    /**
     * 创建修复执行观察器。
     *
     * @param policy    超时和预算告警策略
     * @param alertSink 告警输出端口
     */
    public RepairExecutionWatchdog(Policy policy, RepairAlertSinkPort alertSink) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.alertSink = Objects.requireNonNull(alertSink, "alertSink must not be null");
    }

    /**
     * 评估当前执行耗时和预估花费，发布首次命中的告警并返回继续执行结果。
     *
     * @param repairRecordId       修复记录 ID
     * @param taskId               RD 任务 ID
     * @param elapsedMillis        已执行毫秒数
     * @param estimatedSpend       当前预估花费
     * @param createdAtEpochMillis 告警创建时间
     * @return 本次评估结果，告警命中时仍默认继续执行
     */
    public EvaluationResult evaluate(
            String repairRecordId,
            String taskId,
            long elapsedMillis,
            BigDecimal estimatedSpend,
            long createdAtEpochMillis
    ) {
        String normalizedRepairRecordId = requireId(repairRecordId, "repairRecordId");
        String normalizedTaskId = requireId(taskId, "taskId");
        BigDecimal normalizedSpend = estimatedSpend == null ? BigDecimal.ZERO : estimatedSpend;
        List<RepairAlert> alerts = new ArrayList<>();

        if (elapsedMillis > policy.timeoutWarningMillis()) {
            publishIfFirst(
                    alerts,
                    new RepairAlert(
                            normalizedRepairRecordId,
                            normalizedTaskId,
                            RepairAlertType.TIMEOUT_WARNING,
                            TIMEOUT_MESSAGE,
                            Map.of(
                                    "elapsedMillis", Long.toString(elapsedMillis),
                                    "thresholdMillis", Long.toString(policy.timeoutWarningMillis())
                            ),
                            createdAtEpochMillis
                    )
            );
        }
        if (normalizedSpend.compareTo(policy.budgetWarningAmount()) > 0) {
            publishIfFirst(
                    alerts,
                    new RepairAlert(
                            normalizedRepairRecordId,
                            normalizedTaskId,
                            RepairAlertType.BUDGET_WARNING,
                            BUDGET_MESSAGE,
                            Map.of(
                                    "estimatedSpend", normalizedSpend.toPlainString(),
                                    "thresholdSpend", policy.budgetWarningAmount().toPlainString()
                            ),
                            createdAtEpochMillis
                    )
            );
        }

        return new EvaluationResult(false, alerts);
    }

    private synchronized void publishIfFirst(List<RepairAlert> alerts, RepairAlert alert) {
        AlertKey key = new AlertKey(alert.repairRecordId(), alert.taskId(), alert.type());
        if (publishedAlertKeys.add(key)) {
            try {
                alertSink.publish(alert);
            } catch (RuntimeException | Error ex) {
                publishedAlertKeys.remove(key);
                throw ex;
            }
            alerts.add(alert);
        }
    }

    private static String requireId(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    /**
     * 观察器阈值策略，由调用方配置，避免执行器内部隐藏告警常量。
     *
     * @param timeoutWarningMillis 超时告警阈值毫秒数
     * @param budgetWarningAmount  预算告警阈值金额
     */
    public record Policy(long timeoutWarningMillis, BigDecimal budgetWarningAmount) {

        public Policy {
            if (timeoutWarningMillis < 0) {
                throw new IllegalArgumentException("timeoutWarningMillis must not be negative");
            }
            budgetWarningAmount = budgetWarningAmount == null ? BigDecimal.ZERO : budgetWarningAmount;
            if (budgetWarningAmount.signum() < 0) {
                throw new IllegalArgumentException("budgetWarningAmount must not be negative");
            }
        }
    }

    /**
     * 观察器评估结果，显式表达告警默认不要求终止执行。
     *
     * @param shouldStop 是否建议上游停止执行
     * @param alerts     本次新发布的告警
     */
    public record EvaluationResult(boolean shouldStop, List<RepairAlert> alerts) {

        public EvaluationResult {
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
        }
    }

    private record AlertKey(String repairRecordId, String taskId, RepairAlertType type) {
    }
}
