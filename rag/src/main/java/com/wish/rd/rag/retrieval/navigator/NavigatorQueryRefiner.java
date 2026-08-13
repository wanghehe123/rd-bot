package com.wish.rd.rag.retrieval.navigator;

import com.wish.rd.rag.retrieval.navigator.model.NavigatorCollectedEvidence;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorRoundRecord;

import java.util.List;

/**
 * 一轮结束后若证据不足，生成下一轮查询。只能改查询文本，不能改工具权限或目标 URI。
 */
@FunctionalInterface
public interface NavigatorQueryRefiner {

    /**
     * @param currentQuery 本轮查询
     * @param round        刚结束的一轮留证，只有计数没有正文
     * @param collected    到此刻已收集的证据；改写要转向它没覆盖到的部分，
     *                     所以必须看得见正文，只给 {@code round} 是改不出方向的
     * @return 下一轮查询；空白视为需要用户补充
     */
    String refine(String currentQuery, NavigatorRoundRecord round, List<NavigatorCollectedEvidence> collected);
}
