package com.wish.rd.rag.guidance;

import com.wish.rd.rag.intent.model.NodeScore;

import java.util.List;
import com.wish.rd.rag.guidance.model.GuidanceDecision;

/**
 * 意图歧义引导服务。
 *
 * <p>当头部多个意图得分过于接近时，直接给出检索结果会让用户难以判断归属哪个系统，
 * 因此这类场景下不进入检索，而是返回提示语，要求用户补充目标系统或关键调用链。
 *
 * <p>判定规则：得分>0 的候选数 ≥2，且第一名与第二名得分差 ≤0.5 时，判定为"歧义"，
 * 取前 3 个候选名称拼成引导文案；否则视为无歧义，正常进入检索。
 */
public final class IntentGuidanceService {

    /**
     * 根据意图评分列表做出歧义判定。
     *
     * @param rankedIntents 按得分降序排列的意图评分（来自 {@link com.wish.rd.rag.intent.IntentClassifier}）
     * @return 歧义引导决策：PROMPT（需引导）或 NONE（可直接检索）
     */
    public GuidanceDecision decide(List<NodeScore> rankedIntents) {
        // 只考虑得分>0 的候选
        List<NodeScore> matched = rankedIntents.stream()
                .filter(score -> score.score() > 0.0d)
                .toList();
        // 候选不足两个，无法判断歧义
        if (matched.size() < 2) {
            return GuidanceDecision.none();
        }
        NodeScore first = matched.get(0);
        NodeScore second = matched.get(1);
        // 头部得分差距过小（≤0.5）判定为歧义，引导用户补充信息
        if (first.score() - second.score() <= 0.5d) {
            List<String> candidates = matched.stream()
                    .limit(3)
                    .map(score -> score.node().name())
                    .toList();
            return GuidanceDecision.prompt(
                    "工单描述可能对应多个系统，请补充目标系统或关键调用链。候选：" + String.join("、", candidates),
                    candidates
            );
        }
        return GuidanceDecision.none();
    }
}
