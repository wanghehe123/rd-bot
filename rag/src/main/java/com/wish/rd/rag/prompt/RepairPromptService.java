package com.wish.rd.rag.prompt;

import com.wish.rd.framework.convention.ChatMessage;
import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.pipeline.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagRequest;
import com.wish.rd.rag.rewrite.QueryRewriteService;
import com.wish.rd.rag.rewrite.RewriteResult;
import com.wish.rd.rag.rewrite.RuleBasedQueryRewriteService;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复场景 Prompt 规划服务：把检索证据、对话记忆、改写后问题组装成最终的系统/用户 Prompt。
 *
 * <p>这是 RAG 流程"检索后"的关键一步，负责把结构化的 {@link RepairContextPackage}
 * 转化为 LLM 可理解的自然语言上下文。生成的 {@link RepairPromptPlan} 包含：
 * <ul>
 *   <li>系统 Prompt：定义助手角色、目标系统、输出约束；</li>
 *   <li>用户 Prompt：按段落组织"对话记忆 / 知识库证据 / 运行日志 / 代码证据 / 拆分问题"；</li>
 *   <li>场景标签：EMPTY（无证据）、KB_ONLY（仅知识库）、REPAIR_MIXED（混合证据）。</li>
 * </ul>
 *
 * <p>段落仅在实际有内容时才输出，避免空段落干扰模型。
 */
public final class RepairPromptService {

    private final QueryRewriteService rewriteService;

    /**
     * @param rewriteService 查询改写服务，为空时退化为不做任何替换的空实现
     */
    public RepairPromptService(QueryRewriteService rewriteService) {
        this.rewriteService = rewriteService == null ? RuleBasedQueryRewriteService.empty() : rewriteService;
    }

    /** 默认工厂方法，等价于直接构造，便于调用处语义清晰。 */
    public static RepairPromptService defaultService(QueryRewriteService rewriteService) {
        return new RepairPromptService(rewriteService);
    }

    /** 无对话历史的便捷重载。 */
    public RepairPromptPlan build(RepairRagRequest request, RepairContextPackage contextPackage) {
        return build(request, contextPackage, List.of());
    }

    /**
     * 构建 Prompt 计划的主方法。
     *
     * @param request         原始修复请求（提供 description）
     * @param contextPackage  RAG 主流程产出的检索上下文
     * @param history         对话历史（含本轮用户消息），拼入"对话记忆"段落
     * @return 完整的 Prompt 计划
     */
    public RepairPromptPlan build(
            RepairRagRequest request,
            RepairContextPackage contextPackage,
            List<ChatMessage> history
    ) {
        List<ChatMessage> safeHistory = history == null ? List.of() : List.copyOf(history);
        // 改写 + 拆分用户问题（结合历史上下文）
        RewriteResult rewrite = rewriteService.rewriteWithSplit(request.description(), safeHistory);
        List<RetrievedChunk> chunks = contextPackage.retrievedChunks();
        // 把证据按 knowledgeType 分流到三类段落
        List<RetrievedChunk> kbChunks = chunks.stream()
                .filter(chunk -> !isRuntimeLog(chunk) && !isCodeSnippet(chunk))
                .toList();
        List<RetrievedChunk> logChunks = chunks.stream().filter(this::isRuntimeLog).toList();
        List<RetrievedChunk> codeChunks = chunks.stream().filter(this::isCodeSnippet).toList();

        // 按固定顺序拼装用户 Prompt 的各段落，同时记录段落名用于 meta 输出
        ArrayList<String> sections = new ArrayList<>();
        StringBuilder userPrompt = new StringBuilder();
        appendMemorySection(userPrompt, sections, safeHistory);
        appendChunkSection(userPrompt, sections, "知识库证据", kbChunks);
        appendChunkSection(userPrompt, sections, "运行日志", logChunks);
        appendChunkSection(userPrompt, sections, "代码证据", codeChunks);
        appendQuestions(userPrompt, sections, rewrite);

        // 根据证据构成判定 Prompt 场景标签
        PromptScene scene = chunks.isEmpty()
                ? PromptScene.EMPTY
                : (logChunks.isEmpty() && codeChunks.isEmpty() ? PromptScene.KB_ONLY : PromptScene.REPAIR_MIXED);

        return new RepairPromptPlan(
                scene,
                rewrite,
                systemPrompt(contextPackage),
                userPrompt.toString().strip(),
                sections,
                chunks.stream().map(RetrievedChunk::chunkId).toList()
        );
    }

