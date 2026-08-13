package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("knowledge_document_revisions")
public class KnowledgeDocumentRevisionRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long documentId;
    public Long syncVersion;
    public String sourceRevisionId;
    public String checksum;
    public String canonicalMimeType;
    public String canonicalContent;
    public String parserName;
    public String parserVersion;
    public OffsetDateTime createdAt;
}
