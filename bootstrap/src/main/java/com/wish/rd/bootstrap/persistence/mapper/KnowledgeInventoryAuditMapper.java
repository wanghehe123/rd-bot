package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.InventoryCategoryCountRow;
import com.wish.rd.bootstrap.persistence.entity.InventoryDriftRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 存量审计只读查询。分类 CASE 顺序必须与计划 D5 一致。
 */
@Mapper
public interface KnowledgeInventoryAuditMapper {

    @Select("""
            SELECT classified.category AS category,
                   COUNT(*) AS documentCount
              FROM (
                    SELECT CASE
                             WHEN d.deleted_at IS NOT NULL THEN 'TOMBSTONE'
                             WHEN d.superseded_by_document_id IS NOT NULL THEN 'SUPERSEDED'
                             WHEN d.deleted_at IS NULL
                              AND d.superseded_by_document_id IS NULL
                              AND d.source_identity_key IS NOT NULL
                              AND dup.cnt > 1 THEN 'DUPLICATE_UNRESOLVED'
                             WHEN kb.lifecycle_status IS DISTINCT FROM 'ACTIVE' THEN 'EXCLUDED_BASE_INACTIVE'
                             WHEN d.local_only_override = TRUE THEN 'EXCLUDED_LOCAL_ONLY'
                             WHEN d.chunk_count = 0 OR d.checksum IS NULL OR d.checksum = '' THEN 'EXCLUDED_EMPTY'
                             WHEN b.projection_status IN ('FAILED', 'DEAD_LETTER', 'NEEDS_HUMAN') THEN 'FAILED'
                             WHEN b.projection_status = 'IN_SYNC' THEN 'IN_SYNC'
                             WHEN b.document_id IS NOT NULL THEN 'PROJECTING'
                             ELSE 'PENDING_BACKFILL'
                           END AS category
                      FROM knowledge_documents d
                      JOIN knowledge_bases kb ON kb.id = d.knowledge_base_id
                      LEFT JOIN knowledge_external_index_bindings b
                        ON b.document_id = d.id AND b.provider = 'OPENVIKING'
                      LEFT JOIN (
                            SELECT knowledge_base_id, source_identity_key, COUNT(*) AS cnt
                              FROM knowledge_documents
                             WHERE deleted_at IS NULL
                               AND superseded_by_document_id IS NULL
                               AND source_identity_key IS NOT NULL
                             GROUP BY knowledge_base_id, source_identity_key
                      ) dup ON dup.knowledge_base_id = d.knowledge_base_id
                           AND dup.source_identity_key = d.source_identity_key
                     WHERE d.knowledge_base_id = #{knowledgeBaseId}
              ) classified
             GROUP BY classified.category
            """)
    List<InventoryCategoryCountRow> countByCategory(@Param("knowledgeBaseId") Long knowledgeBaseId);

    @Select("""
            SELECT COUNT(*) FROM knowledge_documents WHERE knowledge_base_id = #{knowledgeBaseId}
            """)
    long countDocuments(@Param("knowledgeBaseId") Long knowledgeBaseId);

    @Select("""
            SELECT d.*
              FROM knowledge_documents d
              JOIN knowledge_bases kb ON kb.id = d.knowledge_base_id
              LEFT JOIN knowledge_external_index_bindings b
                ON b.document_id = d.id AND b.provider = 'OPENVIKING'
             WHERE d.knowledge_base_id = #{knowledgeBaseId}
               AND d.deleted_at IS NULL
               AND d.superseded_by_document_id IS NULL
               AND kb.lifecycle_status = 'ACTIVE'
               AND d.local_only_override = FALSE
               AND d.chunk_count > 0
               AND d.checksum IS NOT NULL AND d.checksum <> ''
               AND b.document_id IS NULL
               AND (
                    d.source_identity_key IS NULL
                    OR NOT EXISTS (
                        SELECT 1
                          FROM knowledge_documents other
                         WHERE other.knowledge_base_id = d.knowledge_base_id
                           AND other.source_identity_key = d.source_identity_key
                           AND other.id <> d.id
                           AND other.deleted_at IS NULL
                           AND other.superseded_by_document_id IS NULL
                    )
               )
               AND d.id > #{afterDocumentId}
             ORDER BY d.id
             LIMIT #{limit}
            """)
    /**
     * 键集分页。首页传 0 而不是 null：文档 id 是正的雪花 id，{@code d.id > 0} 恒真，
     * 而 {@code #{param} IS NULL} 会让 PostgreSQL 无法推断参数类型并直接报错。
     */
    List<KnowledgeDocumentRow> nextBackfillCandidates(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("afterDocumentId") long afterDocumentId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT d.*
              FROM knowledge_documents d
             WHERE d.knowledge_base_id = #{knowledgeBaseId}
               AND d.deleted_at IS NULL
               AND d.superseded_by_document_id IS NULL
               AND d.source_identity_key IS NOT NULL
               AND d.source_identity_key IN (
                    SELECT source_identity_key
                      FROM knowledge_documents
                     WHERE knowledge_base_id = #{knowledgeBaseId}
                       AND deleted_at IS NULL
                       AND superseded_by_document_id IS NULL
                       AND source_identity_key IS NOT NULL
                     GROUP BY source_identity_key
                    HAVING COUNT(*) > 1
               )
             ORDER BY d.source_identity_key,
                      d.last_synced_at DESC NULLS LAST,
                      d.created_at DESC,
                      d.id DESC
            """)
    List<KnowledgeDocumentRow> listDuplicateMembers(@Param("knowledgeBaseId") Long knowledgeBaseId);

    @Select("""
            SELECT b.knowledge_base_id AS knowledgeBaseId,
                   b.document_id AS documentId,
                   b.remote_uri AS remoteUri,
                   b.desired_state AS desiredState
              FROM knowledge_external_index_bindings b
              JOIN knowledge_documents d ON d.id = b.document_id
             WHERE b.knowledge_base_id = #{knowledgeBaseId}
               AND b.desired_state = 'PRESENT'
               AND (d.deleted_at IS NOT NULL OR d.superseded_by_document_id IS NOT NULL)
             ORDER BY b.document_id
             LIMIT #{limit}
            """)
    List<InventoryDriftRow> listLocalOrphanDrift(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
              FROM knowledge_external_index_outbox
             WHERE knowledge_base_id = #{knowledgeBaseId}
               AND status NOT IN ('SUCCEEDED', 'SUPERSEDED', 'DEAD_LETTER')
            """)
    long countInFlightOperations(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
