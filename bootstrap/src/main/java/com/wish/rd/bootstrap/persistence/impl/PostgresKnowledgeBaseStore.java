package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.wish.rd.bootstrap.persistence.entity.KnowledgeBaseRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeBaseMapper;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeBaseLifecycle;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 知识库 Store。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeBaseStore implements KnowledgeBaseStore {

    private final KnowledgeBaseMapper mapper;

    public PostgresKnowledgeBaseStore(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeBase save(KnowledgeBase base) {
        KnowledgeBaseRow row = toRow(base);
        if (mapper.selectById(row.id) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return base;
    }

    @Override
    public Optional<KnowledgeBase> findById(String id) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(id)))
                .map(this::toBase);
    }

    @Override
    public List<KnowledgeBase> list() {
        return mapper.selectList(null)
                .stream()
                .sorted((left, right) -> {
                    int createdCompare = left.createdAt.compareTo(right.createdAt);
                    return createdCompare != 0 ? createdCompare : left.id.compareTo(right.id);
                })
                .map(this::toBase)
                .toList();
    }

    @Override
    public void delete(String id) {
        mapper.deleteById(PostgresPersistenceSupport.parseId(id));
    }

    private KnowledgeBaseRow toRow(KnowledgeBase base) {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeBaseRow row = new KnowledgeBaseRow();
        row.id = PostgresPersistenceSupport.parseId(base.id());
        row.name = base.name();
        row.description = base.description();
        row.enabled = base.enabled();
        row.createdAt = PostgresPersistenceSupport.toDateTime(base.createdAtEpochMillis());
        row.updatedAt = now;
        row.lifecycleStatus = base.lifecycleStatus().name();
        row.deletedAt = PostgresPersistenceSupport.nullableDateTime(base.deletedAtEpochMillis());
        row.purgeAfter = PostgresPersistenceSupport.nullableDateTime(base.purgeAfterEpochMillis());
        row.syncVersion = base.syncVersion();
        row.rowVersion = base.rowVersion();
        return row;
    }

    private KnowledgeBase toBase(KnowledgeBaseRow row) {
        return new KnowledgeBase(
                PostgresPersistenceSupport.idString(row.id),
                row.name,
                row.description,
                Boolean.TRUE.equals(row.enabled),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                lifecycle(row.lifecycleStatus),
                PostgresPersistenceSupport.toEpochMillis(row.deletedAt),
                PostgresPersistenceSupport.toEpochMillis(row.purgeAfter),
                row.syncVersion == null ? 1L : row.syncVersion,
                row.rowVersion == null ? 0L : row.rowVersion
        );
    }

    private static KnowledgeBaseLifecycle lifecycle(String value) {
        if (value == null || value.isBlank()) {
            return KnowledgeBaseLifecycle.ACTIVE;
        }
        return KnowledgeBaseLifecycle.valueOf(value);
    }
}
