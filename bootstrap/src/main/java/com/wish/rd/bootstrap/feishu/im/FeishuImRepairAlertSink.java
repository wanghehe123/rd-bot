package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.bootstrap.executor.impl.InMemoryRepairAlertSink;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.alert.RdAlertDeliveryStore;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigService;
import com.wish.rd.rag.project.alert.model.RdAlertDelivery;
import com.wish.rd.rag.project.alert.model.RdAlertDeliveryStatus;
import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdAlertRecipientType;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Feishu IM implementation of the repair alert sink.
 *
 * <p>The sink stores the alert locally first for operations visibility, then
 * sends a redacted text message to the configured Feishu chat. Feishu delivery
 * failures are captured as delivery attempts and do not fail the workflow.
 */
@Component
@ConditionalOnProperty(prefix = "rd.feishu.im", name = "enabled", havingValue = "true")
public class FeishuImRepairAlertSink extends InMemoryRepairAlertSink {

    private static final Logger log = LoggerFactory.getLogger(FeishuImRepairAlertSink.class);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|secret|token|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final FeishuImClient client;
    private final FeishuImProperties properties;
    private final RdTaskStore taskStore;
    private final RdProjectAlertConfigService configService;
    private final RdAlertDeliveryStore deliveryStore;
    private final SnowflakeIdGenerator idGenerator;
    private final DistributedLockExecutor lockExecutor;
    private final List<DeliveryAttempt> deliveryAttempts = new ArrayList<>();

    public FeishuImRepairAlertSink(FeishuImClient client, FeishuImProperties properties) {
        this(
                client,
                properties,
                (RdTaskStore) null,
                (RdProjectAlertConfigService) null,
                (RdAlertDeliveryStore) null,
                (SnowflakeIdGenerator) null,
                DistributedLockExecutor.local()
        );
    }

    public FeishuImRepairAlertSink(
            FeishuImClient client,
            FeishuImProperties properties,
            RdTaskStore taskStore,
            RdProjectAlertConfigService configService,
            RdAlertDeliveryStore deliveryStore,
            SnowflakeIdGenerator idGenerator
    ) {
        this(client, properties, taskStore, configService, deliveryStore, idGenerator, DistributedLockExecutor.local());
    }

    public FeishuImRepairAlertSink(
            FeishuImClient client,
            FeishuImProperties properties,
            RdTaskStore taskStore,
            RdProjectAlertConfigService configService,
            RdAlertDeliveryStore deliveryStore,
            SnowflakeIdGenerator idGenerator,
            DistributedLockExecutor lockExecutor
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.properties = properties == null ? new FeishuImProperties() : properties;
        this.taskStore = taskStore;
        this.configService = configService;
        this.deliveryStore = deliveryStore;
        this.idGenerator = idGenerator;
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
    }

    @Autowired
    public FeishuImRepairAlertSink(
            FeishuImClient client,
            FeishuImProperties properties,
            ObjectProvider<RdTaskStore> taskStoreProvider,
            ObjectProvider<RdProjectAlertConfigService> configServiceProvider,
            ObjectProvider<RdAlertDeliveryStore> deliveryStoreProvider,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider,
            ObjectProvider<DistributedLockExecutor> lockExecutorProvider
    ) {
        this(
                client,
                properties,
                taskStoreProvider == null ? null : taskStoreProvider.getIfAvailable(),
                configServiceProvider == null ? null : configServiceProvider.getIfAvailable(),
                deliveryStoreProvider == null ? null : deliveryStoreProvider.getIfAvailable(),
                idGeneratorProvider == null ? null : idGeneratorProvider.getIfAvailable(),
                lockExecutorProvider == null
                        ? DistributedLockExecutor.local()
                        : lockExecutorProvider.getIfAvailable(DistributedLockExecutor::local)
        );
    }

