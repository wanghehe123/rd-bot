package com.wish.rd.rag.intent;

import com.wish.rd.rag.intent.model.ManagedIntentNode;

import java.util.List;
import java.util.Optional;

/**
 * 意图节点存储端口。
 *
 * <p>由 {@link IntentTreeRegistry} 依赖，生产环境通过 bootstrap PostgreSQL 适配器落库；
 * 本地 memory 模式和单测可使用内存实现。
 */
public interface IntentNodeStore {

    /**
     * 保存意图节点。
     *
     * @param node 去掉 children 后的意图节点
     * @return 已保存节点
     */
    ManagedIntentNode save(ManagedIntentNode node);

    /**
     * 按 ID 查询意图节点。
     *
     * @param id 节点 ID
     * @return 节点
     */
    Optional<ManagedIntentNode> findById(String id);

    /**
     * 按业务编码查询意图节点。
     *
     * @param intentCode 意图编码
     * @return 节点
     */
    Optional<ManagedIntentNode> findByIntentCode(String intentCode);

    /**
     * 查询全部未删除节点。
     *
     * @return 节点快照
     */
    List<ManagedIntentNode> list();

    /**
     * 删除意图节点。
     *
     * @param id 节点 ID
     */
    void delete(String id);
}
