package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;

/**
 * 外部知识索引的领域端口。实现不得把 HTTP/SDK DTO 泄露到本模块，
 * 也不得抛出未分类的远端异常：所有失败都要翻译成
 * {@link com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass}。
 */
public interface ExternalKnowledgeIndexPort {

    /**
     * 远端是否可接收新的写入。不健康时停止派发，但不得丢弃本地 operation。
     *
     * @return 可派发时为 true
     */
    boolean ready();

    /**
     * 提交一次 upsert。返回受理结果，不等待后台任务完成。
     *
     * @param command upsert 意图
     * @return 受理或失败结果
     */
    ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command);

    /**
     * 查询远端后台任务。
     *
     * @param remoteTaskId 远端任务 ID
     * @return 任务观测结果
     */
    ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId);

    /**
     * 按期望 marker 核验远端资源。
     *
     * @param marker 期望值
     * @return 核验结果
     */
    ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker);
}
