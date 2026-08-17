package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentStrategyProfileRow;
import com.wish.rd.bootstrap.persistence.entity.AgentStrategyRoleSlotRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentStrategyProfileMapper;
import com.wish.rd.rag.project.agent.AgentStrategyProfileStore;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStrategyImageMode;
import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;
import com.wish.rd.rag.project.agent.model.AgentStrategyRoleSlot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL-backed project agent strategy store.
 *
 * <p>Not {@code final} because {@code @Transactional} requires a CGLIB subclass.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresAgentStrategyProfileStore implements AgentStrategyProfileStore {

    private final AgentStrategyProfileMapper mapper;

    /**
     * @param mapper MyBatis mapper for strategy aggregate tables
     */
    public PostgresAgentStrategyProfileStore(AgentStrategyProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AgentStrategyProfile save(AgentStrategyProfile profile) {
        mapper.upsertProfile(toProfileRow(profile));
        mapper.deleteSlots(PostgresPersistenceSupport.parseId(profile.projectId()), profile.strategyId());
        for (AgentStrategyRoleSlot slot : profile.roles()) {
            mapper.insertSlot(toSlotRow(profile.projectId(), profile.strategyId(), slot));
        }
        return profile;
    }

    @Override
    public Optional<AgentStrategyProfile> find(String projectId, String strategyId) {
        AgentStrategyProfileRow header = mapper.findProfile(
                PostgresPersistenceSupport.parseId(projectId),
                strategyId
        );
        if (header == null) {
            return Optional.empty();
        }
        return Optional.of(toProfile(header, mapper.listSlots(
                PostgresPersistenceSupport.parseId(projectId),
                strategyId
        )));
    }

    @Override
    public List<AgentStrategyProfile> listByProject(String projectId) {
        long parsedProjectId = PostgresPersistenceSupport.parseId(projectId);
        List<AgentStrategyProfileRow> headers = mapper.listProfiles(parsedProjectId);
        Map<String, List<AgentStrategyRoleSlotRow>> slotsByStrategy = new LinkedHashMap<>();
        for (AgentStrategyRoleSlotRow slot : mapper.listSlotsByProject(parsedProjectId)) {
            slotsByStrategy.computeIfAbsent(slot.strategyId, ignored -> new ArrayList<>()).add(slot);
        }
        return headers.stream()
                .map(header -> toProfile(header, slotsByStrategy.getOrDefault(header.strategyId, List.of())))
                .toList();
    }

    @Override
    public void bindDefault(String projectId, String strategyId) {
        mapper.bindDefault(PostgresPersistenceSupport.parseId(projectId), strategyId);
    }

    @Override
    public Optional<String> findDefault(String projectId) {
        return Optional.ofNullable(mapper.findDefault(PostgresPersistenceSupport.parseId(projectId)));
    }

    private static AgentStrategyProfileRow toProfileRow(AgentStrategyProfile profile) {
        AgentStrategyProfileRow row = new AgentStrategyProfileRow();
        row.strategyId = profile.strategyId();
        row.projectId = PostgresPersistenceSupport.parseId(profile.projectId());
        row.name = profile.name();
        row.enabled = profile.enabled();
        row.version = profile.version();
        return row;
    }

    private static AgentStrategyRoleSlotRow toSlotRow(String projectId, String strategyId, AgentStrategyRoleSlot slot) {
        AgentStrategyRoleSlotRow row = new AgentStrategyRoleSlotRow();
        row.projectId = PostgresPersistenceSupport.parseId(projectId);
        row.strategyId = strategyId;
        row.role = slot.role();
        row.runtimeType = slot.runtimeType().name();
        row.providerProfileId = slot.providerProfileId();
        row.modelOverride = slot.modelOverride();
        row.extensionSetId = slot.extensionSetId();
        row.extensionSetVersion = slot.extensionSetVersion();
        row.toolPolicyId = slot.toolPolicyId();
        row.toolPolicyVersion = slot.toolPolicyVersion();
        row.imageMode = slot.imageMode().name();
        row.image = slot.image();
        row.dockerfileName = slot.dockerfileName();
        row.dockerfileSha256 = slot.dockerfileSha256();
        row.dockerfileArtifactUri = slot.dockerfileArtifactUri();
        row.dockerfileText = slot.dockerfileText();
        return row;
    }

    private static AgentStrategyProfile toProfile(
            AgentStrategyProfileRow header,
            List<AgentStrategyRoleSlotRow> slotRows
    ) {
        return new AgentStrategyProfile(
                header.strategyId,
                PostgresPersistenceSupport.idString(header.projectId),
                header.name,
                Boolean.TRUE.equals(header.enabled),
                header.version == null ? 1L : header.version,
                slotRows.stream().map(PostgresAgentStrategyProfileStore::toSlot).toList()
        );
    }

    private static AgentStrategyRoleSlot toSlot(AgentStrategyRoleSlotRow row) {
        return new AgentStrategyRoleSlot(
                row.role,
                AgentRuntimeType.parse(row.runtimeType),
                row.providerProfileId,
                row.modelOverride,
                row.extensionSetId,
                row.extensionSetVersion == null ? 0L : row.extensionSetVersion,
                row.toolPolicyId,
                row.toolPolicyVersion == null ? 1L : row.toolPolicyVersion,
                AgentStrategyImageMode.parse(row.imageMode),
                row.image,
                row.dockerfileName,
                row.dockerfileSha256,
                row.dockerfileArtifactUri,
                row.dockerfileText
        );
    }
}
