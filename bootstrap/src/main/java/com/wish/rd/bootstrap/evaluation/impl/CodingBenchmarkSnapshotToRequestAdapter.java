package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkCaseRuntime;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArmProfile;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArmProfiles;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Assembles an immutable {@link CodingBenchmarkExecutionRequest} from a snapshot's runtime
 * descriptors, a trial, and a freshly materialised per-trial workspace.
 */
public class CodingBenchmarkSnapshotToRequestAdapter {

    private static final String REQUEST_JSON = "request.json";
    private static final String ARM_PROFILE_JSON = "arm-profile.json";
    private static final String RESOURCE_MANIFEST_JSON = "resource-manifest.json";
    private static final String PROBLEM_MD = "problem.md";
    private static final String PATCH_PATH = "candidate.patch";
    private static final String PATCH_ARTIFACT_PATH = "/work/output/candidate.patch";
    private static final String RELAY_TOKEN_ENV = "RD_EVAL_MODEL_RELAY_TOKEN";
    private static final int RELAY_PORT = 8765;
    /**
     * Turn budget for coding-benchmark agent sessions. Multi-role arms (B/C/D) explore longer;
     * 40 was too tight and produced mid-tool kills without {@code result.json}.
     */
    private static final int DEFAULT_MAX_AGENT_TURNS = 80;
    private static final long DEFAULT_MAX_TOTAL_TOKENS = 2_000_000L;

    private final Path outputRoot;
    private final Path prepRoot;
    private final EvaluationProperties.CodingBenchmark.ModelProvider modelProvider;
    private final Supplier<String> relayTokenSupplier;
    private final ObjectMapper mapper;

    public CodingBenchmarkSnapshotToRequestAdapter(
            Path outputRoot,
            EvaluationProperties properties,
            Supplier<String> relayTokenSupplier,
            ObjectMapper mapper
    ) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.prepRoot = properties.resolvedCodingBenchmarkPrepRoot();
        this.modelProvider = properties.getCodingBenchmark().getModelProvider();
        this.relayTokenSupplier = Objects.requireNonNull(relayTokenSupplier, "relayTokenSupplier must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public TrialWorkspace materialiseTrialWorkspace(CodingBenchmarkTrial trial, CodingBenchmarkCaseRuntime runtime) throws IOException {
        Path trialRoot = trialWorkspaceRoot(trial);
        Path repo = trialRoot.resolve("repo");
        Path verifier = trialRoot.resolve("verifier");
        Path cache = trialRoot.resolve("cache");
        Path verifierCache = trialRoot.resolve("verifier-cache");
        Path output = trialRoot.resolve("output");
        Files.createDirectories(trialRoot);
        Files.createDirectories(output);
        cloneOrCopyDir(runtime.agentRepository(), repo);
        // Oracle must never mutate the shared prepared tree: it applies withheld tests in-place.
        cloneOrCopyDir(runtime.verifierRepository(), verifier);
        cloneOrCopyDir(runtime.agentCache(), cache);
        cloneOrCopyDir(runtime.verifierCache(), verifierCache);
        return new TrialWorkspace(repo, verifier, cache, verifierCache, output, trialRoot);
    }

