package com.wish.rd.rag.knowledge.projection;

/**
 * Mutation 提交之后的唤醒钩子。实现必须在事务外调用；拒绝入队不得回滚 PENDING。
 */
@FunctionalInterface
public interface KnowledgeProjectionWakePort {

    void wake();

    static KnowledgeProjectionWakePort noop() {
        return () -> {
        };
    }
}
