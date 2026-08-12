package com.wish.rd.bootstrap.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.oracle.AssertionSpecCompiler;
import com.wish.rd.engine.oracle.HostAssertionBundleStore;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionRunReport;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host authority boundary for QA assertion contracts.
 *
 * <p>The agent never supplies executable assertion specifications. The Host freezes
 * explicit assertion definitions before QA, persists them by task/stage/scope, and
 * later accepts only matching hash/evidence echoes before executing the stored specs.
 */
public final class HostOwnedAssertionGate {

    public static final long CONTRACT_VERSION = 1L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HostAssertionOracle oracle;
    private final HostAssertionBundleStore bundleStore;
    private final HostVerifierWorkspaceFactory workspaceFactory;
    private final AssertionSpecCompiler compiler;

    /** Compatibility constructor for installations that do not enable Host assertions. */
    public HostOwnedAssertionGate(HostAssertionOracle oracle) {
        this(oracle, HostAssertionBundleStore.unavailable(), HostVerifierWorkspaceFactory.unavailable(),
                new AssertionSpecCompiler());
    }

    /** Creates a Host assertion gate with explicit persistence and clean-workspace dependencies. */
    public HostOwnedAssertionGate(
            HostAssertionOracle oracle,
            HostAssertionBundleStore bundleStore,
            HostVerifierWorkspaceFactory workspaceFactory
    ) {
        this(oracle, bundleStore, workspaceFactory, new AssertionSpecCompiler());
    }

    /** Full constructor retained for focused compiler/gate tests. */
    public HostOwnedAssertionGate(
            HostAssertionOracle oracle,
            HostAssertionBundleStore bundleStore,
            HostVerifierWorkspaceFactory workspaceFactory,
            AssertionSpecCompiler compiler
    ) {
        this.oracle = Objects.requireNonNull(oracle, "oracle must not be null");
        this.bundleStore = bundleStore == null ? HostAssertionBundleStore.unavailable() : bundleStore;
        this.workspaceFactory = workspaceFactory == null ? HostVerifierWorkspaceFactory.unavailable() : workspaceFactory;
        this.compiler = compiler == null ? new AssertionSpecCompiler() : compiler;
    }

    /**
     * Compiles and freezes explicit Host definitions for one immutable QA stage.
     * Legacy natural-language criteria deliberately produce no Host contract.
     */
    public List<FrozenAssertionBundle> freeze(String taskId, String stageRunId, String acceptanceCriteriaJson) {
        if (!compiler.isExplicitHostAssertionDefinition(acceptanceCriteriaJson)) {
            return List.of();
        }
        return freezeCompiled(taskId, stageRunId, compiler.compileByScope(acceptanceCriteriaJson));
    }

    /**
     * Freezes the explicit structured assertion input carried by a requirement task.
     * A non-null task field is an affirmative Host assertion request, so malformed envelopes
     * fail before the QA executor is invoked rather than falling back to natural-language text.
     *
     * @param taskId workflow task identity
     * @param stageRunId immutable QA stage identity
     * @param hostAssertionBundle Host-owned structured assertion envelope, or null for legacy tasks
     * @return immutable stored CURRENT and REGRESSION bundles
     */
    public List<FrozenAssertionBundle> freeze(
            String taskId,
            String stageRunId,
            JsonNode hostAssertionBundle
    ) {
        if (hostAssertionBundle == null || hostAssertionBundle.isNull()) {
            return List.of();
        }
        return freezeCompiled(taskId, stageRunId, compiler.compileByScope(hostAssertionBundle));
    }

    private List<FrozenAssertionBundle> freezeCompiled(
            String taskId,
            String stageRunId,
            Map<String, AssertionSpecBundle> compiled
    ) {
        String safeTaskId = requireText(taskId, "taskId");
        String safeStageRunId = requireText(stageRunId, "stageRunId");
        for (Map.Entry<String, AssertionSpecBundle> entry : compiled.entrySet()) {
            bundleStore.saveIfAbsent(new FrozenAssertionBundle(
                    safeTaskId,
                    safeStageRunId,
                    entry.getKey(),
                    entry.getValue(),
                    CONTRACT_VERSION
            ));
        }
        List<FrozenAssertionBundle> frozen = bundleStore.listByTaskAndStage(safeTaskId, safeStageRunId);
        requireFrozenBundlesMatchCompiledDefinitions(compiled, frozen);
        return List.copyOf(frozen);
    }

