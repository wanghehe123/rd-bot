package com.wish.rd.rag.intent;

import com.wish.rd.rag.text.TextAnalyzer;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 意图分类器：基于规则的关键词匹配，对意图树所有节点评分并排序。
 *
 * <p>这是 RAG 主流程的第一步——把工单文本归到某个目标系统/意图。
 * MVP 未引入模型推理，而是用"术语重叠 + 名称命中 + 示例命中"的确定性打分：
 * <ul>
 *   <li>查询与节点画像的术语重叠：每个匹配术语得 1.0 分，长度≥4 的长术语加权到 1.4 分；</li>
 *   <li>节点名称整串出现在工单中：额外 +4.0 分；</li>
 *   <li>节点示例出现在工单中：每个 +3.0 分。</li>
 * </ul>
 *
 * <p>得分排序后，{@link com.wish.rd.rag.pipeline.RepairRagPipeline} 取首个得分>0 的节点作为主意图。
 */
public final class IntentClassifier {

    private final IntentTree intentTree;

    public IntentClassifier(IntentTree intentTree) {
        this.intentTree = intentTree;
    }

    /**
     * 对工单文本做意图打分排序。
     *
     * @param ticketText 工单描述（可能含日志合并后的文本）
     * @return 按得分降序排列的节点评分列表
     */
    public List<NodeScore> rank(String ticketText) {
        Set<String> queryTerms = TextAnalyzer.terms(ticketText);
        return intentTree.nodes().stream()
                .map(node -> score(node, ticketText, queryTerms))
                .sorted(Comparator.comparingDouble(NodeScore::score).reversed())
                .toList();
    }

    /**
     * 计算单个意图节点的得分。
     *
     * <p>三类加分项叠加：术语重叠分 + 名称命中分 + 示例命中分。
     *
     * @param node       待评分的意图节点
     * @param ticketText 工单文本
     * @param queryTerms 工单切出的术语集合（避免重复切词）
     * @return 节点评分（含命中术语列表）
     */
    private NodeScore score(IntentNode node, String ticketText, Set<String> queryTerms) {
        Set<String> nodeTerms = TextAnalyzer.terms(node.profileText());
        // 找出查询与节点画像都出现的术语
        List<String> matchedTerms = queryTerms.stream()
                .filter(nodeTerms::contains)
                .toList();
        // 术语重叠分：长术语（≥4字符）加权，鼓励匹配专有名词而非停用词
        double score = matchedTerms.stream()
                .mapToDouble(term -> term.length() >= 4 ? 1.4d : 1.0d)
                .sum();
        // 名称整串命中：强信号，大幅加权
        if (ticketText != null && ticketText.contains(node.name())) {
            score += 4.0d;
        }
        // 示例命中：中等强度信号
        for (String example : node.examples()) {
            if (example != null && !example.isBlank() && ticketText != null && ticketText.contains(example)) {
                score += 3.0d;
            }
        }
        return new NodeScore(node, score, matchedTerms);
    }
}
