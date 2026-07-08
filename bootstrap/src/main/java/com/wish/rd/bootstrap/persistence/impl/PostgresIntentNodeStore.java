package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.IntentNodeRow;
import com.wish.rd.bootstrap.persistence.mapper.IntentNodeMapper;
import com.wish.rd.rag.intent.IntentNodeStore;
import com.wish.rd.rag.intent.model.ManagedIntentNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 意图节点存储适配器。
 *
 * <p>供管理台意图树配置持久化到 {@code t_intent_node}，避免重启后回退为
 * JVM 内存默认节点。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresIntentNodeStore implements IntentNodeStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final IntentNodeMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresIntentNodeStore(IntentNodeMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ManagedIntentNode save(ManagedIntentNode node) {
        IntentNodeRow row = toRow(node);
        IntentNodeRow existing = mapper.selectById(row.id);
        if (existing == null) {
            mapper.insert(row);
        } else {
            row.createTime = existing.createTime;
            row.createBy = existing.createBy;
            mapper.updateById(row);
        }
        return toNode(row);
    }

    @Override
    public Optional<ManagedIntentNode> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id))
                .filter(row -> row.deleted == null || row.deleted == 0)
                .map(this::toNode);
    }

    @Override
    public Optional<ManagedIntentNode> findByIntentCode(String intentCode) {
        String safeCode = intentCode == null ? "" : intentCode.strip();
        if (safeCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<IntentNodeRow>()
                        .eq("intent_code", safeCode)
                        .eq("deleted", 0)
                        .last("LIMIT 1")))
                .map(this::toNode);
    }

    @Override
    public List<ManagedIntentNode> list() {
        return mapper.selectList(new QueryWrapper<IntentNodeRow>().eq("deleted", 0))
                .stream()
                .map(this::toNode)
                .sorted(Comparator.comparingInt(ManagedIntentNode::sortOrder)
                        .thenComparing(ManagedIntentNode::id))
                .toList();
    }

    @Override
    public void delete(String id) {
        IntentNodeRow row = mapper.selectById(id);
        if (row == null) {
            return;
        }
        row.deleted = 1;
        row.updateTime = LocalDateTime.now();
        mapper.updateById(row);
    }

    private IntentNodeRow toRow(ManagedIntentNode node) {
        LocalDateTime now = LocalDateTime.now();
        IntentNodeRow row = new IntentNodeRow();
        row.id = node.id();
        row.kbId = nullToEmpty(node.kbId());
        row.intentCode = node.intentCode();
        row.name = node.name();
        row.level = node.level();
        row.parentCode = nullToEmpty(node.parentCode());
        row.description = nullToEmpty(node.description());
        row.examples = writeExamples(node.examples());
        row.collectionName = nullToEmpty(node.kbId());
        row.topK = 5;
        row.mcpToolId = "";
        row.kind = 0;
        row.promptSnippet = "";
        row.promptTemplate = "";
        row.paramPromptTemplate = "";
        row.sortOrder = node.sortOrder();
        row.enabled = node.enabled();
        row.createBy = "rd-bot";
        row.updateBy = "rd-bot";
        row.createTime = now;
        row.updateTime = now;
        row.deleted = 0;
        return row;
    }

    private ManagedIntentNode toNode(IntentNodeRow row) {
        return new ManagedIntentNode(
                nullToEmpty(row.id),
                nullToEmpty(row.intentCode),
                nullToEmpty(row.name),
                row.level == null ? 2 : row.level,
                blankToNull(row.parentCode),
                nullToEmpty(row.description),
                firstNonBlank(row.kbId, row.collectionName),
                readExamples(row.examples),
                List.of(),
                row.enabled == null ? 1 : row.enabled,
                row.sortOrder == null ? 0 : row.sortOrder,
                List.of()
        );
    }

    private String writeExamples(List<String> examples) {
        try {
            return objectMapper.writeValueAsString(examples == null ? List.of() : examples);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize intent examples", exception);
        }
    }

    private List<String> readExamples(String examples) {
        if (examples == null || examples.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(examples, STRING_LIST);
        } catch (JsonProcessingException exception) {
            return List.of(examples);
        }
    }

    private String firstNonBlank(String first, String second) {
        String normalizedFirst = blankToNull(first);
        if (normalizedFirst != null) {
            return normalizedFirst;
        }
        return blankToNull(second);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