    /**
     * Validates agent hash/evidence echoes and runs each persisted Host assertion bundle.
     * A non-empty Host contract fails closed on a missing/mismatched echo, missing evidence,
     * unavailable clean verifier workspace, or non-passing Host assertion.
     */
    public List<String> validate(
            String resultJson,
            String taskId,
            String stageRunId,
            RepairJobCommand command
    ) {
        JsonNode root = parseObject(resultJson);
        String safeTaskId = taskId == null ? "" : taskId.strip();
        String safeStageRunId = stageRunId == null ? "" : stageRunId.strip();
        if (safeTaskId.isBlank() || safeStageRunId.isBlank()) {
            if (root != null) {
                List<String> errors = new ArrayList<>();
                rejectAgentControlledHostFields(root, errors);
                if (root.has("hostAssertionResults")) {
                    errors.add("hostAssertionResults is unexpected without a Host task/stage identity");
                }
                return List.copyOf(errors);
            }
            return List.of();
        }
        List<FrozenAssertionBundle> expected = bundleStore.listByTaskAndStage(safeTaskId, safeStageRunId);
        if (root == null) {
            return expected.isEmpty() ? List.of("QA result JSON must be an object")
                    : List.of("QA result JSON must be an object while Host assertions are required");
        }

        List<String> errors = new ArrayList<>();
        rejectAgentControlledHostFields(root, errors);
        if (expected.isEmpty()) {
            if (root.has("hostAssertionResults")) {
                errors.add("hostAssertionResults is unexpected because no Host assertion bundle is frozen");
            }
            return List.copyOf(errors);
        }
        validateExpectedScopes(expected, errors);
        if (!errors.isEmpty()) {
            return List.copyOf(errors);
        }

        Map<String, AgentEcho> echoes = parseEchoes(root, errors);
        validateEchoesAgainstHostBundles(expected, echoes, root, errors);
        if (!errors.isEmpty()) {
            return List.copyOf(errors);
        }

        for (FrozenAssertionBundle bundle : expected) {
            runHostBundle(bundle, command, errors);
        }
        return List.copyOf(errors);
    }

    /**
     * Legacy overload intentionally cannot execute agent-supplied bundles. New call sites must
     * supply Host task/stage identity and a Host-created verifier command.
     */
    @Deprecated(forRemoval = false)
    public List<String> validate(String resultJson, AssertionEvaluationContext ignoredContext) {
        JsonNode root = parseObject(resultJson);
        if (root != null) {
            List<String> errors = new ArrayList<>();
            rejectAgentControlledHostFields(root, errors);
            if (root.has("hostAssertionResults")) {
                errors.add("hostAssertionResults is unexpected without a Host task/stage identity");
            }
            return List.copyOf(errors);
        }
        return List.of();
    }

    /** Convenience compatibility overload for file-rooted callers. */
    @Deprecated(forRemoval = false)
    public List<String> validate(String resultJson, Path workspaceRoot) {
        return validate(resultJson, AssertionEvaluationContext.of(workspaceRoot));
    }

    private void runHostBundle(
            FrozenAssertionBundle bundle,
            RepairJobCommand command,
            List<String> errors
    ) {
        HostVerifierWorkspace workspace = null;
        try {
            workspace = workspaceFactory.create(
                    command,
                    new HostVerifierWorkspaceRequest(
                            bundle.taskId(),
                            bundle.stageRunId(),
                            bundle.scope(),
                            requiresRuntime(bundle)
                    )
            );
            if (workspace == null) {
                errors.add("hostAssertion[" + bundle.scope() + "] Host verifier workspace factory returned null");
                return;
            }
            AssertionEvaluationContext context = new AssertionEvaluationContext(
                    workspace.workspaceRoot(),
                    workspace.baseUrl(),
                    workspace.attributes()
            );
            AssertionRunReport report = oracle.evaluate(bundle.bundle(), context);
            if (report.passed()) {
                return;
            }
            for (AssertionResult result : report.results()) {
                if (result.outcome() != AssertionOutcome.PASSED) {
                    errors.add("hostAssertion[" + bundle.scope() + "/" + result.assertionId() + "] "
                            + result.outcome() + ": " + result.message());
                }
            }
            if (report.results().isEmpty() && !report.failureSummary().isBlank()) {
                errors.add("hostAssertion[" + bundle.scope() + "] " + report.failureSummary());
            }
        } catch (Exception exception) {
            errors.add("hostAssertion[" + bundle.scope() + "] Host verifier workspace failed: "
                    + safeMessage(exception));
        } finally {
            if (workspace != null) {
                try {
                    workspace.close();
                } catch (Exception exception) {
                    errors.add("hostAssertion[" + bundle.scope() + "] Host verifier runtime cleanup failed: "
                            + safeMessage(exception));
                }
            }
        }
    }

