package com.wish.rd.bootstrap.openviking;

import java.util.List;

/**
 * 真实语料评测题。expectedDocumentIds 为空表示故意不可答，用来测误引。
 *
 * @param id                   题号
 * @param type                 SINGLE_DOCUMENT / MULTI_DOCUMENT / UNANSWERABLE
 * @param question             工程师口吻的问法，禁止整段抄正文
 * @param expectedDocumentIds  读过语料后记下的答案文档
 * @param justification        答案落在哪一篇、为何不是邻句改写
 */
record WaimaiGoldQuestion(
        String id,
        String type,
        String question,
        List<String> expectedDocumentIds,
        String justification
) {
}
