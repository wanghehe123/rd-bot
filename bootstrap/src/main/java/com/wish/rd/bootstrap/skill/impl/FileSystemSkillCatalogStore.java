package com.wish.rd.bootstrap.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.skill.SkillCatalogPort;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillRiskLevel;
import com.wish.rd.skill.model.SkillRoleBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Filesystem MVP store for Skill Hub catalog and role bindings.
 *
 * <p>Root is {@code ${rd.executor.docker.workspace-root}/_skill-hub}. Catalog JSON lives under
 * {@code catalog/{skillId}.json}; role bindings under {@code bindings/{ROLE}.json}. Startup seeds
 * the two bundled skills via existing provisioners.
 */
@Component
public final class FileSystemSkillCatalogStore implements SkillCatalogPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path root;
    private final Path catalogRoot;
    private final Path bindingsRoot;
    private final ConcurrentHashMap<String, SkillCatalogEntry> catalog = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SkillRoleBinding>> bindings = new ConcurrentHashMap<>();

    /**
     * Creates the store under the Docker workspace root and seeds bundled skills.
     *
     * @param properties Docker executor properties (workspace root)
     */
    @Autowired
    public FileSystemSkillCatalogStore(DockerExecutorProperties properties) {
        this(resolveRoot(properties), true);
    }

    /**
     * Test-friendly constructor that optionally skips seeding.
     *
     * @param root         Skill Hub root directory
     * @param seedBundled  whether to provision and bind bundled skills
     */
    public FileSystemSkillCatalogStore(Path root, boolean seedBundled) {
        this.root = (root == null ? Path.of("/tmp/rd-bot/skill-hub") : root).toAbsolutePath().normalize();
        this.catalogRoot = this.root.resolve("catalog").normalize();
        this.bindingsRoot = this.root.resolve("bindings").normalize();
        try {
            Files.createDirectories(catalogRoot);
            Files.createDirectories(bindingsRoot);
            loadFromDisk();
            if (seedBundled) {
                seedBundledSkills();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to initialize Skill Hub store at " + this.root, exception);
        }
    }

    @Override
    public List<SkillCatalogEntry> list() {
        return catalog.values().stream()
                .sorted(Comparator.comparing(SkillCatalogEntry::skillId))
                .toList();
    }

    @Override
    public Optional<SkillCatalogEntry> findById(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalog.get(skillId.strip()));
    }

    @Override
    public synchronized SkillCatalogEntry upsert(SkillCatalogEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        SkillCatalogEntry normalized = new SkillCatalogEntry(
                entry.skillId(),
                entry.version(),
                entry.description(),
                entry.guidePrompt(),
                entry.forceGuide(),
                entry.riskLevel(),
                entry.checksum(),
                entry.sourceUri(),
                entry.installPath(),
                entry.status(),
                entry.allowedRoles(),
                entry.updatedAt() == null || entry.updatedAt().equals(Instant.EPOCH)
                        ? Instant.now()
                        : entry.updatedAt()
        );
        try {
            writeCatalog(normalized);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist skill catalog entry: " + normalized.skillId(), exception);
        }
        catalog.put(normalized.skillId(), normalized);
        return normalized;
    }

    @Override
    public List<SkillRoleBinding> listBindings(String role) {
        String normalizedRole = normalizeRole(role);
        if (normalizedRole.isBlank()) {
            return List.of();
        }
        return List.copyOf(bindings.getOrDefault(normalizedRole, List.of()));
    }

    @Override
    public synchronized List<SkillRoleBinding> replaceBindings(String role, List<SkillRoleBinding> nextBindings) {
        String normalizedRole = normalizeRole(role);
        if (normalizedRole.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        List<SkillRoleBinding> normalized = (nextBindings == null ? List.<SkillRoleBinding>of() : nextBindings).stream()
                .map(binding -> new SkillRoleBinding(
                        normalizedRole,
                        binding.skillId(),
                        binding.sortOrder(),
                        binding.forceGuide()
                ))
                .sorted(Comparator.comparingInt(SkillRoleBinding::sortOrder).thenComparing(SkillRoleBinding::skillId))
                .toList();
        try {
            writeBindings(normalizedRole, normalized);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist skill role bindings for " + normalizedRole, exception);
        }
        bindings.put(normalizedRole, List.copyOf(normalized));
        return List.copyOf(normalized);
    }

    @Override
    public Map<String, List<SkillRoleBinding>> listBindingsForAllRoles() {
        Map<String, List<SkillRoleBinding>> result = new LinkedHashMap<>();
        bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), List.copyOf(entry.getValue())));
        return Map.copyOf(result);
    }

    /**
     * Returns the Skill Hub filesystem root.
     *
     * @return absolute root path
     */
    public Path root() {
        return root;
    }

    private void loadFromDisk() throws IOException {
        try (Stream<Path> stream = Files.list(catalogRoot)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                SkillCatalogEntry entry = OBJECT_MAPPER.readValue(file.toFile(), SkillCatalogEntry.class);
                catalog.put(entry.skillId(), entry);
            }
        }
        try (Stream<Path> stream = Files.list(bindingsRoot)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String role = file.getFileName().toString().replace(".json", "");
                SkillRoleBinding[] loaded = OBJECT_MAPPER.readValue(file.toFile(), SkillRoleBinding[].class);
                List<SkillRoleBinding> roleBindings = new ArrayList<>();
                for (SkillRoleBinding binding : loaded) {
                    roleBindings.add(new SkillRoleBinding(role, binding.skillId(), binding.sortOrder(), binding.forceGuide()));
                }
                roleBindings.sort(Comparator.comparingInt(SkillRoleBinding::sortOrder).thenComparing(SkillRoleBinding::skillId));
                bindings.put(role, List.copyOf(roleBindings));
            }
        }
    }

    private void seedBundledSkills() {
        QaPlaywrightSkillProvisioner.Provision qa = new QaPlaywrightSkillProvisioner(
                root.resolve("bundled").resolve("qa")
        ).provision();
        if (!qa.installed()) {
            throw new IllegalStateException("failed to seed qa-playwright-cli: " + qa.message());
        }
        upsert(new SkillCatalogEntry(
                qa.skillId(),
                qa.version(),
                "Real command, HTTP, Playwright CLI and regression QA evidence workflow",
                "",
                false,
                SkillRiskLevel.LOW,
                qa.checksum(),
                Path.of(qa.installPath()).toUri().toString(),
                qa.installPath(),
                SkillCatalogStatus.ACTIVE,
                List.of("QA_AGENT"),
                Instant.now()
        ));
        ensureBinding("QA_AGENT", qa.skillId(), 0, false);

        RoleHandoffDocumentSkillProvisioner.Provision handoff = new RoleHandoffDocumentSkillProvisioner(
                root.resolve("bundled").resolve("handoff")
        ).provision();
        if (!handoff.installed()) {
            throw new IllegalStateException("failed to seed role-handoff-document: " + handoff.message());
        }
        upsert(new SkillCatalogEntry(
                handoff.skillId(),
                handoff.version(),
                "Bounded private Markdown handoff for direct downstream delivery roles",
                "",
                false,
                SkillRiskLevel.LOW,
                handoff.checksum(),
                Path.of(handoff.installPath()).toUri().toString(),
                handoff.installPath(),
                SkillCatalogStatus.ACTIVE,
                List.of("REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT"),
                Instant.now()
        ));
        for (String role : List.of("REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT")) {
            ensureBinding(role, handoff.skillId(), 0, false);
        }
    }

    private void ensureBinding(String role, String skillId, int sortOrder, boolean forceGuide) {
        List<SkillRoleBinding> existing = new ArrayList<>(listBindings(role));
        boolean present = existing.stream().anyMatch(binding -> skillId.equals(binding.skillId()));
        if (present) {
            return;
        }
        existing.add(new SkillRoleBinding(role, skillId, sortOrder, forceGuide));
        replaceBindings(role, existing);
    }

    private void writeCatalog(SkillCatalogEntry entry) throws IOException {
        Path target = catalogRoot.resolve(entry.skillId() + ".json").normalize();
        if (!target.startsWith(catalogRoot)) {
            throw new IOException("catalog path escapes catalog root: " + target);
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), entry);
    }

    private void writeBindings(String role, List<SkillRoleBinding> roleBindings) throws IOException {
        Path target = bindingsRoot.resolve(role + ".json").normalize();
        if (!target.startsWith(bindingsRoot)) {
            throw new IOException("bindings path escapes bindings root: " + target);
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), roleBindings);
    }

    private static Path resolveRoot(DockerExecutorProperties properties) {
        Path workspaceRoot = properties == null || properties.getWorkspaceRoot() == null
                ? DockerExecutorProperties.DEFAULT_WORKSPACE_ROOT
                : properties.getWorkspaceRoot();
        return workspaceRoot.toAbsolutePath().normalize().resolve("_skill-hub");
    }

    private static String normalizeRole(String role) {
        return role == null ? "" : role.strip().toUpperCase();
    }
}