    @Override
    public void publish(RepairAlert alert) {
        RepairAlert safeAlert = redactedAlert(alert);
        super.publish(safeAlert);
        try {
            if (projectRoutingAvailable() && publishToProject(safeAlert)) {
                return;
            }
            publishToGlobalFallback(safeAlert);
        } catch (RuntimeException exception) {
            log.warn("feishu alert routing failed open, taskId={}, type={}, reason={}",
                    safeAlert.taskId(), safeAlert.type(), exception.getMessage());
            recordDeliveryAttempt(DeliveryAttempt.failure(
                    safeAlert.taskId(), safeAlert.type().name(), "ALERT_ROUTING_ERROR", exception.getMessage()));
        }
    }

    public void observe(
            String repairRecordId,
            String taskId,
            java.math.BigDecimal estimatedSpendCny,
            java.math.BigDecimal globalThresholdCny,
            long observedAtEpochMillis
    ) {
        if (!projectRoutingAvailable() || estimatedSpendCny == null || estimatedSpendCny.signum() <= 0) {
            return;
        }
        try {
            OptionalTaskContext context = taskStore.findTask(taskId).map(this::toTaskContext).orElse(null);
            if (context == null || context.projectId().isBlank()) return;
            RdProjectAlertConfig config = configService.get(context.projectId());
            if (!config.enabled()
                    || !config.eventTypes().contains(RdProjectAlertEventType.BUDGET_EXCEEDED)
                    || config.budgetThresholdCny().signum() <= 0
                    || estimatedSpendCny.compareTo(config.budgetThresholdCny()) < 0) {
                return;
            }
            publish(new RepairAlert(
                    repairRecordId,
                    taskId,
                    com.wish.rd.exec.repair.alert.model.RepairAlertType.BUDGET_WARNING,
                    "project budget threshold exceeded",
                    Map.of(
                            "currency", "CNY",
                            "estimatedSpendCny", estimatedSpendCny.toPlainString(),
                            "thresholdSpendCny", config.budgetThresholdCny().toPlainString(),
                            "globalThresholdCny", globalThresholdCny == null
                                    ? "0" : globalThresholdCny.toPlainString(),
                            "projectThresholdSpendCny", config.budgetThresholdCny().toPlainString()
                    ),
                    observedAtEpochMillis
            ));
        } catch (RuntimeException exception) {
            log.warn("project budget observation failed open, taskId={}, reason={}", taskId, exception.getMessage());
        }
    }

    private boolean publishToProject(RepairAlert alert) {
        OptionalTaskContext context = taskStore.findTask(alert.taskId())
                .map(this::toTaskContext)
                .orElse(null);
        if (context == null || context.projectId().isBlank()) {
            return false;
        }
        RdProjectAlertEventType eventType = toProjectEvent(alert.type());
        if (eventType == null) {
            return false;
        }
        RdProjectAlertConfig config = configService.get(context.projectId());
        if (!config.enabled() || !config.eventTypes().contains(eventType)) {
            return false;
        }
        if (!passesThreshold(alert, eventType, config)) {
            return true;
        }
        List<RdAlertRecipient> recipients = config.recipients();
        if (recipients.isEmpty()) {
            return false;
        }
        for (RdAlertRecipient recipient : recipients) {
            publishToRecipient(alert, context, eventType, recipient);
        }
        return true;
    }

    private void publishToRecipient(
            RepairAlert alert,
            OptionalTaskContext context,
            RdProjectAlertEventType eventType,
            RdAlertRecipient recipient
    ) {
        String idempotencyKey = String.join(":",
                alert.taskId(), eventType.name(), alert.repairRecordId(), recipient.type().name(), recipient.value());
        lockExecutor.execute("rd-bot:alert-delivery:" + sha256(idempotencyKey), () -> {
            RdAlertDelivery reservation = deliveryStore.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (reservation != null && reservation.status() != RdAlertDeliveryStatus.PENDING) {
                return;
            }
            if (reservation == null) {
                reservation = new RdAlertDelivery(
                        idGenerator.nextIdString(), alert.taskId(), context.projectId(), eventType,
                        recipient.type(), recipient.value(), RdAlertDeliveryStatus.PENDING, "",
                        "DELIVERY_PENDING", "delivery reserved before provider call",
                        idempotencyKey, System.currentTimeMillis()
                );
                try {
                    reservation = deliveryStore.save(reservation);
                } catch (RuntimeException auditException) {
                    String reason = redact(auditException.getMessage());
                    log.warn("alert delivery reservation failed; provider call skipped, taskId={}, type={}, reason={}",
                            alert.taskId(), eventType, reason);
                    recordDeliveryAttempt(DeliveryAttempt.failure(
                            alert.taskId(), eventType.name(), "AUDIT_RESERVATION_FAILED", reason));
                    return;
                }
                if (reservation.status() != RdAlertDeliveryStatus.PENDING) {
                    return;
                }
            }
            deliverProjectAlert(alert, context, eventType, recipient, reservation);
        });
    }