    public CodingBenchmarkExecutionRequest adapt(
            CodingBenchmarkTrial trial,
            TrialWorkspace workspace,
            CodingBenchmarkCaseRuntime runtime
    ) {
        Objects.requireNonNull(trial, "trial must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");

        CodingBenchmarkArm arm = trial.arm();
        CodingBenchmarkArmProfile profile = CodingBenchmarkArmProfiles.of(arm);

        Path inputDir = workspace.trialRoot().resolve("input");
        Path armProfileFile = inputDir.resolve(ARM_PROFILE_JSON);
        Path requestJsonFile = inputDir.resolve(REQUEST_JSON);
        Path resourceManifestFile = inputDir.resolve(RESOURCE_MANIFEST_JSON);

        try {
            writeArmProfile(armProfileFile, profile);
            writeResourceManifest(resourceManifestFile);
            writeProblemStatement(inputDir.resolve(PROBLEM_MD), trial.caseId());
            writeAgentRequest(requestJsonFile, trial, profile);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write trial input files", exception);
        }

        Path agentOutput = workspace.outputDir();
        Path oracleOutput = workspace.trialRoot().resolve("oracle-output");
        try {
            Files.createDirectories(oracleOutput);
            try {
                Files.setPosixFilePermissions(oracleOutput, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystems still accept Docker Desktop bind writes for the trial user
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to prepare oracle output directory", exception);
        }
        Path candidatePatch = agentOutput.resolve(PATCH_PATH);

        return new CodingBenchmarkExecutionRequest(
                trial,
                runtime.agentImage(),
                runtime.oracleImage(),
                workspace.repoDir(),
                workspace.cacheDir(),
                agentOutput,
                workspace.verifierDir(),
                workspace.verifierCacheDir(),
                candidatePatch,
                runtime.protectedTestBundle(),
                runtime.protectedTestPatch(),
                runtime.protectedTestTarget(),
                oracleOutput,
                runtime.agentCommand(),
                runtime.oracleCommand(),
                relayTokenSupplier.get(),
                runtime.agentTimeoutMillis(),
                runtime.oracleTimeoutMillis()
        );
    }

    private Path trialWorkspaceRoot(CodingBenchmarkTrial trial) {
        return outputRoot
                .resolve(trial.campaignId())
                .resolve(trial.trialId())
                .toAbsolutePath()
                .normalize();
    }

    private void writeArmProfile(Path path, CodingBenchmarkArmProfile profile) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, profile.toJson(), StandardCharsets.UTF_8);
    }

    private void writeResourceManifest(Path path) throws IOException {
        Map<String, Object> manifest = Map.of(
                "protocol", "rd-agent-resource-manifest/v1",
                "extensionSetId", "empty",
                "extensionSetVersion", 1,
                "verificationStatus", "VERIFIED",
                "resources", List.of()
        );
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
    }

