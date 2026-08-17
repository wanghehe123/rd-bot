package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStrategyConsole;
import com.wish.rd.rag.project.agent.model.AgentStrategyImageMode;
import com.wish.rd.rag.project.agent.model.AgentStrategyProfile;
import com.wish.rd.rag.project.agent.model.AgentStrategyRoleSlot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validates project agent strategies and projects them onto per-role execution profiles. */
public final class AgentStrategyProfileService {

    public static final String LEGACY_STRATEGY_ID = "legacy-current";
    public static final List<String> DELIVERY_ROLES = List.of(
            "REQUIREMENT_REVIEWER",
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
    );
    private static final Set<String> ROLE_SET = new LinkedHashSet<>(DELIVERY_ROLES);

    private final AgentStrategyProfileStore store;
    private final AgentExecutionProfileService profileService;

    /**
     * @param store strategy aggregate persistence
     * @param profileService existing per-role execution-profile service used for projection
     */
    public AgentStrategyProfileService(
            AgentStrategyProfileStore store,
            AgentExecutionProfileService profileService
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
    }

    /**
     * Lists stored strategies, or synthesizes a read-only legacy view when none exist.
     *
     * @param projectId project id
     * @return console payload
     */
    public AgentStrategyConsole list(String projectId) {
        String safeProjectId = requireText(projectId, "projectId");
        List<AgentStrategyProfile> stored = store.listByProject(safeProjectId).stream()
                .map(AgentStrategyProfile::withoutDockerfileText)
                .toList();
        if (!stored.isEmpty()) {
            return new AgentStrategyConsole(
                    safeProjectId,
                    store.findDefault(safeProjectId).orElse(""),
                    false,
                    stored
            );
        }
        AgentStrategyProfile synthesized = synthesizeLegacy(safeProjectId).withoutDockerfileText();
        return new AgentStrategyConsole(safeProjectId, LEGACY_STRATEGY_ID, true, List.of(synthesized));
    }

    /**
     * Returns one stored strategy, or the synthesized legacy view.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @return strategy
     */
    public AgentStrategyProfile get(String projectId, String strategyId) {
        String safeProjectId = requireText(projectId, "projectId");
        String safeStrategyId = requireText(strategyId, "strategyId");
        if (LEGACY_STRATEGY_ID.equals(safeStrategyId) && store.listByProject(safeProjectId).isEmpty()) {
            return synthesizeLegacy(safeProjectId);
        }
        return requireStored(safeProjectId, safeStrategyId);
    }

    /**
     * Saves a four-role strategy and projects it onto execution profiles.
     *
     * @param profile complete strategy
     * @return stored strategy
     */
    public AgentStrategyProfile save(AgentStrategyProfile profile) {
        AgentStrategyProfile safe = requireComplete(profile);
        rejectLegacyId(safe.strategyId());
        boolean firstDefault = store.findDefault(safe.projectId()).isEmpty();
        boolean alreadyDefault = store.findDefault(safe.projectId()).filter(safe.strategyId()::equals).isPresent();
        AgentStrategyProfile stored = store.save(safe);
        projectSlots(stored);
        if (firstDefault || alreadyDefault) {
            bindProjectedRoles(stored);
            store.bindDefault(stored.projectId(), stored.strategyId());
        }
        return stored;
    }

    /**
     * Binds every delivery role default to this strategy's projected profiles.
     *
     * @param projectId project id
     * @param strategyId strategy id
     */
    public void bindDefault(String projectId, String strategyId) {
        String safeProjectId = requireText(projectId, "projectId");
        String safeStrategyId = requireText(strategyId, "strategyId");
        rejectLegacyId(safeStrategyId);
        AgentStrategyProfile profile = requireStored(safeProjectId, safeStrategyId);
        if (!profile.enabled()) {
            throw new IllegalArgumentException("execution strategy is disabled");
        }
        projectSlots(profile);
        bindProjectedRoles(profile);
        store.bindDefault(safeProjectId, safeStrategyId);
    }

    /**
     * Records a custom Dockerfile against one role slot.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @param role delivery role
     * @param image resolved image tag, may be blank for Pi persist-only
     * @param dockerfileName original file name
     * @param dockerfileSha256 content hash
     * @param dockerfileArtifactUri stored artifact URI
     * @param dockerfileText raw Dockerfile text
     * @return updated strategy
     */
    public AgentStrategyProfile applyCustomImage(
            String projectId,
            String strategyId,
            String role,
            String image,
            String dockerfileName,
            String dockerfileSha256,
            String dockerfileArtifactUri,
            String dockerfileText
    ) {
        AgentStrategyProfile current = requireStored(requireText(projectId, "projectId"), requireText(strategyId, "strategyId"));
        rejectLegacyId(current.strategyId());
        AgentStrategyRoleSlot slot = current.slot(role);
        if (slot.runtimeType() == AgentRuntimeType.MODEL_ONLY) {
            throw new IllegalArgumentException(slot.role() + " MODEL_ONLY does not use a runtime image");
        }
        AgentStrategyProfile updated = store.save(current.withSlot(slot.withCustomImage(
                image,
                dockerfileName,
                dockerfileSha256,
                dockerfileArtifactUri,
                dockerfileText
        )));
        projectSlots(updated);
        return updated;
    }