    private void deliverProjectAlert(
            RepairAlert alert,
            OptionalTaskContext context,
            RdProjectAlertEventType eventType,
            RdAlertRecipient recipient,
            RdAlertDelivery reservation
    ) {
        try {
            FeishuImClient.FeishuImSendResult result = client.sendTextMessage(
                    recipient.type() == RdAlertRecipientType.CHAT_ID ? "chat_id" : "open_id",
                    recipient.value(),
                    formatAlert(alert, context),
                    sha256(reservation.idempotencyKey()).substring(0, 32)
            );
            RdAlertDeliveryStatus status = result.success()
                    ? RdAlertDeliveryStatus.SENT
                    : RdAlertDeliveryStatus.FAILED;
            updateDeliveryQuietly(new RdAlertDelivery(
                    reservation.deliveryId(), alert.taskId(), context.projectId(), eventType,
                    recipient.type(), recipient.value(), status, result.messageId(),
                    result.success() ? "" : result.code(), result.success() ? "" : redact(result.message()),
                    reservation.idempotencyKey(), reservation.createTimeEpochMillis()
            ));
            recordDeliveryAttempt(result.success()
                    ? DeliveryAttempt.success(alert.taskId(), eventType.name(), result.messageId())
                    : DeliveryAttempt.failure(alert.taskId(), eventType.name(), result.code(), redact(result.message())));
        } catch (RuntimeException exception) {
            String reason = redact(exception.getMessage());
            log.warn("feishu project alert delivery failed, taskId={}, type={}, reason={}",
                    alert.taskId(), eventType, reason);
            updateDeliveryQuietly(new RdAlertDelivery(
                    reservation.deliveryId(), alert.taskId(), context.projectId(), eventType,
                    recipient.type(), recipient.value(), RdAlertDeliveryStatus.FAILED, "",
                    "FEISHU_IM_ERROR", reason, reservation.idempotencyKey(),
                    reservation.createTimeEpochMillis()
            ));
            recordDeliveryAttempt(DeliveryAttempt.failure(
                    alert.taskId(), eventType.name(), "FEISHU_IM_ERROR", reason));
        }
    }

    private boolean updateDeliveryQuietly(RdAlertDelivery delivery) {
        try {
            deliveryStore.updateOutcome(delivery);
            return true;
        } catch (RuntimeException auditException) {
            String reason = redact(auditException.getMessage());
            log.warn("alert delivery audit failed, taskId={}, type={}, reason={}",
                    delivery.taskId(), delivery.alertType(), reason);
            recordDeliveryAttempt(DeliveryAttempt.failure(
                    delivery.taskId(), delivery.alertType().name(), "AUDIT_OUTCOME_FAILED", reason));
            return false;
        }
    }