    private void writeAgentRequest(Path path, CodingBenchmarkTrial trial, CodingBenchmarkArmProfile profile) {
        String role = selectCodingRole(profile);
        String relayHost = CodingBenchmarkTrialNaming.relayHostname(trial);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("protocol", "rd-pi-request/v1");
        request.put("snapshotId", trial.campaignId());
        request.put("stageRunId", trial.trialId());
        request.put("taskId", "coding-benchmark-" + trial.caseId());
        request.put("role", role);
        request.put("prompt", buildPrompt(trial, profile));
        request.put("provider", modelProvider.getName());
        request.put("model", modelProvider.getModel());
        request.put("api", modelProvider.piApi());
        request.put("baseUrl", "http://" + relayHost + ":" + RELAY_PORT);
        request.put("authHeader", true);
        request.put("credentialEnvironmentVariable", RELAY_TOKEN_ENV);
        request.put("repoPath", "/work/repo");
        request.put("inputPath", "/work/input");
        request.put("outputPath", "/work/output");
        request.put("resourceManifestPath", "/work/input/resource-manifest.json");
        request.put("patchArtifactPath", PATCH_ARTIFACT_PATH);
        request.put("maxAgentTurns", DEFAULT_MAX_AGENT_TURNS);
        request.put("maxTotalTokens", DEFAULT_MAX_TOTAL_TOKENS);
        request.put("toolPolicy", Map.of(
                "allow", List.of("read", "bash", "edit", "write", "rd_submit_result")
        ));
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, mapper.writeValueAsString(request) + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write agent request JSON: " + path, exception);
        }
    }

    static String selectCodingRole(CodingBenchmarkArmProfile profile) {
        if (profile.roles().contains("CODING_AGENT")) {
            return "CODING_AGENT";
        }
        return profile.roles().isEmpty() ? "CODING_AGENT" : profile.roles().getLast();
    }

    private String buildPrompt(CodingBenchmarkTrial trial, CodingBenchmarkArmProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are solving coding benchmark case ").append(trial.caseId())
                .append(" in arm ").append(trial.arm().name()).append(".\n");
        if (profile.roles().size() > 1) {
            sb.append("Arm roles (for context only; you execute as CODING_AGENT): ")
                    .append(String.join(", ", profile.roles())).append(".\n");
        }
        sb.append("Read /work/input/problem.md for the issue statement. Do not search the filesystem or logs for the problem text.\n");
        sb.append("Read /work/input/arm-profile.json for arm configuration, then fix the bug in /work/repo.\n");
        sb.append("Before submitting SUCCESS, write the unified git diff (including new files, --binary) to ")
                .append(PATCH_ARTIFACT_PATH)
                .append(". Do not modify withheld or protected tests.");
        return sb.toString();
    }

    private void writeProblemStatement(Path path, String caseId) throws IOException {
        String text = loadProblemStatement(caseId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private String loadProblemStatement(String caseId) throws IOException {
        Path trusted = prepRoot.resolve("trusted-dataset.jsonl");
        if (Files.isRegularFile(trusted)) {
            try (var lines = Files.lines(trusted, StandardCharsets.UTF_8)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isBlank()) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = mapper.readValue(line, Map.class);
                    Object id = row.get("instance_id");
                    if (id == null || !caseId.equals(String.valueOf(id))) {
                        continue;
                    }
                    return renderProblem(
                            stringField(row, "title"),
                            stringField(row, "body"),
                            stringField(row, "resolved_issues"),
                            caseId);
                }
            }
        }
        String subject = loadFreshSubject(caseId);
        String acceptance = loadFreshAcceptanceCriteria(caseId);
        if (!subject.isBlank() || !acceptance.isBlank()) {
            StringBuilder body = new StringBuilder();
            if (!subject.isBlank()) {
                body.append(subject.trim()).append("\n\n");
            }
            body.append("Implement the required behavior in /work/repo so the coding-benchmark oracle acceptance checks pass.\n");
            if (!acceptance.isBlank()) {
                body.append("\n## Acceptance checks\n\n").append(acceptance).append("\n");
            }
            return renderProblem(subject.isBlank() ? caseId : subject, body.toString(), "", caseId);
        }
        return "# " + caseId + "\n\n(problem statement unavailable)\n";
    }

    private String loadFreshSubject(String caseId) throws IOException {
        Path selection = propertiesRepositoryFreshSelection();
        if (!Files.isRegularFile(selection)) {
            return "";
        }
        JsonNode root = mapper.readTree(Files.readAllBytes(selection));
        for (JsonNode node : root.path("cases")) {
            if (caseId.equals(node.path("caseId").asText())) {
                return node.path("subject").asText("").trim();
            }
        }
        return "";
    }

    private String loadFreshAcceptanceCriteria(String caseId) throws IOException {
        Path contract = prepRoot.resolve("assets").resolve(caseId).resolve("oracle-contract.json");
        if (!Files.isRegularFile(contract)) {
            return "";
        }
        JsonNode expected = mapper.readTree(Files.readAllBytes(contract)).path("expectedTests");
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("failToPass", "newToPass")) {
            JsonNode arr = expected.path(key);
            if (!arr.isArray() || arr.isEmpty()) {
                continue;
            }
            sb.append("- ").append(key).append(":\n");
            for (JsonNode value : arr) {
                String id = value.asText("").trim();
                if (!id.isEmpty()) {
                    sb.append("  - `").append(id).append("`\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private Path propertiesRepositoryFreshSelection() {
        // Prefer the repo-tracked selection file next to evaluation scripts.
        Path fromRepo = Path.of("scripts/evaluation/fresh-selection-20260730.json").toAbsolutePath().normalize();
        if (Files.isRegularFile(fromRepo)) {
            return fromRepo;
        }
        return prepRoot.resolve("fresh-selection-20260730.json");
    }

    private static String renderProblem(String title, String body, String resolved, String caseId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null || title.isBlank() ? caseId : title).append("\n\n");
        if (body != null && !body.isBlank()) {
            sb.append(body.trim()).append("\n\n");
        }
        if (resolved != null && !resolved.isBlank()) {
            sb.append("## Resolved issues\n\n").append(resolved.trim()).append("\n");
        }
        return sb.toString();
    }

    private static String stringField(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void cloneOrCopyDir(Path source, Path target) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Path resolvedSource = resolveMaterialisationSource(source);
        if (isUsableMaterialisedDirectory(target)) {
            return;
        }
        deleteIfPresent(target);
        Files.createDirectories(target.getParent());
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "clonefile",
                        resolvedSource.toAbsolutePath().toString(),
                        target.toAbsolutePath().toString());
                int exit = pb.start().waitFor();
                if (exit == 0 && isUsableMaterialisedDirectory(target)) {
                    return;
                }
                deleteIfPresent(target);
            } catch (IOException | InterruptedException ignored) {
                // Fall through — clonefile fails across volumes (e.g. /Volumes/WishDisk).
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
                deleteIfPresent(target);
            }
        }
        try {
            // -R recurses; do not use -a alone on a symlink source (preserves the link).
            ProcessBuilder copy = new ProcessBuilder(
                    "cp", "-R",
                    resolvedSource.toAbsolutePath().toString(),
                    target.toAbsolutePath().toString());
            copy.redirectErrorStream(true);
            Process process = copy.start();
            int exit = process.waitFor();
            if (exit == 0 && isUsableMaterialisedDirectory(target)) {
                return;
            }
            deleteIfPresent(target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while copying " + resolvedSource, exception);
        } catch (IOException ignored) {
            deleteIfPresent(target);
        }
        Files.createDirectories(target);
        try (var stream = Files.walk(resolvedSource)) {
            stream.forEach(src -> {
                try {
                    Path dst = target.resolve(resolvedSource.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else if (!Files.isSymbolicLink(src)) {
                        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Path linkTarget = Files.readSymbolicLink(src);
                        Path resolved = src.getParent() == null
                                ? linkTarget
                                : src.getParent().resolve(linkTarget).normalize();
                        if (Files.isRegularFile(resolved)) {
                            Files.copy(resolved, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            });
        }
        if (!isUsableMaterialisedDirectory(target)) {
            throw new IOException("failed to materialise directory from " + resolvedSource + " to " + target);
        }
    }

    private static Path resolveMaterialisationSource(Path source) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("materialisation source missing: " + source);
        }
        Path resolved = source.toRealPath();
        if (!Files.isDirectory(resolved)) {
            throw new IOException("materialisation source is not a directory: " + resolved);
        }
        return resolved;
    }

    private static boolean isUsableMaterialisedDirectory(Path path) {
        // Accept empty caches: warmed Maven trees may be large, but fixture/agent caches can be empty.
        // Never accept a symlink — that is what left dangling trial mounts on rd-bot cases.
        return path != null && !Files.isSymbolicLink(path) && Files.isDirectory(path);
    }

    private static void deleteIfPresent(Path path) throws IOException {
        if (path == null || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
            try (var walk = Files.walk(path)) {
                List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
                for (Path entry : paths) {
                    Files.deleteIfExists(entry);
                }
            }
            return;
        }
        Files.deleteIfExists(path);
    }

    /** Immutable paths for one trial's private workspace. */
    public record TrialWorkspace(
            Path repoDir,
            Path verifierDir,
            Path cacheDir,
            Path verifierCacheDir,
            Path outputDir,
            Path trialRoot
    ) {
        public TrialWorkspace {
            Objects.requireNonNull(repoDir, "repoDir must not be null");
            Objects.requireNonNull(verifierDir, "verifierDir must not be null");
            Objects.requireNonNull(cacheDir, "cacheDir must not be null");
            Objects.requireNonNull(verifierCacheDir, "verifierCacheDir must not be null");
            Objects.requireNonNull(outputDir, "outputDir must not be null");
            Objects.requireNonNull(trialRoot, "trialRoot must not be null");
        }
    }
}