    /**
     * 追加"对话记忆"段落：列出历史消息（含本轮用户消息），角色与内容逐条编号。
     * 历史为空时跳过该段落。
     */
    private void appendMemorySection(
            StringBuilder prompt,
            List<String> sections,
            List<ChatMessage> history
    ) {
        if (history.isEmpty()) {
            return;
        }
        appendSectionHeader(prompt, "对话记忆");
        sections.add("对话记忆");
        for (int index = 0; index < history.size(); index++) {
            ChatMessage message = history.get(index);
            prompt.append(index + 1)
                    .append(". [")
                    .append(message.role())
                    .append("] ")
                    .append(message.content())
                    .append('\n');
        }
    }

    /**
     * 追加证据段落（知识库/日志/代码通用）：按编号列出每条证据的类型、来源与内容。
     * 证据为空时跳过该段落。
     */
    private void appendChunkSection(
            StringBuilder prompt,
            List<String> sections,
            String title,
            List<RetrievedChunk> chunks
    ) {
        if (chunks.isEmpty()) {
            return;
        }
        appendSectionHeader(prompt, title);
        sections.add(title);
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            prompt.append(index + 1)
                    .append(". [")
                    .append(chunk.knowledgeType())
                    .append(" | ")
                    .append(chunk.sourceName())
                    .append("] ")
                    .append(chunk.content())
                    .append('\n');
        }
    }

    /**
     * 追加问题段落：拆分出多个子问题时输出"拆分问题"，否则输出"用户问题"（改写后整句）。
     */
    private void appendQuestions(StringBuilder prompt, List<String> sections, RewriteResult rewrite) {
        if (rewrite.subQuestions().size() > 1) {
            appendSectionHeader(prompt, "拆分问题");
            sections.add("拆分问题");
            for (int index = 0; index < rewrite.subQuestions().size(); index++) {
                prompt.append(index + 1)
                        .append(". ")
                        .append(rewrite.subQuestions().get(index))
                        .append('\n');
            }
            return;
        }
        appendSectionHeader(prompt, "用户问题");
        sections.add("用户问题");
        prompt.append(rewrite.rewrittenQuestion()).append('\n');
    }

    /** 写入段落标题【标题】，首段前不额外加空行。 */
    private void appendSectionHeader(StringBuilder prompt, String title) {
        if (!prompt.isEmpty()) {
            prompt.append('\n');
        }
        prompt.append("【").append(title).append("】").append('\n');
    }

    /**
     * 构建系统 Prompt：定义助手角色为"研发修复机器人"，锁定目标系统，
     * 要求"先根因、再方案、最后验证"，并禁止编造证据外的事实。
     */
    private String systemPrompt(RepairContextPackage contextPackage) {
        String target = contextPackage.primaryIntent()
                .map(score -> score.node().name())
                .orElse("未识别系统");
        return """
                你是研发修复机器人。请基于给定知识库、运行日志和代码证据定位问题，输出可执行的修复分析。
                目标系统：%s
                要求：先给出根因判断，再给出最小修复方案和验证建议；不要编造未出现在证据中的事实。
                """.formatted(target).strip();
    }

    /** 判断证据是否为运行日志（knowledgeType=runtime-log）。 */
    private boolean isRuntimeLog(RetrievedChunk chunk) {
        return "runtime-log".equals(chunk.knowledgeType());
    }

    /** 判断证据是否为代码片段（knowledgeType=code-snippet）。 */
    private boolean isCodeSnippet(RetrievedChunk chunk) {
        return "code-snippet".equals(chunk.knowledgeType());
    }
}
