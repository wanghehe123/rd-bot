package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeVectorRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface KnowledgeVectorMapper extends BaseMapper<KnowledgeVectorRow> {

    @Insert("""
            INSERT INTO knowledge_vectors (id, content, metadata_json, embedding, created_at, updated_at)
            VALUES (#{id}, #{content}, #{metadataJson}::jsonb, #{embedding}::vector, #{createdAt}, #{updatedAt})
            ON CONFLICT (id) DO UPDATE SET
                content = EXCLUDED.content,
                metadata_json = EXCLUDED.metadata_json,
                embedding = EXCLUDED.embedding,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(KnowledgeVectorRow row);

    @Select("""
            SELECT id, content, COALESCE(metadata_json::text, '{}') AS metadata_json, 1.0 AS score
            FROM knowledge_vectors
            ORDER BY id
            """)
    List<KnowledgeVectorRow> selectAllForRetrieval();

    @Select("""
            <script>
            SELECT id, content, COALESCE(metadata_json::text, '{}') AS metadata_json,
                   1 - (embedding &lt;=&gt; #{vectorLiteral}::vector) AS score
            FROM knowledge_vectors
            <where>
                <if test="knowledgeBaseIds != null and knowledgeBaseIds.size() > 0">
                    metadata_json-&gt;&gt;'knowledgeBaseId' IN
                    <foreach collection="knowledgeBaseIds" item="knowledgeBaseId" open="(" separator="," close=")">
                        #{knowledgeBaseId}
                    </foreach>
                </if>
            </where>
            ORDER BY embedding &lt;=&gt; #{vectorLiteral}::vector
            LIMIT #{limit}
            </script>
            """)
    List<KnowledgeVectorRow> vectorSearch(
            @Param("vectorLiteral") String vectorLiteral,
            @Param("knowledgeBaseIds") Collection<String> knowledgeBaseIds,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT id, content, COALESCE(metadata_json::text, '{}') AS metadata_json,
                   CASE WHEN lower(content) LIKE #{keyword} THEN 1.0 ELSE 0.5 END AS score
            FROM knowledge_vectors
            <where>
                <if test="knowledgeBaseIds != null and knowledgeBaseIds.size() > 0">
                    metadata_json-&gt;&gt;'knowledgeBaseId' IN
                    <foreach collection="knowledgeBaseIds" item="knowledgeBaseId" open="(" separator="," close=")">
                        #{knowledgeBaseId}
                    </foreach>
                    AND
                </if>
                (lower(content) LIKE #{keyword} OR lower(metadata_json::text) LIKE #{keyword})
            </where>
            ORDER BY score DESC, id
            LIMIT #{limit}
            </script>
            """)
    List<KnowledgeVectorRow> keywordSearch(
            @Param("keyword") String keyword,
            @Param("knowledgeBaseIds") Collection<String> knowledgeBaseIds,
            @Param("limit") int limit
    );
}
