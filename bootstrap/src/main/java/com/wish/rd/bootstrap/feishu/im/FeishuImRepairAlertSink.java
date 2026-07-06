package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.bootstrap.executor.impl.InMemoryRepairAlertSink;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
@ConditionalOnExpression("'${rd.feishu.im.enabled:false}' == 'true' && '${rd.feishu.im.alert.enabled:false}' == 'true'")
public class FeishuImRepairAlertSink extends InMemoryRepairAlertSink {

    private static final Logger log = LoggerFactory.getLogger(FeishuImRepairAlertSink.class);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|secret|token|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final FeishuImClient client;
    private final FeishuImProperties properties;
    private final List<DeliveryAttempt> deliveryAttempts = new ArrayList<>();

    public FeishuImRepairAlertSink(FeishuImClient client, FeishuImProperties properties) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.properties = properties == null ? new FeishuImProperties() : properties;
    }

    @Override
    public void publish(RepairAlert alert) {
        super.publish(alert);
        String chatId = properties.getAlert().getChatId();
        if (chatId.isBlank()) {
            recordDeliveryAttempt(DeliveryAttempt.failure(alert.taskId(), alert.type().name(),
                    "MISSING_CHAT_ID", "rd.feishu.im.alert.chat-id must not be blank"));
            return;
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
                        result.message()
                ));
            }
        } catch (FeishuImClient.FeishuImException exception) {
            log.warn("feishu im alert delivery failed, taskId={}, type={}, reason={}",
                    alert.taskId(), alert.type(), exception.getMessage());
            recordDeliveryAttempt(DeliveryAttempt.failure(
                    alert.taskId(),
                    alert.type().name(),
                    "FEISHU_IM_ERROR",
                    exception.getMessage()
            ));
        }
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
        List<Map.Entry<String, String>> metadata = alert.metadata().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        for (Map.Entry<String, String> entry : metadata) {
            builder.append(entry.getKey()).append(": ")
                    .append(redactMetadataValue(entry.getKey(), entry.getValue()))
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
