package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.HostAssertionBundleRow;
import com.wish.rd.bootstrap.persistence.mapper.HostAssertionBundleMapper;
import com.wish.rd.engine.oracle.HostAssertionBundleStore;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** PostgreSQL implementation of the immutable Host assertion contract store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresHostAssertionBundleStore implements HostAssertionBundleStore {

    private static final TypeReference<List<AssertionSpec>> ASSERTION_SPECS = new TypeReference<>() { };

    private final HostAssertionBundleMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresHostAssertionBundleStore(HostAssertionBundleMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public FrozenAssertionBundle saveIfAbsent(FrozenAssertionBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("bundle must not be null");
        }
        long taskId = PostgresPersistenceSupport.parseId(bundle.taskId());
        long stageRunId = PostgresPersistenceSupport.parseId(bundle.stageRunId());
        mapper.insertIfAbsent(toRow(bundle));
        HostAssertionBundleRow stored = mapper.find(taskId, stageRunId, bundle.scope());
        if (stored == null) {
            throw new IllegalStateException("Host assertion bundle was not persisted");
        }
        return toBundle(stored);
    }

    @Override
    public Optional<FrozenAssertionBundle> find(String taskId, String stageRunId, String scope) {
        HostAssertionBundleRow row = mapper.find(
                PostgresPersistenceSupport.parseId(taskId),
                PostgresPersistenceSupport.parseId(stageRunId),
                FrozenAssertionBundle.normalizeScope(scope)
        );
        return Optional.ofNullable(row).map(this::toBundle);
    }

    @Override
    public List<FrozenAssertionBundle> listByTaskAndStage(String taskId, String stageRunId) {
        return mapper.listByTaskAndStage(
                        PostgresPersistenceSupport.parseId(taskId),
                        PostgresPersistenceSupport.parseId(stageRunId)
                ).stream()
                .map(this::toBundle)
                .toList();
    }

    private HostAssertionBundleRow toRow(FrozenAssertionBundle bundle) {
        HostAssertionBundleRow row = new HostAssertionBundleRow();
        row.taskId = PostgresPersistenceSupport.parseId(bundle.taskId());
        row.stageRunId = PostgresPersistenceSupport.parseId(bundle.stageRunId());
        row.scope = bundle.scope();
        row.canonicalSpecsJson = write(bundle.bundle().specs());
        row.contentHash = bundle.contentHash();
        row.version = bundle.version();
        row.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        return row;
    }

    private FrozenAssertionBundle toBundle(HostAssertionBundleRow row) {
        if (row == null) {
            throw new IllegalArgumentException("Host assertion bundle row must not be null");
        }
        try {
            List<AssertionSpec> specs = objectMapper.readValue(row.canonicalSpecsJson, ASSERTION_SPECS);
            return new FrozenAssertionBundle(
                    PostgresPersistenceSupport.idString(row.taskId),
                    PostgresPersistenceSupport.idString(row.stageRunId),
                    row.scope,
                    new AssertionSpecBundle(specs, row.contentHash),
                    row.version == null ? 0L : row.version
            );
        } catch (Exception exception) {
            throw new IllegalStateException("stored Host assertion bundle is invalid", exception);
        }
    }

    private String write(List<AssertionSpec> specs) {
        try {
            return objectMapper.writeValueAsString(specs);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize Host assertion bundle", exception);
        }
    }
}
