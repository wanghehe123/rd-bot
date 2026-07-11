package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.QueryTermMappingRow;
import com.wish.rd.bootstrap.persistence.mapper.QueryTermMappingMapper;
import com.wish.rd.rag.rewrite.QueryTermMappingStore;
import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed production truth for query term mappings. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresQueryTermMappingStore implements QueryTermMappingStore {

    private final QueryTermMappingMapper mapper;

    public PostgresQueryTermMappingStore(QueryTermMappingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ManagedQueryTermMapping save(ManagedQueryTermMapping mapping) {
        QueryTermMappingRow row = new QueryTermMappingRow();
        row.id = PostgresPersistenceSupport.parseId(mapping.id());
        row.projectId = mapping.projectId().isBlank() ? null : PostgresPersistenceSupport.parseId(mapping.projectId());
        row.scope = mapping.scope().name();
        row.sourceTerm = mapping.sourceTerm();
        row.targetTerm = mapping.targetTerm();
        row.priority = mapping.priority();
        row.enabled = mapping.enabled();
        row.remark = mapping.remark();
        row.createdAt = PostgresPersistenceSupport.toDateTime(mapping.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(mapping.updatedAtEpochMillis());
        mapper.upsert(row);
        return mapping;
    }

    @Override
    public Optional<ManagedQueryTermMapping> findById(String id) {
        return Optional.ofNullable(mapper.findById(PostgresPersistenceSupport.parseId(id))).map(this::toMapping);
    }

    @Override
    public List<ManagedQueryTermMapping> list() {
        return mapper.list().stream().map(this::toMapping).toList();
    }

    @Override
    public void delete(String id) {
        mapper.deleteById(PostgresPersistenceSupport.parseId(id));
    }

    private ManagedQueryTermMapping toMapping(QueryTermMappingRow row) {
        return new ManagedQueryTermMapping(
                PostgresPersistenceSupport.idString(row.id),
                row.projectId == null ? "" : PostgresPersistenceSupport.idString(row.projectId),
                QueryTermMappingScope.valueOf(row.scope),
                row.sourceTerm,
                row.targetTerm,
                row.priority == null ? 0 : row.priority,
                Boolean.TRUE.equals(row.enabled),
                row.remark,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }
}
