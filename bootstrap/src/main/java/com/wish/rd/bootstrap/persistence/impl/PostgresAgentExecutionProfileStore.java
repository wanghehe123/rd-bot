package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExecutionProfileMapper;
import com.wish.rd.rag.project.agent.AgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.model.AgentManifestCanonicalJson;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

/** PostgreSQL-backed registered profile and binding store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentExecutionProfileStore implements AgentExecutionProfileStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentExecutionProfileMapper mapper;

    public PostgresAgentExecutionProfileStore(AgentExecutionProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentExecutionProfile save(AgentExecutionProfile profile) {
        AgentExecutionProfileRow existing = mapper.find(profile.profileId());
        if (existing == null) {
            return insert(profile);
        }
        long expectedVersion = existing.version == null ? 1L : existing.version;
        if (mapper.update(toRow(profile), expectedVersion) != 1) {
            throw new IllegalStateException("execution profile version conflict: " + profile.profileId());
        }
        return profile;
    }

    @Override
    public AgentExecutionProfile insert(AgentExecutionProfile profile) {
        if (mapper.insert(toRow(profile)) != 1) {
            throw new IllegalStateException("execution profile insert failed: " + profile.profileId());
        }
        return profile;
    }

    @Override
    public Optional<AgentExecutionProfile> update(AgentExecutionProfile profile, long expectedVersion) {
        return mapper.update(toRow(profile), expectedVersion) == 1
                ? Optional.of(profile)
                : Optional.empty();
    }

    @Override
    public Optional<AgentExecutionProfile> find(String profileId) {
        return Optional.ofNullable(mapper.find(profileId)).map(PostgresAgentExecutionProfileStore::toProfile);
    }

    @Override
    public List<AgentExecutionProfile> listByProject(String projectId) {
        return mapper.listByProject(PostgresPersistenceSupport.parseId(projectId)).stream()
                .map(PostgresAgentExecutionProfileStore::toProfile)
                .toList();
    }

    @Override
    public void bindProjectDefault(String projectId, String role, String profileId) {
        mapper.bindProjectDefault(
                PostgresPersistenceSupport.parseId(projectId), role, profileId
        );
    }

    @Override
    public Optional<String> findProjectDefault(String projectId, String role) {
        return Optional.ofNullable(mapper.findProjectDefault(
                PostgresPersistenceSupport.parseId(projectId), role
        ));
    }

    @Override
    public void setTaskOverride(String taskId, String projectId, String role, String profileId) {
        mapper.setTaskOverride(
                PostgresPersistenceSupport.parseId(taskId),
                PostgresPersistenceSupport.parseId(projectId),
                role,
                profileId
        );
    }

    @Override
    public Optional<String> findTaskOverride(String taskId, String role) {
        return Optional.ofNullable(mapper.findTaskOverride(
                PostgresPersistenceSupport.parseId(taskId), role
        ));
    }

    @Override
    public void clearTaskOverride(String taskId, String role) {
        mapper.clearTaskOverride(PostgresPersistenceSupport.parseId(taskId), role);
    }

    private static AgentExecutionProfileRow toRow(AgentExecutionProfile profile) {
        AgentExecutionProfileRow row = new AgentExecutionProfileRow();
        row.profileId = profile.profileId();
        row.projectId = PostgresPersistenceSupport.parseId(profile.projectId());
        row.role = profile.role();
        row.name = profile.name();
        row.runtimeType = profile.runtimeType().name();
        row.providerProfileId = profile.providerProfileId();
        row.modelOverride = profile.modelOverride();
        row.extensionSetId = profile.extensionSetId();
        row.extensionSetVersion = profile.extensionSetVersion();
        row.toolPolicyId = profile.toolPolicyId();
        row.toolPolicyVersion = profile.toolPolicyVersion();
        row.enabled = profile.enabled();
        row.version = profile.version();
        row.capabilitiesJson = AgentManifestCanonicalJson.canonicalJson(
                profile.capabilities().stream().map(Enum::name).toList()
        );
        return row;
    }

    private static AgentExecutionProfile toProfile(AgentExecutionProfileRow row) {
        return new AgentExecutionProfile(
                row.profileId,
                PostgresPersistenceSupport.idString(row.projectId),
                row.role,
                row.name,
                AgentRuntimeType.parse(row.runtimeType),
                row.providerProfileId,
                row.modelOverride,
                row.extensionSetId,
                row.extensionSetVersion == null ? 0L : row.extensionSetVersion,
                row.toolPolicyId,
                row.toolPolicyVersion == null ? 1L : row.toolPolicyVersion,
                Boolean.TRUE.equals(row.enabled),
                row.version == null ? 1L : row.version,
                capabilities(row.capabilitiesJson)
        );
    }

    private static List<AgentRuntimeCapability> capabilities(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (!node.isArray()) {
                throw new IllegalStateException("execution profile capabilities must be a JSON array");
            }
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    .map(item -> {
                        if (!item.isTextual()) {
                            throw new IllegalStateException("execution profile capability must be a string");
                        }
                        return AgentRuntimeCapability.parse(item.textValue());
                    })
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid execution profile capabilities JSON", exception);
        }
    }
}
