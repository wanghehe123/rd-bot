package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdateCommand;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.TicketUpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 飞书 IM 工单适配器。
 *
 * <p>读取端委托本地 IM 工单存储；写入端通过飞书 IM API 向原始 chat_id
 * 发送文本消息。该适配器使 RD-Bot 能在无 Helpdesk 商业能力时继续使用标准工单端口。
 */
@Component
@ConditionalOnExpression("'${rd.repair.ticket.provider:mock}' == 'feishu-im' && '${rd.feishu.im.enabled:false}' == 'true'")
public class FeishuImTicketAdapter implements TicketProviderPort, TicketUpdatePort {

    private static final Logger log = LoggerFactory.getLogger(FeishuImTicketAdapter.class);

    private final FeishuImTicketStore store;
    private final FeishuImClient client;
    private final FeishuImProperties properties;

    public FeishuImTicketAdapter(
            FeishuImTicketStore store,
            FeishuImClient client,
            FeishuImProperties properties
    ) {
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Optional<TicketSnapshot> findTicket(String ticketId) {
        return store.findTicket(ticketId);
    }

    @Override
    public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
        return store.findMessages(ticketId, query);
    }

    @Override
    public TicketUpdateResult sendMessage(TicketReplyCommand command) {
        if (!properties.getWriteBack().isEnabled()) {
            return TicketUpdateResult.failure("WRITE_BACK_DISABLED", "feishu im write-back disabled");
        }
        Optional<TicketSnapshot> snapshot = store.findTicket(command.ticketId());
        if (snapshot.isEmpty()) {
            return TicketUpdateResult.failure("TICKET_NOT_FOUND", "ticket not found: " + command.ticketId());
        }
        if (properties.getLocalListener().isWriteBackViaCli()) {
            return sendMessageViaCli(snapshot.get().chatId(), command);
        }
        try {
            FeishuImClient.FeishuImSendResult result = client.sendTextMessage(snapshot.get().chatId(), command.content());
            if (result.success()) {
                return TicketUpdateResult.success(result.messageId(), result.message());
            }
            return TicketUpdateResult.failure(result.code(), result.message());
        } catch (FeishuImClient.FeishuImException exception) {
            log.warn("feishu im write-back failed, ticketId={}, reason={}", command.ticketId(), exception.getMessage());
            return TicketUpdateResult.failure("FEISHU_IM_ERROR", exception.getMessage());
        }
    }

    @Override
    public TicketUpdateResult updateTicket(TicketUpdateCommand command) {
        return TicketUpdateResult.failure("UNSUPPORTED", "feishu im local ticket update is not supported");
    }

    private TicketUpdateResult sendMessageViaCli(String chatId, TicketReplyCommand command) {
        if (chatId == null || chatId.isBlank()) {
            return TicketUpdateResult.failure("INVALID_CHAT_ID", "chatId must not be blank");
        }
        java.util.List<String> argv = buildSendMessageViaCliCommand(chatId, command);
        try {
            Process process = new ProcessBuilder(argv).start();
            boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                return TicketUpdateResult.failure("LARK_CLI_TIMEOUT", "lark-cli im message send timed out");
            }
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0) {
                log.warn("feishu im cli write-back failed, ticketId={}, exitCode={}, stderr={}",
                        command.ticketId(), process.exitValue(), stderr);
                return TicketUpdateResult.failure("LARK_CLI_ERROR", stderr.isBlank() ? stdout : stderr);
            }
            return TicketUpdateResult.success(extractMessageId(stdout), "ok");
        } catch (Exception exception) {
            log.warn("feishu im cli write-back failed, ticketId={}, reason={}",
                    command.ticketId(), exception.getMessage());
            return TicketUpdateResult.failure("LARK_CLI_ERROR", exception.getMessage());
        }
    }

    java.util.List<String> buildSendMessageViaCliCommand(String chatId, TicketReplyCommand command) {
        java.util.List<String> argv = new ArrayList<>();
        FeishuImProperties.LocalListener local = properties.getLocalListener();
        argv.add(local.getCommand());
        if (!local.getProfile().isBlank()) {
            argv.add("--profile");
            argv.add(local.getProfile());
        }
        argv.add("im");
        argv.add("+messages-send");
        argv.add("--as");
        argv.add(local.getIdentity());
        argv.add("--chat-id");
        argv.add(chatId);
        argv.add("--text");
        argv.add(command.content() == null ? "" : command.content());
        argv.add("--idempotency-key");
        argv.add(idempotencyKey(command));
        argv.add("--json");
        return List.copyOf(argv);
    }

    private static String readAll(java.io.InputStream stream) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().reduce((left, right) -> left + "\n" + right).orElse("");
        }
    }

    private static String idempotencyKey(TicketReplyCommand command) {
        String seed = command.ticketId() + "|" + command.traceId() + "|" + command.repairRecordId() + "|"
                + (command.content() == null ? "" : command.content());
        return "rd-bot-" + Integer.toUnsignedString(seed.hashCode());
    }

    private String extractMessageId(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stdout);
            String value = node.path("data").path("message_id").asText("");
            if (!value.isBlank()) {
                return value;
            }
            return node.path("message_id").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }
}
