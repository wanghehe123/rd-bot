package com.wish.rd.rag.retrieval.navigator;

import com.wish.rd.rag.retrieval.navigator.model.NavigatorCollectedEvidence;

import java.util.List;

/**
 * 证据门：判断当前已收集材料是否足够结束导航。生产默认只做确定性子串匹配，
 * 不把远端正文当指令执行；评测或调用方可注入更严的门。
 */
@FunctionalInterface
public interface NavigatorEvidenceGate {

    /**
     * @param query    当前查询
     * @param evidence 已围栏的证据
     * @return 足够则停止并记 {@code EVIDENCE_SUFFICIENT}
     */
    boolean isSufficient(String query, List<NavigatorCollectedEvidence> evidence);
}
