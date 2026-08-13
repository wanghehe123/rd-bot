-- WP-6 身份唯一化。必须在存量审计与重复收敛完成后才能应用。
-- 顺序不可颠倒：先装身份函数，再把空串身份归一为 NULL，再校验没有未解决的重复
-- 活动身份，最后才建 active-only 唯一索引。中间那一步刻意让迁移失败而不是静默
-- 跳过：唯一索引一旦建成，被它拒绝的写入会变成运行期错误，而操作员此时已经没有
-- 「先看审计、再决定谁存活」的机会。

-- 1) 身份函数。它是 SQL 侧「生效身份」的唯一定义，必须与
--    rag/src/main/java/com/wish/rd/rag/knowledge/SourceIdentityKeys.java 逐字节一致：
--    同样的默认 LOCAL、同样的大写与去空白、同样的 token 优先于 url、
--    同样以 \0 分隔后取 SHA-256 的十六进制。
--
--    存量行的 source_identity_key 还是 NULL，但它们的 source_token/source_url 可能
--    早就撞在一起了。只看 source_identity_key 的话，这种「潜在重复」会被判成
--    PENDING_BACKFILL：回填给第一篇写上身份后第二篇撞唯一索引，而它既不会变成
--    DUPLICATE_UNRESOLVED（身份仍为 NULL）也永远回填不成功。所以审计、候选查询与
--    下面的守卫都必须按生效身份判定，而不是按已落库的列。
--
--    假设：source_type/source_token/source_url 是 ASCII。Java 用 Locale.ROOT 大写与
--    Unicode-aware strip，SQL 用 upper/btrim，两者只在非 ASCII 空白或土耳其语 i 这类
--    输入上才会分叉；来源字段由应用产生，不含这类字符。
CREATE OR REPLACE FUNCTION knowledge_source_identity_key(
    source_type TEXT,
    source_token TEXT,
    source_url TEXT
) RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
             WHEN btrim(COALESCE(source_token, '')) <> '' THEN
                 encode(sha256(
                     convert_to(canonical_type, 'UTF8')
                     || '\x00'::bytea || convert_to('token', 'UTF8')
                     || '\x00'::bytea || convert_to(btrim(source_token), 'UTF8')
                 ), 'hex')
             WHEN btrim(COALESCE(source_url, '')) <> '' THEN
                 encode(sha256(
                     convert_to(canonical_type, 'UTF8')
                     || '\x00'::bytea || convert_to('url', 'UTF8')
                     || '\x00'::bytea || convert_to(btrim(source_url), 'UTF8')
                 ), 'hex')
           END
      FROM (
            SELECT CASE
                     WHEN btrim(COALESCE(source_type, '')) = '' THEN 'LOCAL'
                     ELSE upper(btrim(source_type))
                   END AS canonical_type
      ) canonical
$$;

COMMENT ON FUNCTION knowledge_source_identity_key(TEXT, TEXT, TEXT) IS
    'WP-6 生效身份键，必须与 Java SourceIdentityKeys 一致；本地匿名上传返回 NULL。';

-- 2) 归一化。Java 侧 PostgresKnowledgeDocumentStore 用 blankToNull 写入，
--    但历史行可能留有空串；空串会被唯一索引当成一个真实身份互相冲突。
UPDATE knowledge_documents
   SET source_identity_key = NULL
 WHERE source_identity_key = '';

-- 3) 审计守卫。存在未解决的重复活动身份时中止，并指出去哪里解决。
--    按生效身份判定，因此已落库的重复与尚未回填的潜在重复都会被拦住。
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
             WHERE deleted_at IS NULL
               AND superseded_by_document_id IS NULL
               AND COALESCE(
                       NULLIF(source_identity_key, ''),
                       knowledge_source_identity_key(source_type, source_token, source_url)
                   ) IS NOT NULL
             GROUP BY knowledge_base_id,
                      COALESCE(
                          NULLIF(source_identity_key, ''),
                          knowledge_source_identity_key(source_type, source_token, source_url)
                      )
            HAVING COUNT(*) > 1
      ) unresolved;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'WP-6 identity backfill blocked: % unresolved duplicate active source identities remain (for example knowledge base %). Resolve them at /admin/knowledge/<knowledgeBaseId>/openviking (存量审计 tab) or POST /admin/knowledge-base/<knowledgeBaseId>/openviking/inventory/duplicates/resolve before applying this migration.',
            duplicate_groups, sample;
    END IF;
END $$;

-- 4) active-only 唯一身份索引。索引守的是应用写进来的那一列：身份值的权威在 Java，
--    SQL 只负责在回填前发现尚未落库的碰撞（第 3 步），不在这里第二次充当权威。
--    谓词必须同时排除墓碑与已被取代的行：墓碑保留原身份用于审计，取代后的旧行也
--    保留身份用于溯源，两者都不再是活动身份，不能参与唯一性判定。
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_documents_active_identity
    ON knowledge_documents (knowledge_base_id, source_identity_key)
 WHERE source_identity_key IS NOT NULL
   AND deleted_at IS NULL
   AND superseded_by_document_id IS NULL;
