-- WP-6 身份唯一化。必须在存量审计与重复收敛完成后才能应用。
-- 顺序不可颠倒：先把空串身份归一为 NULL，再校验没有未解决的重复活动身份，
-- 最后才建 active-only 唯一索引。中间那一步刻意让迁移失败而不是静默跳过：
-- 唯一索引一旦建成，被它拒绝的写入会变成运行期错误，而操作员此时已经没有
-- 「先看审计、再决定谁存活」的机会。

-- 1) 归一化。Java 侧 PostgresKnowledgeDocumentStore 用 blankToNull 写入，
--    但历史行可能留有空串；空串会被唯一索引当成一个真实身份互相冲突。
UPDATE knowledge_documents
   SET source_identity_key = NULL
 WHERE source_identity_key = '';

-- 2) 审计守卫。存在未解决的重复活动身份时中止，并指出去哪里解决。
DO $$
DECLARE
    duplicate_groups BIGINT;
    sample TEXT;
BEGIN
    SELECT COUNT(*), COALESCE(MIN(knowledge_base_id::text), '')
      INTO duplicate_groups, sample
      FROM (
            SELECT knowledge_base_id
              FROM knowledge_documents
             WHERE source_identity_key IS NOT NULL
               AND deleted_at IS NULL
               AND superseded_by_document_id IS NULL
             GROUP BY knowledge_base_id, source_identity_key
            HAVING COUNT(*) > 1
      ) unresolved;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'WP-6 identity backfill blocked: % unresolved duplicate active source identities remain (for example knowledge base %). Resolve them at /admin/knowledge/<knowledgeBaseId>/openviking (存量审计 tab) or POST /admin/knowledge-base/<knowledgeBaseId>/openviking/inventory/duplicates/resolve before applying this migration.',
            duplicate_groups, sample;
    END IF;
END $$;

-- 3) active-only 唯一身份索引。谓词必须同时排除墓碑与已被取代的行：
--    墓碑保留原身份用于审计，取代后的旧行也保留身份用于溯源，
--    两者都不再是活动身份，不能参与唯一性判定。
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_documents_active_identity
    ON knowledge_documents (knowledge_base_id, source_identity_key)
 WHERE source_identity_key IS NOT NULL
   AND deleted_at IS NULL
   AND superseded_by_document_id IS NULL;