    private static boolean requiresRuntime(FrozenAssertionBundle bundle) {
        return bundle != null && bundle.bundle().specs().stream().anyMatch(spec -> {
            String type = spec.assertionType().name();
            return type.startsWith("HTTP_") || type.startsWith("BROWSER_");
        });
    }

    private static void validateExpectedScopes(List<FrozenAssertionBundle> bundles, List<String> errors) {
        Set<String> scopes = new HashSet<>();
        for (FrozenAssertionBundle bundle : bundles) {
            if (!scopes.add(bundle.scope())) {
                errors.add("Host assertion store contains duplicate scope: " + bundle.scope());
            }
        }
        if (!scopes.contains("CURRENT") || !scopes.contains("REGRESSION")) {
            errors.add("Host assertion store must contain both CURRENT and REGRESSION bundles");
        }
    }

    private static void requireFrozenBundlesMatchCompiledDefinitions(
            Map<String, AssertionSpecBundle> compiled,
            List<FrozenAssertionBundle> frozen
    ) {
        Map<String, FrozenAssertionBundle> frozenByScope = new HashMap<>();
        for (FrozenAssertionBundle bundle : frozen == null ? List.<FrozenAssertionBundle>of() : frozen) {
            FrozenAssertionBundle previous = frozenByScope.putIfAbsent(bundle.scope(), bundle);
            if (previous != null) {
                throw new IllegalStateException(
                        "Host assertion bundle store returned duplicate " + bundle.scope() + " bundles"
                );
            }
        }
        for (Map.Entry<String, AssertionSpecBundle> entry : compiled.entrySet()) {
            FrozenAssertionBundle bundle = frozenByScope.get(entry.getKey());
            if (bundle == null) {
                throw new IllegalStateException(
                        "Host assertion bundle store did not preserve the " + entry.getKey() + " bundle"
                );
            }
            if (!entry.getValue().contentHash().equals(bundle.contentHash())) {
                throw new IllegalStateException(
                        "Host assertion bundle store returned a different frozen " + entry.getKey() + " bundle"
                );
            }
        }
        if (frozenByScope.size() != compiled.size()) {
            throw new IllegalStateException("Host assertion bundle store returned unexpected frozen bundles");
        }
    }

