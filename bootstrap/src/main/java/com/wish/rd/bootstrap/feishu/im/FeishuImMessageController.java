package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.feishu.im.model.FeishuImTicketDraft;
import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.model.TicketEventInput;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 飞书 IM 消息事件控制器。
 *
 * <p>接收飞书 IM 事件回调，将文本消息转换成本地工单并交给
 * {@link TicketEventIngestionEngine} 入队。控制器只做外部事件适配，不直接调用 RAG 或执行器。
 */
@RestController
@ConditionalOnExpression("'${rd.repair.ticket.provider:mock}' == 'feishu-im' && '${rd.feishu.im.enabled:false}' == 'true'")
public class FeishuImMessageController {

    private static final Logger log = LoggerFactory.getLogger(FeishuImMessageController.class);
    private static final String FEISHU_MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";

    private final ObjectMapper objectMapper;
    private final FeishuImProperties properties;
    private final FeishuImTicketParser parser;
    private final FeishuImTicketStore store;
    private final TicketEventIngestionEngine ingestionEngine;
    private final RagStreamTaskRegistry taskRegistry;
    private final TaskMaterialStore materialStore;
    private final RequirementDeliveryEngine requirementDeliveryEngine;
    private final RequirementDeliveryDispatchService requirementDeliveryDispatchService;
    private final SnowflakeIdGenerator idGenerator;

    public FeishuImMessageController(
            ObjectMapper objectMapper,
            FeishuImProperties properties,
            FeishuImTicketParser parser,
            FeishuImTicketStore store,
            TicketEventIngestionEngine ingestionEngine
    ) {
        this(objectMapper, properties, parser, store, ingestionEngine, null, null, null, null,
                SnowflakeIdGenerator.defaultGenerator());
    }

    @Autowired
    public FeishuImMessageController(
            ObjectMapper objectMapper,
            FeishuImProperties properties,
            FeishuImTicketParser parser,
            FeishuImTicketStore store,
            TicketEventIngestionEngine ingestionEngine,
            ObjectProvider<RagStreamTaskRegistry> taskRegistryProvider,
            ObjectProvider<TaskMaterialStore> materialStoreProvider,
            ObjectProvider<RequirementDeliveryEngine> requirementDeliveryEngineProvider,
            ObjectProvider<RequirementDeliveryDispatchService> requirementDeliveryDispatchServiceProvider,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider
    ) {
        this(
                objectMapper,
                properties,
                parser,
                store,
                ingestionEngine,
                taskRegistryProvider.getIfAvailable(),
                materialStoreProvider.getIfAvailable(),
                requirementDeliveryEngineProvider.getIfAvailable(),
                requirementDeliveryDispatchServiceProvider.getIfAvailable(),
                idGeneratorProvider.getIfAvailable(SnowflakeIdGenerator::defaultGenerator)
        );
    }