    /**
     * Restores a role slot to the host default image.
     *
     * @param projectId project id
     * @param strategyId strategy id
     * @param role delivery role
     * @return updated strategy
     */
    public AgentStrategyProfile clearCustomImage(String projectId, String strategyId, String role) {
        AgentStrategyProfile current = requireStored(requireText(projectId, "projectId"), requireText(strategyId, "strategyId"));
        rejectLegacyId(current.strategyId());
        AgentStrategyProfile updated = store.save(current.withSlot(current.slot(role).withLocalDefaultImage()));
        projectSlots(updated);
        return updated;
    }

    private void projectSlots(AgentStrategyProfile profile) {
        for (AgentStrategyRoleSlot slot : profile.roles()) {
            profileService.register(new AgentExecutionProfile(
                    AgentStrategyProfile.projectedProfileId(profile.projectId(), profile.strategyId(), slot.role()),
                    profile.projectId(),
                    slot.role(),
                    profile.name(),
                    slot.runtimeType(),
                    slot.providerProfileId(),
                    slot.modelOverride(),
                    slot.extensionSetId(),
                    slot.extensionSetVersion(),
                    slot.toolPolicyId(),
                    slot.toolPolicyVersion(),
                    profile.enabled(),
                    profile.version()
            ));
        }
    }

    private void bindProjectedRoles(AgentStrategyProfile profile) {
        for (AgentStrategyRoleSlot slot : profile.roles()) {
            profileService.bindProjectDefault(
                    profile.projectId(),
                    slot.role(),
                    AgentStrategyProfile.projectedProfileId(profile.projectId(), profile.strategyId(), slot.role())
            );
        }
    }

    private AgentStrategyProfile synthesizeLegacy(String projectId) {
        List<AgentStrategyRoleSlot> slots = new ArrayList<>();
        for (String role : DELIVERY_ROLES) {
            Optional<AgentExecutionProfile> bound = profileService.findProjectDefault(projectId, role);
            if (bound.isPresent()) {
                AgentExecutionProfile profile = bound.get();
                slots.add(new AgentStrategyRoleSlot(
                        role,
                        profile.runtimeType(),
                        profile.providerProfileId(),
                        profile.modelOverride(),
                        profile.extensionSetId(),
                        profile.extensionSetVersion(),
                        profile.toolPolicyId(),
                        profile.toolPolicyVersion(),
                        AgentStrategyImageMode.LOCAL_DEFAULT,
                        "",
                        "",
                        "",
                        "",
                        ""
                ));
            } else {
                slots.add(new AgentStrategyRoleSlot(
                        role,
                        AgentRuntimeType.PI,
                        "",
                        "",
                        "",
                        0L,
                        "legacy-host-bound",
                        1L,
                        AgentStrategyImageMode.LOCAL_DEFAULT,
                        "",
                        "",
                        "",
                        "",
                        ""
                ));
            }
        }
        return new AgentStrategyProfile(LEGACY_STRATEGY_ID, projectId, "当前默认", true, 1L, slots);
    }

    private AgentStrategyProfile requireStored(String projectId, String strategyId) {
        return store.find(projectId, strategyId)
                .orElseThrow(() -> new IllegalArgumentException("execution strategy not found: " + strategyId));
    }

    private static AgentStrategyProfile requireComplete(AgentStrategyProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("execution strategy must not be null");
        }
        requireText(profile.strategyId(), "strategyId");
        if (!AgentStrategyProfile.isPersistableStrategyId(profile.strategyId())) {
            throw new IllegalArgumentException("strategyId must match [A-Za-z0-9._-]+ and be at most 64 characters");
        }
        rejectLegacyId(profile.strategyId());
        requireText(profile.projectId(), "projectId");
        requireText(profile.name(), "name");
        if (profile.roles().size() != DELIVERY_ROLES.size()) {
            throw new IllegalArgumentException("strategy must include all four delivery roles");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<AgentStrategyRoleSlot> normalized = new ArrayList<>();
        for (AgentStrategyRoleSlot slot : profile.roles()) {
            if (!ROLE_SET.contains(slot.role())) {
                throw new IllegalArgumentException("role must be a requirement delivery role");
            }
            if (!seen.add(slot.role())) {
                throw new IllegalArgumentException("duplicate strategy role " + slot.role());
            }
            if (slot.providerProfileId().isBlank()) {
                throw new IllegalArgumentException(slot.role() + " providerProfileId must not be blank");
            }
            if (slot.toolPolicyId().isBlank()) {
                throw new IllegalArgumentException(slot.role() + " toolPolicyId must not be blank");
            }
            if (slot.runtimeType() == AgentRuntimeType.MODEL_ONLY
                    || slot.imageMode() == AgentStrategyImageMode.LOCAL_DEFAULT) {
                normalized.add(slot.withLocalDefaultImage());
            } else {
                normalized.add(slot);
            }
        }
        if (!seen.equals(ROLE_SET)) {
            throw new IllegalArgumentException("strategy must include all four delivery roles");
        }
        return new AgentStrategyProfile(
                profile.strategyId(),
                profile.projectId(),
                profile.name(),
                profile.enabled(),
                profile.version(),
                normalized
        );
    }

    private static void rejectLegacyId(String strategyId) {
        if (LEGACY_STRATEGY_ID.equals(strategyId)) {
            throw new IllegalArgumentException("legacy-current is read-only; save a new strategy id");
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
