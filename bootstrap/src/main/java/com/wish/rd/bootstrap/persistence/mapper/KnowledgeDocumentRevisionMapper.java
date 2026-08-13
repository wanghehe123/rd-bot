package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRevisionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentRevisionMapper extends BaseMapper<KnowledgeDocumentRevisionRow> {

    @Insert("""
            INSERT INTO knowledge_document_revisions (
                id, document_id, sync_version, source_revision_id, checksum,
                canonical_mime_type, canonical_content, parser_name, parser_version, created_at
            )
            VALUES (
                #{id}, #{documentId}, #{syncVersion}, #{sourceRevisionId}, #{checksum},
                #{canonicalMimeType}, #{canonicalContent}, #{parserName}, #{parserVersion}, #{createdAt}
            )
            ON CONFLICT (document_id, checksum) DO UPDATE SET
                source_revision_id = EXCLUDED.source_revision_id
            """)
    void upsert(KnowledgeDocumentRevisionRow row);
}