    private void publishToGlobalFallback(RepairAlert alert) {
        if (alert.type() == com.wish.rd.exec.repair.alert.model.RepairAlertType.BUDGET_WARNING) {
            java.math.BigDecimal spend = parseDecimal(alert.metadata().get("estimatedSpendCny"));
            java.math.BigDecimal threshold = parseDecimal(alert.metadata().get("thresholdSpendCny"));
            if (threshold.signum() > 0 && spend.compareTo(threshold) <= 0) {
                return;
            }
        }
        String chatId = properties.getAlert().getChatId();
        if (!properties.getAlert().isEnabled() || chatId.isBlank()) {
            recordDeliveryAttempt(DeliveryAttempt.failure(alert.taskId(), alert.type().name(),
                    "MISSING_CHAT_ID", "rd.feishu.im.alert.chat-id must not be blank"));
            return;
        }
        if (projectRoutingAvailable()) {
            OptionalTaskContext context = taskStore.findTask(alert.taskId()).map(this::toTaskContext).orElse(null);
            RdProjectAlertEventType eventType = toProjectEvent(alert.type());
            if (context != null && !context.projectId().isBlank() && eventType != null) {
                publishToRecipient(
                        alert,
                        context,
                        eventType,
                        new RdAlertRecipient(RdAlertRecipientType.CHAT_ID, chatId)
                );
                return;
            }
        }
        try {
            FeishuImClient.FeishuImSendResult result = client.sendTextMessage(chatId, formatAlert(alert));
            if (result.success()) {
                recordDeliveryAttempt(DeliveryAttempt.success(
                        alert.taskId(),
                        alert.type().name(),
                        result.messageId()
                ));
            } else {
                recordDeliveryAttempt(DeliveryAttempt.failure(
                        alert.taskId(),
                        alert.type().name(),
                        result.code(),
                        redact(result.message())
                ));
            }
        } catch (FeishuImClient.FeishuImException exception) {
            String reason = redact(exception.getMessage());
            log.warn("feishu im alert delivery failed, taskId={}, type={}, reason={}",
                    alert.taskId(), alert.type(), reason);
            recordDeliveryAttempt(DeliveryAttempt.failure(
                    alert.taskId(),
                    alert.type().name(),
                    "FEISHU_IM_ERROR",
                    reason
            ));
        }
    }

    private boolean projectRoutingAvailable() {
        return taskStore != null && configService != null && deliveryStore != null && idGenerator != null;
    }

    private boolean passesThreshold(
            RepairAlert alert,
            RdProjectAlertEventType eventType,
            RdProjectAlertConfig config
    ) {
        if (eventType == RdProjectAlertEventType.RETRY_EXHAUSTED) {
            return parseInt(alert.metadata().get("attemptNo"), 1) >= config.failureThreshold();
        }
        if (eventType == RdProjectAlertEventType.BUDGET_EXCEEDED
                && config.budgetThresholdCny().signum() > 0) {
            String estimatedSpendCny = alert.metadata().getOrDefault("estimatedSpendCny", "0");
            return parseDecimal(estimatedSpendCny)
                    .compareTo(config.budgetThresholdCny()) >= 0;
        }
        return true;
    }

    private RdProjectAlertEventType toProjectEvent(com.wish.rd.exec.repair.alert.model.RepairAlertType type) {
        return switch (type) {
            case TASK_COMPLETED -> RdProjectAlertEventType.TASK_COMPLETED;
            case TASK_BLOCKED, STAGE_FAILED_NEEDS_HUMAN, POLICY_WAITING_APPROVAL -> RdProjectAlertEventType.TASK_BLOCKED;
            case TASK_FAILED, WORKFLOW_DEAD_LETTERED -> RdProjectAlertEventType.TASK_FAILED;
            case RETRY_EXHAUSTED, STAGE_FAILED_RETRYABLE -> RdProjectAlertEventType.RETRY_EXHAUSTED;
            case BUDGET_WARNING -> RdProjectAlertEventType.BUDGET_EXCEEDED;
            case QA_FAILED -> RdProjectAlertEventType.QA_FAILED;
            default -> null;
        };
    }

