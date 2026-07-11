package com.wish.rd.rag.rewrite.impl;

import com.wish.rd.rag.rewrite.QueryRewriteService;
import com.wish.rd.rag.rewrite.QueryTermMappingUtil;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import com.wish.rd.rag.rewrite.model.QueryTermMapping;
import com.wish.rd.rag.rewrite.model.RewriteResult;

/**
 * 基于规则的查询改写服务：把用户口语化的术语按映射表替换为标准术语，并可拆分子问题。
 *
 * <p>这是 RAG 流程"检索前"的关键一步，用于把"下单→POST /api/orders"、"金额→orders.amount"
 * 这类业务术语标准化，提升检索召回率。/rag/v3/chat 的 meta.rewrittenQuestion 即由此产生。
 *
 * <p>映射应用顺序：先按 priority 降序、再按 sourceTerm 长度降序排列，保证高优先级、
 * 更长的术语优先匹配，避免短词误伤（例如"金额"先于"额"）。
 */
public final class RuleBasedQueryRewriteService implements QueryRewriteService {

    /** 子问题切分的分隔符：问号、句号、分号、换行（中英文皆支持）。 */
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[?？。；;\\n]+");

    private final List<QueryTermMapping> mappings;

    /**
     * 构造时即完成映射过滤与排序：仅保留启用项，按优先级与术语长度降序。
     *
     * @param mappings 原始映射列表（含禁用项），允许为空
     */
    public RuleBasedQueryRewriteService(List<QueryTermMapping> mappings) {
        this(mappings, false);
    }

    private RuleBasedQueryRewriteService(List<QueryTermMapping> mappings, boolean preserveInputOrder) {
        List<QueryTermMapping> enabledMappings = mappings == null ? List.of() : mappings.stream()
                .filter(QueryTermMapping::enabled)
                .toList();
        this.mappings = preserveInputOrder
                ? enabledMappings
                : enabledMappings.stream()
                        .sorted(Comparator.comparingInt(QueryTermMapping::priority).reversed()
                                .thenComparing(mapping -> mapping.sourceTerm().length(), Comparator.reverseOrder()))
                        .toList();
    }

    /** Keeps a caller-provided deterministic policy order, such as project rules before globals. */
    public static RuleBasedQueryRewriteService inOrder(List<QueryTermMapping> mappings) {
        return new RuleBasedQueryRewriteService(mappings, true);
    }

    /** 创建一个空映射的改写服务（不做任何替换）。 */
    public static RuleBasedQueryRewriteService empty() {
        return new RuleBasedQueryRewriteService(List.of());
    }

    /**
     * 纯改写：按映射顺序逐条替换术语，返回改写后的完整问题。
     *
     * @param userQuestion 用户原始问题
     * @return 改写后的问题；输入为空则返回空串
     */
    @Override
    public String rewrite(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return "";
        }
        String rewritten = userQuestion.strip();
        for (QueryTermMapping mapping : mappings) {
            rewritten = QueryTermMappingUtil.applyMapping(rewritten, mapping.sourceTerm(), mapping.targetTerm());
        }
        return rewritten;
    }

    /**
     * 改写 + 拆分：先改写整句，再按分隔符切成多个子问题，每个补全问号。
     *
     * <p>拆分用于把"复合问题"拆成可独立检索的子问题，Prompt 规划时作为"拆分问题"段落输出。
     * 无分隔符时退化为只含改写后整句的单元素列表。
     *
     * @param userQuestion 用户原始问题
     * @return 改写后的问题与拆分后的子问题列表
     */
    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        String rewritten = rewrite(userQuestion);
        List<String> subQuestions = SPLIT_PATTERN.splitAsStream(rewritten)
                .map(String::strip)
                .filter(part -> !part.isBlank())
                // 统一以问号结尾，便于作为独立子问题呈现
                .map(part -> part.endsWith("?") || part.endsWith("？") ? part : part + "？")
                .toList();
        // 兜底：改写后无分隔符时，整句作为一个子问题
        if (subQuestions.isEmpty() && !rewritten.isBlank()) {
            subQuestions = List.of(rewritten);
        }
        return new RewriteResult(rewritten, subQuestions);
    }
}