    private static Map<String, AgentEcho> parseEchoes(JsonNode root, List<String> errors) {
        JsonNode results = root.get("hostAssertionResults");
        if (results == null || !results.isArray() || results.isEmpty()) {
            errors.add("hostAssertionResults is required when Host assertion bundles are frozen");
            return Map.of();
        }
        Map<String, AgentEcho> echoes = new HashMap<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode entry = results.get(index);
            String prefix = "hostAssertionResults[" + index + "]";
            if (entry == null || !entry.isObject()) {
                errors.add(prefix + " must be an object");
                continue;
            }
            entry.fieldNames().forEachRemaining(field -> {
                if (!"scope".equals(field) && !"contentHash".equals(field)
                        && !"evidenceArtifactIds".equals(field)) {
                    errors.add(prefix + " may contain only scope, contentHash, and evidenceArtifactIds");
                }
            });
            String scope;
            try {
                scope = FrozenAssertionBundle.normalizeScope(text(entry.path("scope")));
            } catch (IllegalArgumentException exception) {
                errors.add(prefix + ".scope must be CURRENT or REGRESSION");
                continue;
            }
            String contentHash = text(entry.path("contentHash"));
            if (contentHash.isBlank()) {
                errors.add(prefix + ".contentHash must not be blank");
            }
            Set<String> evidence = stringSet(entry.path("evidenceArtifactIds"), prefix + ".evidenceArtifactIds", errors);
            AgentEcho previous = echoes.putIfAbsent(scope, new AgentEcho(scope, contentHash, evidence));
            if (previous != null) {
                errors.add("hostAssertionResults contains duplicate " + scope + " scope");
            }
        }
        return Map.copyOf(echoes);
    }

    private static void rejectAgentControlledHostFields(JsonNode root, List<String> errors) {
        if (root == null || !root.isObject()) {
            return;
        }
        if (root.has("hostAssertionBundle")) {
            errors.add("hostAssertionBundle is not accepted; agents must not submit executable assertion specs");
        }
        if (root.has("hostAssertionWorkspace")) {
            errors.add("hostAssertionWorkspace is not accepted; Host selects the verifier workspace");
        }
        if (root.has("hostAssertionBaseUrl")) {
            errors.add("hostAssertionBaseUrl is not accepted; Host selects the verifier base URL");
        }
        if (root.has("hostAssertionContext")) {
            errors.add("hostAssertionContext is not accepted; Host creates assertion evaluation context");
        }
    }

    private static void validateEchoesAgainstHostBundles(
            List<FrozenAssertionBundle> expected,
            Map<String, AgentEcho> echoes,
            JsonNode result,
            List<String> errors
    ) {
        Set<String> hostScopes = new HashSet<>();
        for (FrozenAssertionBundle bundle : expected) {
            hostScopes.add(bundle.scope());
            AgentEcho echo = echoes.get(bundle.scope());
            if (echo == null) {
                errors.add("hostAssertionResults is missing " + bundle.scope() + " Host bundle echo");
                continue;
            }
            if (!bundle.contentHash().equals(echo.contentHash())) {
                errors.add("hostAssertionResults[" + bundle.scope()
                        + "].contentHash does not match the frozen Host bundle");
            }
            Set<String> scopeEvidence = acceptanceEvidenceForScope(result, bundle.scope());
            if (scopeEvidence.isEmpty()) {
                errors.add("acceptanceResults has no evidence references for Host assertion scope " + bundle.scope());
                continue;
            }
            for (String evidenceRef : echo.evidenceArtifactIds()) {
                if (!scopeEvidence.contains(evidenceRef)) {
                    errors.add("hostAssertionResults[" + bundle.scope() + "] evidence '" + evidenceRef
                            + "' is not referenced by " + bundle.scope() + " acceptanceResults");
                }
            }
        }
        for (String scope : echoes.keySet()) {
            if (!hostScopes.contains(scope)) {
                errors.add("hostAssertionResults contains an unexpected " + scope + " scope");
            }
        }
    }

    private static Set<String> acceptanceEvidenceForScope(JsonNode root, String scope) {
        Set<String> evidence = new LinkedHashSet<>();
        JsonNode acceptance = root.path("acceptanceResults");
        if (!acceptance.isArray()) {
            return evidence;
        }
        for (JsonNode item : acceptance) {
            if (!item.isObject() || !scope.equalsIgnoreCase(text(item.path("scope")))) {
                continue;
            }
            String log = text(item.path("logArtifactId"));
            if (!log.isBlank()) {
                evidence.add(log);
            }
            JsonNode artifactIds = item.path("evidenceArtifactIds");
            if (artifactIds.isArray()) {
                for (JsonNode artifactId : artifactIds) {
                    String value = text(artifactId);
                    if (!value.isBlank()) {
                        evidence.add(value);
                    }
                }
            }
        }
        return Set.copyOf(evidence);
    }

    private static Set<String> stringSet(JsonNode node, String field, List<String> errors) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            errors.add(field + " must be a non-empty array");
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String value = text(node.get(index));
            if (value.isBlank()) {
                errors.add(field + "[" + index + "] must not be blank");
            } else {
                values.add(value);
            }
        }
        return Set.copyOf(values);
    }

    private static JsonNode parseObject(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isTextual() || node.isNumber() || node.isBoolean() ? node.asText("").strip() : "";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record AgentEcho(String scope, String contentHash, Set<String> evidenceArtifactIds) {
        private AgentEcho {
            scope = scope == null ? "" : scope;
            contentHash = contentHash == null ? "" : contentHash;
            evidenceArtifactIds = evidenceArtifactIds == null ? Set.of() : Set.copyOf(evidenceArtifactIds);
        }
    }
}