    private OptionalTaskContext toTaskContext(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return new OptionalTaskContext(bugFixTask.projectId(), bugFixTask.projectName(), bugFixTask.status().name());
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return new OptionalTaskContext(
                    requirementTask.projectId(), requirementTask.projectName(), requirementTask.status().name());
        }
        return new OptionalTaskContext("", "", task.status().name());
    }

    private String formatAlert(RepairAlert alert, OptionalTaskContext context) {
        return formatAlert(alert)
                + "\nproject: " + redact(context.projectName())
                + "\nstatus: " + context.status();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static java.math.BigDecimal parseDecimal(String value) {
        try {
            return new java.math.BigDecimal(value == null ? "0" : value);
        } catch (NumberFormatException ignored) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private record OptionalTaskContext(String projectId, String projectName, String status) {
    }

    /**
     * Returns immutable Feishu delivery attempts.
     *
     * @return delivery attempts
     */
    public synchronized List<DeliveryAttempt> deliveryAttempts() {
        return List.copyOf(deliveryAttempts);
    }

    private String formatAlert(RepairAlert alert) {
        StringBuilder builder = new StringBuilder();
        builder.append("RD-Bot 告警\n");
        builder.append("类型: ").append(alert.type()).append('\n');
        builder.append("taskId: ").append(alert.taskId()).append('\n');
        builder.append("repairRecordId: ").append(alert.repairRecordId()).append('\n');
        builder.append("时间: ").append(Instant.ofEpochMilli(alert.createdAtEpochMillis())).append('\n');
        builder.append("消息: ").append(redact(alert.message())).append('\n');
        builder.append("管理台: ").append(properties.getAlert().getAdminBaseUrl())
                .append("/admin/rd-tasks/").append(alert.taskId()).append('\n');
        List<Map.Entry<String, String>> metadata = alert.metadata().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        for (Map.Entry<String, String> entry : metadata) {
            builder.append(entry.getKey()).append(": ")
                    .append(formatAlertMetadataValue(entry.getKey(), entry.getValue()))
                    .append('\n');
        }
        return builder.toString().strip();
    }

    private synchronized void recordDeliveryAttempt(DeliveryAttempt attempt) {
        deliveryAttempts.add(attempt);
    }

    private static String redactMetadataValue(String key, String value) {
        if (isSecretKey(key)) {
            return "[REDACTED]";
        }
        return redact(value);
    }

    private static String formatAlertMetadataValue(String key, String value) {
        String redacted = redactMetadataValue(key, value);
        return key != null && key.endsWith("Cny") && !"[REDACTED]".equals(redacted)
                ? "¥" + redacted
                : redacted;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("api-key")
                || normalized.contains("api_key");
    }

    private static String redact(String value) {
        String safeValue = value == null ? "" : value;
        return SECRET_ASSIGNMENT.matcher(safeValue).replaceAll("$1=[REDACTED]");
    }

    private static RepairAlert redactedAlert(RepairAlert alert) {
        Objects.requireNonNull(alert, "alert must not be null");
        Map<String, String> metadata = new LinkedHashMap<>();
        alert.metadata().forEach((key, value) -> metadata.put(key, redactMetadataValue(key, value)));
        return new RepairAlert(
                alert.repairRecordId(), alert.taskId(), alert.type(), redact(alert.message()),
                metadata, alert.createdAtEpochMillis());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Feishu alert delivery attempt.
     *
     * @param taskId    task id
     * @param alertType alert type
     * @param success   whether delivery succeeded
     * @param messageId Feishu message id when successful
     * @param code      provider or local failure code
     * @param message   provider or local failure message
     */
    public record DeliveryAttempt(
            String taskId,
            String alertType,
            boolean success,
            String messageId,
            String code,
            String message
    ) {
        public DeliveryAttempt {
            taskId = taskId == null ? "" : taskId.strip();
            alertType = alertType == null ? "" : alertType.strip();
            messageId = messageId == null ? "" : messageId.strip();
            code = code == null ? "" : code.strip();
            message = message == null ? "" : message.strip();
        }

        static DeliveryAttempt success(String taskId, String alertType, String messageId) {
            return new DeliveryAttempt(taskId, alertType, true, messageId, "0", "ok");
        }

        static DeliveryAttempt failure(String taskId, String alertType, String code, String message) {
            return new DeliveryAttempt(taskId, alertType, false, "", code, message);
        }
    }
}
