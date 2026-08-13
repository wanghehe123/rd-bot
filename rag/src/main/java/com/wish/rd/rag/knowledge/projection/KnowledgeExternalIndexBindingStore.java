package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部索引 desired/observed 绑定。
 */
public interface KnowledgeExternalIndexBindingStore {

    KnowledgeExternalIndexBinding save(KnowledgeExternalIndexBinding binding);

    /**
     * 以 CAS 写入观测结果。只更新 observed/projection/remote/last_* 列，
     * 绝不改 {@code desired_*}：desired 由本地 mutation 事务拥有，
     * Worker/Poller 覆盖它会把刚提交的 v2 意图悄悄退回 v1。
     *
     * @param binding            携带新观测值的绑定
     * @param expectedRowVersion 期望的当前 rowVersion
     * @return CAS 成功时返回新绑定；版本不符或行不存在时为空
     */
    Optional<KnowledgeExternalIndexBinding> saveObservationIfVersionMatches(
            KnowledgeExternalIndexBinding binding,
            long expectedRowVersion
    );

    Optional<KnowledgeExternalIndexBinding> findByProviderAndDocumentId(String provider, String documentId);

    /**
     * 将远端命中 URI 解析为绑定：在同一 provider 下，取 {@code remote_uri} 为该 URI
     * 最长路径前缀的一行。
     *
     * <p>路径前缀指规范化后相等，或命中以 {@code remote_uri + '/'} 开头。
     * 因此 {@code .../documents/12} 不会匹配 {@code .../documents/123/...}。
     * 命中若带 {@code ..}，先按投影 URI 规则规范化再匹配，避免裸前缀钉死穿越前的目录。
     *
     * <p>实现必须走有界的祖先等值查询（命中路径深度通常 7～9 段），
     * 禁止 {@code hit LIKE remote_uri || '%'} 全表扫描。空白或非法 URI 返回空，
     * 不得把命中当成数字 id 去 {@code parseId}。
     *
     * @param provider 提供方，空白时按 OPENVIKING
     * @param hitUri   远端命中 URI，可以是文档根或其派生子路径
     * @return 最长匹配的绑定；没有前缀命中时为空
     */
    Optional<KnowledgeExternalIndexBinding> findByProviderAndLongestRemoteUriPrefix(String provider, String hitUri);

    /**
     * 一个知识库的绑定，可按投影状态过滤。
     *
     * @param provider          提供方
     * @param knowledgeBaseId   知识库 ID
     * @param projectionStatus  可选状态过滤，null 表示不过滤
     * @param offset            偏移
     * @param limit             最多返回条数
     * @return 匹配的绑定，新的在前
     */
    List<KnowledgeExternalIndexBinding> findByKnowledgeBase(
            String provider,
            String knowledgeBaseId,
            ExternalKnowledgeProjectionStatus projectionStatus,
            int offset,
            int limit
    );

    /**
     * 一个知识库各 projection_status 的行数。
     *
     * @param provider        提供方
     * @param knowledgeBaseId 知识库 ID
     * @return 状态到计数
     */
    Map<ExternalKnowledgeProjectionStatus, Long> countByProjectionStatus(String provider, String knowledgeBaseId);

    List<KnowledgeExternalIndexBinding> listAll();

    void delete(String provider, String documentId);

    /**
     * 插入绑定，已存在时不覆盖 {@code observed_*}。
     *
     * @return 新插入时为 true，已存在时为 false
     */
    boolean insertIfAbsent(KnowledgeExternalIndexBinding binding);
}
