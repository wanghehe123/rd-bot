package com.wish.rd.bootstrap.openviking;

import java.util.List;

/**
 * 真实语料评测题。expectedDocumentIds 为空表示故意不可答，用来测误引。
 *
 * <p>标注错的题会静默污染由它算出的每个召回数，所以答案文档必须带
 * {@code supportingQuotes}：期望文档里的原文片段。字节级子串断言在
 * {@code tmp/verify_gold_questions.py}（要连库），本类只守结构不变式。
 *
 * @param id                   题号
 * @param type                 SINGLE_DOCUMENT / MULTI_DOCUMENT / UNANSWERABLE
 * @param question             工程师口吻的问法，禁止整段抄正文
 * @param expectedDocumentIds  读过语料后记下的答案文档
 * @param justification        答案落在哪一篇、为何不是邻句改写
 * @param supportingQuotes     每篇答案文档一条原文引用；不可答题为空
 */
record WaimaiGoldQuestion(
        String id,
        String type,
        String question,
        List<String> expectedDocumentIds,
        String justification,
        List<SupportingQuote> supportingQuotes
) {

    /**
     * @param documentId 引用出自哪篇答案文档
     * @param quote      该文档正文里的原文片段
     */
    record SupportingQuote(String documentId, String quote) {
    }
}
