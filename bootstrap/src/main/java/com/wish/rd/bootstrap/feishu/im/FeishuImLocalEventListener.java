package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书 IM 本地事件监听器。
 *
 * <p>该组件通过 {@code lark-cli event consume im.message.receive_v1 --as bot}
 * 在本机主动消费事件流，不要求飞书公网回调到本地机器。读取到的 lark-cli
 * 扁平事件会被转换成现有 HTTP 回调 envelope，并复用 {@link FeishuImMessageController}
 * 的入队逻辑。
 */
@Component
@ConditionalOnExpression("'${rd.repair.ticket.provider:mock}' == 'feishu-im' "
        + "&& '${rd.feishu.im.enabled:false}' == 'true' "
        + "&& '${rd.feishu.im.local-listener.enabled:false}' == 'true'")
public class FeishuImLocalEventListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuImLocalEventListener.class);

    private final ObjectMapper objectMapper;
    private final FeishuImProperties properties;
    private final FeishuImMessageController controller;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile Process process;

    public FeishuImLocalEventListener(
            ObjectMapper objectMapper,
            FeishuImProperties properties,
            FeishuImMessageController controller
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.controller = controller;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::runLoop, "feishu-im-local-event-listener");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
        Process current = process;
        if (current != null) {
            try {
                current.getOutputStream().close();
            } catch (Exception ignored) {
                // stdin close is a graceful lark-cli event consume shutdown signal.
            }
            current.destroy();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void runLoop() {
        while (running.get()) {
            try {
                ProcessBuilder builder = new ProcessBuilder(command());
                builder.redirectErrorStream(false);
                Process started = builder.start();
                process = started;
                log.info("started local feishu im listener, command={}", String.join(" ", command()));

                List<String> stderrLines = new CopyOnWriteArrayList<>();
                Thread stdout = new Thread(() -> readStdout(started), "feishu-im-local-event-stdout");
                Thread stderr = new Thread(() -> readStderr(started, stderrLines), "feishu-im-local-event-stderr");
                stdout.setDaemon(true);
                stderr.setDaemon(true);
                stdout.start();
                stderr.start();

                int exitCode = started.waitFor();
                stdout.join(1000);
                stderr.join(1000);
                process = null;
                if (running.get() && shouldStopAfterExit(exitCode, stderrLines)) {
                    running.set(false);
                    log.error(
                            "local feishu im listener stopped after non-retryable lark-cli validation failure, "
                                    + "exitCode={}, profile={}, eventKey={}. Resolve the lark-cli stderr hint, "
                                    + "then restart RD-Bot.",
                            exitCode,
                            properties.getLocalListener().getProfile(),
                            properties.getLocalListener().getEventKey()
                    );
                    return;
                }
                if (running.get()) {
                    log.warn("local feishu im listener exited, exitCode={}, restartDelayMillis={}",
                            exitCode, properties.getLocalListener().getRestartDelayMillis());
                    Thread.sleep(properties.getLocalListener().getRestartDelayMillis());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                process = null;
                log.warn("local feishu im listener failed to start or run: {}", exception.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    List<String> command() {
        FeishuImProperties.LocalListener local = properties.getLocalListener();
        List<String> argv = new ArrayList<>();
        argv.add(local.getCommand());
        if (!local.getProfile().isBlank()) {
            argv.add("--profile");
            argv.add(local.getProfile());
        }
        argv.add("event");
        argv.add("consume");
        argv.add(local.getEventKey());
        argv.add("--as");
        argv.add(local.getIdentity());
        return List.copyOf(argv);
    }

    boolean shouldStopAfterExit(int exitCode, List<String> stderrLines) {
        if (exitCode != 2) {
            return false;
        }
        JsonNode envelope = readErrorEnvelope(stderrLines);
        return "validation".equals(envelope.path("error").path("type").asText(""));
    }

    private JsonNode readErrorEnvelope(List<String> stderrLines) {
        String stderr = String.join("\n", stderrLines == null ? List.of() : stderrLines);
        int start = stderr.indexOf('{');
        int end = stderr.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(stderr.substring(start, end + 1));
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private void readStdout(Process current) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                handleEventLine(line);
            }
        } catch (Exception exception) {
            if (running.get()) {
                log.warn("local feishu im listener stdout read failed: {}", exception.getMessage());
            }
        }
    }

    private void readStderr(Process current, List<String> stderrLines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                current.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderrLines.add(line);
                if (line.contains("[event] ready")) {
                    log.info("local feishu im listener ready: {}", line);
                } else {
                    log.info("local feishu im listener: {}", line);
                }
            }
        } catch (Exception exception) {
            if (running.get()) {
                log.warn("local feishu im listener stderr read failed: {}", exception.getMessage());
            }
        }
    }

    void handleEventLine(String line) {
        String raw = line == null ? "" : line.strip();
        if (raw.isBlank()) {
            return;
        }
        try {
            String envelope = toHttpEnvelope(raw);
            ResponseEntity<Object> response = controller.receive(envelope);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("local feishu im event rejected, status={}", response.getStatusCode());
            }
        } catch (Exception exception) {
            log.warn("local feishu im event handling failed: {}", exception.getMessage());
        }
    }

    String toHttpEnvelope(String line) throws Exception {
        JsonNode node = objectMapper.readTree(line);
        if (node.path("header").path("event_type").asText("").equals("im.message.receive_v1")) {
            return line;
        }

        String eventType = text(node, "type", "im.message.receive_v1");
        String messageId = firstText(node, "message_id", "id");
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode header = root.putObject("header");
        header.put("event_type", eventType);
        header.put("event_id", text(node, "event_id", messageId));
        header.put("create_time", firstText(node, "timestamp", "create_time"));

        ObjectNode event = root.putObject("event");
        ObjectNode sender = event.putObject("sender");
        sender.putObject("sender_id").put("open_id", text(node, "sender_id", ""));

        ObjectNode message = event.putObject("message");
        message.put("message_id", messageId);
        message.put("chat_id", text(node, "chat_id", ""));
        message.put("chat_type", text(node, "chat_type", "p2p"));
        String messageType = text(node, "message_type", "text");
        message.put("message_type", messageType);
        if ("interactive".equals(messageType)) {
            message.put("content", text(node, "content", ""));
        } else {
            message.put("content", objectMapper.writeValueAsString(
                    java.util.Map.of("text", text(node, "content", ""))
            ));
        }
        message.putArray("mentions");
        return objectMapper.writeValueAsString(root);
    }

    private String firstText(JsonNode node, String first, String second) {
        String firstValue = text(node, first, "");
        return firstValue.isBlank() ? text(node, second, "") : firstValue;
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(properties.getLocalListener().getRestartDelayMillis());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