    FeishuImMessageController(
            ObjectMapper objectMapper,
            FeishuImProperties properties,
            FeishuImTicketParser parser,
            FeishuImTicketStore store,
            TicketEventIngestionEngine ingestionEngine,
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementDeliveryEngine requirementDeliveryEngine,
            RequirementDeliveryDispatchService requirementDeliveryDispatchService,
            SnowflakeIdGenerator idGenerator
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.parser = parser;
        this.store = store;
        this.ingestionEngine = ingestionEngine;
        this.taskRegistry = taskRegistry;
        this.materialStore = materialStore;
        this.requirementDeliveryEngine = requirementDeliveryEngine;
        this.requirementDeliveryDispatchService = requirementDeliveryDispatchService;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    /**
     * 接收飞书 IM 事件回调。
     *
     * @param rawBody 飞书原始事件体
     * @return URL 校验、忽略结果或入队结果
     */
    @PostMapping("/feishu/im/events")
    public ResponseEntity<Object> receive(@RequestBody String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "empty body"));
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawBody);
        } catch (Exception exception) {
            log.warn("feishu im event parse failed: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "invalid json body"));
        }

        if ("url_verification".equals(envelope.path("type").asText(""))) {
            return ResponseEntity.ok(Map.of("challenge", envelope.path("challenge").asText("")));
        }

        String eventType = envelope.path("header").path("event_type").asText("");
        if (!FEISHU_MESSAGE_RECEIVE_EVENT.equals(eventType)) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "eventType", eventType));
        }

        JsonNode event = envelope.path("event");
        JsonNode message = event.path("message");
        if (!isTextLikeMessage(message.path("message_type").asText(""))) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "reason", "non-text message"));
        }

        boolean groupChat = "group".equals(message.path("chat_type").asText(""));
        JsonNode mentions = message.path("mentions");
        if (properties.isRequireAtMention() && groupChat && (!mentions.isArray() || mentions.isEmpty())) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "reason", "bot not mentioned"));
        }

        String text = cleanText(extractText(message.path("content")), mentions);
        if (text.isBlank()) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "reason", "empty text"));
        }

        String messageId = message.path("message_id").asText("");
        RequirementDraft requirementDraft = parseRequirement(text);
        if (requirementDraft.requirement()) {
            return handleRequirement(requirementDraft, messageId);
        }
        String ticketId = toTicketId(messageId);
        String chatId = message.path("chat_id").asText("");
        String senderOpenId = event.path("sender").path("sender_id").path("open_id").asText("");
        Instant createdAt = toInstant(envelope.path("header").path("create_time"));
        FeishuImTicketDraft draft = parser.parse(text);
        TicketSnapshot snapshot = store.registerFromMessage(ticketId, chatId, senderOpenId, messageId, text, draft, createdAt);

        TicketEventInput input = new TicketEventInput(
                snapshot.ticketId(),
                envelope.path("header").path("event_id").asText(""),
                TicketEventInput.TYPE_FEISHU_IM_MESSAGE_CREATED,
                FeishuImTicketStore.SOURCE,
                snapshot.priority(),
                "",
                createdAt,
                metadata(chatId, messageId)
        );
        RepairQueuePublishResult result = ingestionEngine.ingest(input);
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "success", result.success(),
                "messageId", result.messageId(),
                "ticketId", snapshot.ticketId()
        ));
    }

    private ResponseEntity<Object> handleRequirement(RequirementDraft draft, String messageId) {
        if (taskRegistry == null || materialStore == null || requirementDeliveryEngine == null) {
            return ResponseEntity.status(409).body(Map.of("message", "requirement delivery is unavailable"));
        }
        List<String> missingFields = missingRequirementFields(draft);
        if (!missingFields.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accepted", true);
            response.put("taskType", "REQUIREMENT");
            response.put("status", "NEED_INFO");
            response.put("messageId", messageId == null ? "" : messageId);
            response.put("missingFields", missingFields);
            response.put("message", "需求任务缺少必填字段: " + String.join(", ", missingFields));
            return ResponseEntity.ok(response);
        }
        RdRequirementTask task = taskRegistry.createRequirementTask(new CreateRequirementTaskCommand(
                draft.title(),
                draft.priority(),
                "FEISHU_IM",
                messageId,
                "",
                draft.repositoryUrl(),
                "",
                "",
                draft.baseBranch(),
                draft.expectedResult(),
                draft.acceptanceCriteria(),
                true
        ));
        materialStore.save(new TaskMaterial(
                idGenerator.nextIdString(),
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                draft.sourceType(),
                draft.materialTitle(),
                draft.sourceUri(),
                draft.sourceType() == TaskMaterialSourceType.FEISHU_DOC ? "text/uri-list" : "text/markdown",
                sha256(draft.materialContent().isBlank() ? draft.sourceUri() : draft.materialContent()),
                preview(draft.materialContent().isBlank() ? draft.sourceUri() : draft.materialContent(), 2000),
                "",
                "",
                "",
                "{\"source\":\"feishu-im\",\"messageId\":\"%s\"}".formatted(messageId == null ? "" : messageId),
                System.currentTimeMillis(),
                System.currentTimeMillis()
        ));
        RequirementDeliveryResult result = null;
        if (requirementDeliveryDispatchService != null) {
            requirementDeliveryDispatchService.submit(task.taskId());
        } else {
            result = requirementDeliveryEngine.submit(task.taskId());
        }
        RdRequirementTask latest = taskRegistry.getRequirementTask(task.taskId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", true);
        response.put("taskType", latest.taskType());
        response.put("taskId", latest.taskId());
        response.put("status", latest.status().name());
        response.put("pullRequestUrl", latest.pullRequestUrl());
        response.put("dispatched", requirementDeliveryDispatchService != null);
        if (result != null) {
            response.putAll(executionEvidence(result.resultJson()));
        }
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> executionEvidence(String resultJson) {
        JsonNode root = readExecutionResult(resultJson);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("summary", text(root, "summary"));
        evidence.put("prBody", text(root, "prBody"));
        evidence.put("changedFiles", list(root.path("changedFiles")));
        evidence.put("testCommands", list(firstNode(root, "testCommands", "testMetadata", "testCommands")));
        evidence.put("testStatus", firstText(root, "testStatus", "testMetadata", "testStatus"));
        evidence.put("riskLevel", firstText(root, "riskLevel", "riskMetadata", "riskLevel"));
        return evidence;
    }

    private JsonNode readExecutionResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(resultJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.asText("").strip() : "";
    }

    private static String firstText(JsonNode root, String directField, String objectField, String nestedField) {
        String direct = text(root, directField);
        if (!direct.isBlank()) {
            return direct;
        }
        JsonNode nested = root == null ? null : root.path(objectField).path(nestedField);
        return nested != null && nested.isTextual() ? nested.asText("").strip() : "";
    }

    private static JsonNode firstNode(JsonNode root, String directField, String objectField, String nestedField) {
        JsonNode direct = root == null ? null : root.path(directField);
        if (direct != null && !direct.isMissingNode() && !direct.isNull()) {
            return direct;
        }
        return root == null ? null : root.path(objectField).path(nestedField);
    }

    private static List<String> list(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new java.util.ArrayList<>();
            node.forEach(item -> {
                String value = item.isTextual() ? item.asText("").strip() : item.toString().strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return List.copyOf(values);
        }
        if (node.isTextual()) {
            String value = node.asText("").strip();
            if (value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split("[,\\n]"))
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String extractText(JsonNode contentNode) {
        String content = contentNode.asText("");
        if (content.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            String text = node.path("text").asText("");
            return text.isBlank() ? collectText(node).strip() : text;
        } catch (Exception exception) {
            return content;
        }
    }

    private static boolean isTextLikeMessage(String messageType) {
        return "text".equals(messageType) || "post".equals(messageType);
    }

    private static String collectText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            StringBuilder builder = new StringBuilder();
            if (node.has("text")) {
                builder.append(node.path("text").asText(""));
            }
            node.fields().forEachRemaining(entry -> {
                if (!"text".equals(entry.getKey())) {
                    appendText(builder, collectText(entry.getValue()));
                }
            });
            return builder.toString();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            node.forEach(child -> appendText(builder, collectText(child)));
            return builder.toString();
        }
        return "";
    }

    private static void appendText(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text.strip());
    }

    private static String cleanText(String text, JsonNode mentions) {
        String result = text == null ? "" : text;
        if (mentions != null && mentions.isArray()) {
            for (JsonNode mention : mentions) {
                String key = mention.path("key").asText("");
                if (!key.isBlank()) {
                    result = result.replace(key, "");
                }
            }
        }
        return result.strip();
    }

    private static String toTicketId(String messageId) {
        String safe = messageId == null ? "" : messageId.trim().replaceAll("[^A-Za-z0-9]+", "-");
        safe = safe.replaceAll("^-+", "").replaceAll("-+$", "");
        if (safe.isBlank()) {
            safe = UUID.randomUUID().toString().substring(0, 8);
        }
        return "FI-" + safe;
    }

    private static Instant toInstant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Instant.now();
        }
        String text = node.asText("");
        try {
            long value = Long.parseLong(text);
            if (value > 1_000_000_000_000L) {
                return Instant.ofEpochMilli(value);
            }
            return Instant.ofEpochSecond(value);
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    private static Map<String, String> metadata(String chatId, String messageId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("chatId", chatId == null ? "" : chatId);
        metadata.put("messageId", messageId == null ? "" : messageId);
        return Map.copyOf(metadata);
    }

    private static RequirementDraft parseRequirement(String text) {
        String raw = text == null ? "" : text.strip();
        if (raw.isBlank()) {
            return RequirementDraft.notRequirement();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        boolean requirement = false;
        for (String line : raw.lines().map(String::strip).filter(value -> !value.isBlank()).toList()) {
            if (line.equalsIgnoreCase("requirement") || line.contains("做需求") || line.contains("需求任务")) {
                requirement = true;
            }
            ParsedLine parsed = parseLine(line);
            if (!parsed.key().isBlank()) {
                fields.put(normalizeRequirementKey(parsed.key()), parsed.value());
            }
        }
        requirement = requirement || fields.containsKey("requirementDoc") || fields.containsKey("requirementText");
        if (!requirement) {
            return RequirementDraft.notRequirement();
        }
        String title = firstNonBlank(fields.get("title"), fallbackTitle(raw));
        String repositoryUrl = firstNonBlank(fields.get("repositoryUrl"), fields.get("repository"));
        String baseBranch = firstNonBlank(fields.get("baseBranch"), fields.get("branch"), "main");
        String expectedResult = firstNonBlank(fields.get("expectedResult"));
        String priority = firstNonBlank(fields.get("priority"), "P2");
        String requirementDoc = firstNonBlank(fields.get("requirementDoc"), fields.get("sourceUri"));
        String requirementText = firstNonBlank(fields.get("requirementText"), raw);
        TaskMaterialSourceType sourceType = requirementDoc.isBlank()
                ? TaskMaterialSourceType.MANUAL_TEXT
                : TaskMaterialSourceType.FEISHU_DOC;
        String materialTitle = sourceType == TaskMaterialSourceType.FEISHU_DOC ? "飞书需求文档" : "飞书需求正文";
        return new RequirementDraft(
                true,
                title,
                priority,
                repositoryUrl,
                baseBranch,
                expectedResult,
                acceptanceCriteria(fields.get("acceptance")),
                sourceType,
                materialTitle,
                requirementDoc,
                sourceType == TaskMaterialSourceType.FEISHU_DOC ? "" : requirementText
        );
    }

    private static List<String> missingRequirementFields(RequirementDraft draft) {
        List<String> missing = new ArrayList<>();
        if (draft.title().isBlank()) {
            missing.add("title");
        }
        if (draft.repositoryUrl().isBlank()) {
            missing.add("repositoryUrl");
        }
        if (draft.baseBranch().isBlank()) {
            missing.add("baseBranch");
        }
        if (draft.expectedResult().isBlank()) {
            missing.add("expectedResult");
        }
        if (draft.sourceUri().isBlank() && draft.materialContent().isBlank()) {
            missing.add("requirementMaterial");
        }
        return missing;
    }

    private static ParsedLine parseLine(String line) {
        int colon = line.indexOf(':');
        int chineseColon = line.indexOf('：');
        int index;
        if (colon < 0) {
            index = chineseColon;
        } else if (chineseColon < 0) {
            index = colon;
        } else {
            index = Math.min(colon, chineseColon);
        }
        if (index <= 0 || index >= line.length() - 1) {
            return new ParsedLine("", line);
        }
        return new ParsedLine(line.substring(0, index).strip(), line.substring(index + 1).strip());
    }

    private static String normalizeRequirementKey(String key) {
        return switch (key.strip()) {
            case "标题", "title" -> "title";
            case "仓库", "代码仓库", "repository", "repositoryUrl" -> "repositoryUrl";
            case "分支", "基准分支", "branch", "baseBranch" -> "baseBranch";
            case "优先级", "priority" -> "priority";
            case "需求文档", "飞书文档", "requirementDoc", "sourceUri" -> "requirementDoc";
            case "需求", "需求正文", "requirement", "requirementText" -> "requirementText";
            case "预期", "预期结果", "expectedResult" -> "expectedResult";
            case "验收", "验收标准", "acceptance", "acceptanceCriteria" -> "acceptance";
            default -> key.strip();
        };
    }

    private static List<String> acceptanceCriteria(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("[;；\\n]")).stream()
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String fallbackTitle(String raw) {
        return raw.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank() && !value.contains("做需求"))
                .findFirst()
                .orElse("飞书需求任务");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static String preview(String value, int maxChars) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "...";
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record ParsedLine(String key, String value) {
    }

    private record RequirementDraft(
            boolean requirement,
            String title,
            String priority,
            String repositoryUrl,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            TaskMaterialSourceType sourceType,
            String materialTitle,
            String sourceUri,
            String materialContent
    ) {
        private static RequirementDraft notRequirement() {
            return new RequirementDraft(false, "", "P2", "", "main", "", List.of(),
                    TaskMaterialSourceType.MANUAL_TEXT, "", "", "");
        }
    }
}
