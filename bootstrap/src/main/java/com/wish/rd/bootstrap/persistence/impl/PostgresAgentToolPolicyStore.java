package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentToolPolicyRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentToolPolicyMapper;
import com.wish.rd.rag.project.agent.AgentToolPolicyStore;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL-backed immutable tool-policy store with hash verification on read. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentToolPolicyStore implements AgentToolPolicyStore {

    private final AgentToolPolicyMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresAgentToolPolicyStore(AgentToolPolicyMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolPolicy save(AgentToolPolicy policy) {
        String json = policyJson(policy);
        AgentToolPolicyRow row = new AgentToolPolicyRow();
        row.policyId = policy.policyId();
        row.version = policy.version();
        row.policyJson = json;
        row.policyHash = PostgresPersistenceSupport.checksum(json);
        row.enabled = policy.enabled();
        mapper.upsert(row);
        return policy;
    }

    @Override
    public Optional<AgentToolPolicy> find(String policyId, long version) {
        return Optional.ofNullable(mapper.find(policyId, version)).map(this::toPolicy);
    }

    @Override
    public Optional<AgentToolPolicy> findLatest(String policyId) {
        return Optional.ofNullable(mapper.findLatest(policyId)).map(this::toPolicy);
    }

    private AgentToolPolicy toPolicy(AgentToolPolicyRow row) {
        String expectedHash = PostgresPersistenceSupport.checksum(row.policyJson);
        String storedHash = row.policyHash == null ? "" : row.policyHash.strip();
        if (!expectedHash.equalsIgnoreCase(storedHash)) {
            if (isSha256Hex(storedHash)) {
                throw new IllegalStateException("agent tool policy hash mismatch: " + row.policyId + "@" + row.version);
            }
            row.policyHash = expectedHash;
            mapper.upsert(row);
        }
        try {
            Map<String, Object> json = objectMapper.readValue(
                    row.policyJson == null ? "{}" : row.policyJson,
                    new TypeReference<>() { }
            );
            return new AgentToolPolicy(
                    row.policyId,
                    row.version == null ? 1L : row.version,
                    stringSet(json.get("hostAllow")),
                    stringSet(json.get("allow")),
                    stringSet(json.get("deny")),
                    Boolean.TRUE.equals(row.enabled)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("failed to deserialize agent tool policy: " + row.policyId, exception);
        }
    }

    private String policyJson(AgentToolPolicy policy) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("hostAllow", policy.hostAllow().stream().sorted().toList());
        json.put("allow", policy.allow().stream().sorted().toList());
        json.put("deny", policy.deny().stream().sorted().toList());
        json.put("effectiveAllow", policy.effectiveAllow());
        json.put("enabled", policy.enabled());
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize agent tool policy", exception);
        }
    }

    private static boolean isSha256Hex(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object item : values) {
            String text = item == null ? "" : item.toString().strip();
            if (!text.isBlank()) result.add(text);
        }
        return Set.copyOf(result);
    }
}
