package com.wish.rd.engine.bugfix;

import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlan;
import com.wish.rd.engine.rag.model.BugFixMessage;
import com.wish.rd.framework.convention.model.RetrievedChunk;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Bug 修复执行 Prompt 构建器。
 *
 * <p>供 {@link RdBotFixEngine} 将 RAG 上下文打包成稳定模板，后续执行器只消费纯文本 Prompt。
 */
@Component
public final class BugFixPromptBuilder {

    private static final String TEMPLATE_PATH = "prompt/bugfix.st";

    /**
     * 创建默认 Prompt 构建器。
     *
     * @return 默认构建器
     */
    public static BugFixPromptBuilder defaultBuilder() {
        return new BugFixPromptBuilder();
    }

    /**
     * 构建 Bug 修复执行 Prompt。
     *
     * @param message RAG 上下文消息
     * @return 执行 Prompt
     */
    public String build(BugFixMessage message) {
        BugFixMessage safeMessage = message == null
                ? emptyMessage()
                : message;
        return build(safeMessage, AcceptancePlan.disabled(
                safeMessage.taskId(),
                safeMessage.ticketId(),
                "acceptance planner is not configured"
        ));
    }

    /**
     * 构建带验收计划的 Bug 修复执行 Prompt。
     *
     * @param message        RAG 上下文消息
     * @param acceptancePlan 已校验的验收计划
     * @return 执行 Prompt
     */
    public String build(BugFixMessage message, AcceptancePlan acceptancePlan) {
        BugFixMessage safeMessage = message == null
                ? emptyMessage()
                : message;
        AcceptancePlan safePlan = acceptancePlan == null
                ? AcceptancePlan.disabled(safeMessage.taskId(), safeMessage.ticketId(),
                "acceptance planner is not configured")
                : acceptancePlan;
        return render(template(), safeMessage, safePlan);
    }

    private String render(String template, BugFixMessage message, AcceptancePlan acceptancePlan) {
        Map<String, String> values = Map.ofEntries(
                Map.entry("taskId", message.taskId()),
                Map.entry("ticketId", message.ticketId()),
                Map.entry("ticketTitle", message.ticketTitle()),
                Map.entry("ticketDescription", message.ticketDescription()),
                Map.entry("ticketLabels", String.join(",", message.ticketLabels())),
                Map.entry("deepThinking", Boolean.toString(message.deepThinking())),
                Map.entry("primaryIntentSystemId", message.primaryIntentSystemId()),
                Map.entry("primaryIntentName", message.primaryIntentName()),
                Map.entry("guidanceAction", message.guidanceAction()),
                Map.entry("guidancePrompt", message.guidancePrompt()),
                Map.entry("searchChannels", String.join(",", message.searchChannels())),
                Map.entry("contextSummary", message.contextSummary()),
                Map.entry("evidence", evidence(message)),
                Map.entry("acceptancePlan", acceptancePlan.toPromptSection()),
                Map.entry("agentSystemMessage", message.agentSystemMessage()),
                Map.entry("agentUserMessage", message.agentUserMessage())
        );
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered.strip();
    }

    private String template() {
        try (InputStream stream = BugFixPromptBuilder.class.getClassLoader().getResourceAsStream(TEMPLATE_PATH)) {
            if (stream == null) {
                return fallbackTemplate();
            }
            String loaded = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return loaded.isBlank() ? fallbackTemplate() : loaded;
        } catch (Exception exception) {
            return fallbackTemplate();
        }
    }

    private String fallbackTemplate() {
        return """
                # 研发修复任务

                ## 工单上下文
                - 任务ID：{{taskId}}
                - 工单ID：{{ticketId}}
                - 标题：{{ticketTitle}}
                - 描述：{{ticketDescription}}
                - 标签：{{ticketLabels}}
                - 深度思考：{{deepThinking}}

                ## RAG 结论
                - 主系统：{{primaryIntentSystemId}}
                - 主意图：{{primaryIntentName}}
                - 引导动作：{{guidanceAction}}
                - 引导提示：{{guidancePrompt}}
                - 检索通道：{{searchChannels}}

                {{contextSummary}}

                ## 检索证据
                {{evidence}}

                {{acceptancePlan}}

                ## 执行边界
                只修改与工单直接相关的代码，并输出可解析的 JSON 结果。
                如果验证命令依赖的工具链缺失，必须先按需安装缺失工具链再执行测试。当前隔离容器允许使用 `sudo apt-get update` 和 `sudo apt-get install -y <packages>` 安装开源构建工具，例如 Java 17、Maven、Node.js 或 npm。不要因为缺少工具链直接跳过测试；只有安装失败、包不可用或外部服务缺失时，才在测试日志中说明原因。

                ## Agent 系统消息
                {{agentSystemMessage}}

                ## Agent 用户消息
                {{agentUserMessage}}

                ## 结构化输出 JSON
                {"taskId":"{{taskId}}","bugDescription":"","solution":"","pullRequestUrl":"","testSummary":""}
                """;
    }

    private String evidence(BugFixMessage message) {
        if (message.retrievedChunks().isEmpty()) {
            return "未检索到证据。";
        }
        return message.retrievedChunks().stream()
                .map(this::toEvidenceLine)
                .collect(Collectors.joining("\n"));
    }

    private String toEvidenceLine(RetrievedChunk chunk) {
        return "- [%s | %s | score=%.2f] %s".formatted(
                chunk.knowledgeType(),
                chunk.sourceName(),
                chunk.score(),
                chunk.content()
        );
    }

    private BugFixMessage emptyMessage() {
        return new BugFixMessage(
                "",
                "",
                "",
                List.of(),
                "",
                false,
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                "",
                "",
                "",
                List.of(),
                List.of(),
                "",
                false
        );
    }
}
