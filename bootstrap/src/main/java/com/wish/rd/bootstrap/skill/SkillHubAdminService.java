package com.wish.rd.bootstrap.skill;

import com.wish.rd.bootstrap.skill.impl.LocalFileSystemSkillInstaller;
import com.wish.rd.skill.SkillCatalogPort;
import com.wish.rd.skill.SkillInstallationEngine;
import com.wish.rd.skill.SkillInstallerPort;
import com.wish.rd.skill.SkillRegistryPort;
import com.wish.rd.skill.impl.RoleAllowlistSkillPolicyGate;
import com.wish.rd.skill.model.AgentSkillDescriptor;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillInstallCommand;
import com.wish.rd.skill.model.SkillInstallResult;
import com.wish.rd.skill.model.SkillRiskLevel;
import com.wish.rd.skill.model.SkillRoleBinding;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill Hub 管理面编排服务。
 *
 * <p>承接上传、目录更新、审批与角色绑定；控制器只做 HTTP 适配。安装门禁复用
 * {@link SkillInstallationEngine} + {@link RoleAllowlistSkillPolicyGate}。
 */
@Service
public final class SkillHubAdminService {

    private final SkillCatalogPort catalogPort;
    private final Path skillHubRoot;

    /**
     * @param catalogPort Skill Hub 目录端口
     * @param catalogStore 用于解析 Skill Hub 根目录的文件系统实现
     */
    public SkillHubAdminService(
            SkillCatalogPort catalogPort,
            com.wish.rd.bootstrap.skill.impl.FileSystemSkillCatalogStore catalogStore
    ) {
        this.catalogPort = catalogPort;
        this.skillHubRoot = catalogStore.root();
    }

    /**
     * 列出目录。
     *
     * @return 目录条目
     */
    public List<SkillCatalogEntry> listCatalog() {
        return catalogPort.list();
    }

    /**
     * 按 ID 查询。
     *
     * @param skillId Skill ID
     * @return 目录条目
     */
    public Optional<SkillCatalogEntry> findById(String skillId) {
        return catalogPort.findById(skillId);
    }

    /**
     * 上传 zip 或单文件 SKILL.md，经策略门禁后写入目录。
     *
     * @param file         上传文件
     * @param version      版本
     * @param riskLevel    风险等级
     * @param allowedRoles 允许角色（逗号或 JSON 数组）
     * @param guidePrompt  引导提示词
     * @param forceGuide   是否强制引导
     * @return 目录条目
     */
    public SkillCatalogEntry upload(
            MultipartFile file,
            String version,
            String riskLevel,
            String allowedRoles,
            String guidePrompt,
            boolean forceGuide
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        try {
            Path sourcesRoot = skillHubRoot.resolve("sources").normalize();
            Files.createDirectories(sourcesRoot);
            Path extractRoot = Files.createTempDirectory(sourcesRoot, "upload-");
            Frontmatter frontmatter = extractUpload(file, extractRoot);
            String skillId = frontmatter.name().isBlank()
                    ? deriveSkillId(file.getOriginalFilename())
                    : frontmatter.name();
            String resolvedVersion = blankTo(version, "0.0.0");
            Path versionedSource = skillHubRoot.resolve("sources").resolve(skillId).resolve(resolvedVersion).normalize();
            if (!versionedSource.startsWith(skillHubRoot.resolve("sources"))) {
                throw new IllegalArgumentException("skill source escapes Skill Hub root");
            }
            copyTree(extractRoot, versionedSource);
            String checksum = LocalFileSystemSkillInstaller.sha256(versionedSource);
            List<String> roles = parseRoles(allowedRoles);
            if (roles.isEmpty()) {
                throw new IllegalArgumentException("allowedRoles must not be empty");
            }
            SkillRiskLevel risk = parseRisk(riskLevel);
            String description = frontmatter.description().isBlank()
                    ? "Uploaded skill " + skillId
                    : frontmatter.description();
            AgentSkillDescriptor descriptor = new AgentSkillDescriptor(
                    skillId,
                    resolvedVersion,
                    versionedSource.toUri().toString(),
                    checksum,
                    roles,
                    risk,
                    "skill-hub filesystem install",
                    description
            );
            SkillInstallerPort installer = new LocalFileSystemSkillInstaller(skillHubRoot.resolve("installed"));
            SkillInstallationEngine engine = new SkillInstallationEngine(
                    registry(descriptor),
                    new RoleAllowlistSkillPolicyGate(true),
                    installer
            );
            SkillInstallResult result = engine.install(new SkillInstallCommand(
                    "skill-hub",
                    "upload",
                    roles.getFirst(),
                    skillId,
                    resolvedVersion
            ));
            SkillCatalogStatus status;
            String installPath = result.installPath();
            if (result.installed()) {
                status = SkillCatalogStatus.ACTIVE;
            } else if (result.policyJson().contains("WAITING_APPROVAL")) {
                // HIGH 风险：门禁阻止自动启用，但仍落盘安装快照，待人工审批后可绑定。
                SkillInstallResult forced = installer.install(
                        new SkillInstallCommand("skill-hub", "upload-pending", roles.getFirst(), skillId, resolvedVersion),
                        descriptor
                );
                if (!forced.installed()) {
                    throw new IllegalStateException("skill install failed while waiting approval: " + forced.message());
                }
                installPath = forced.installPath();
                status = SkillCatalogStatus.WAITING_APPROVAL;
            } else {
                throw new IllegalStateException("skill install rejected: " + result.message());
            }
            return catalogPort.upsert(new SkillCatalogEntry(
                    skillId,
                    resolvedVersion,
                    description,
                    safe(guidePrompt),
                    forceGuide,
                    risk,
                    checksum,
                    versionedSource.toUri().toString(),
                    installPath,
                    status,
                    roles,
                    Instant.now()
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("skill upload failed: " + exception.getMessage(), exception);
        }
    }

    /**
     * 更新目录元数据（不重新安装）。
     *
     * @param skillId      Skill ID
     * @param guidePrompt  引导提示词（null 表示保留）
     * @param forceGuide   强制引导（null 表示保留）
     * @param allowedRoles 允许角色（null 表示保留）
     * @param status       状态（null 表示保留）
     * @param description  说明（null 表示保留）
     * @return 更新后的条目
     */
    public SkillCatalogEntry update(
            String skillId,
            String guidePrompt,
            Boolean forceGuide,
            List<String> allowedRoles,
            SkillCatalogStatus status,
            String description
    ) {
        SkillCatalogEntry existing = catalogPort.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("skill not found: " + skillId));
        return catalogPort.upsert(new SkillCatalogEntry(
                existing.skillId(),
                existing.version(),
                description == null ? existing.description() : description,
                guidePrompt == null ? existing.guidePrompt() : guidePrompt,
                forceGuide == null ? existing.forceGuide() : forceGuide,
                existing.riskLevel(),
                existing.checksum(),
                existing.sourceUri(),
                existing.installPath(),
                status == null ? existing.status() : status,
                allowedRoles == null ? existing.allowedRoles() : allowedRoles,
                Instant.now()
        ));
    }

    /**
     * 将 WAITING_APPROVAL 条目审批为 ACTIVE。
     *
     * @param skillId Skill ID
     * @return 更新后的条目
     */
    public SkillCatalogEntry approve(String skillId) {
        SkillCatalogEntry existing = catalogPort.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("skill not found: " + skillId));
        if (existing.status() != SkillCatalogStatus.WAITING_APPROVAL) {
            throw new IllegalArgumentException("skill is not waiting approval: " + existing.status());
        }
        return catalogPort.upsert(new SkillCatalogEntry(
                existing.skillId(),
                existing.version(),
                existing.description(),
                existing.guidePrompt(),
                existing.forceGuide(),
                existing.riskLevel(),
                existing.checksum(),
                existing.sourceUri(),
                existing.installPath(),
                SkillCatalogStatus.ACTIVE,
                existing.allowedRoles(),
                Instant.now()
        ));
    }

    /**
     * 列出全部角色绑定。
     *
     * @return role → bindings
     */
    public Map<String, List<SkillRoleBinding>> listAllBindings() {
        return catalogPort.listBindingsForAllRoles();
    }

    /**
     * 替换角色绑定。
     *
     * @param role     角色
     * @param bindings 绑定列表
     * @return 持久化后的绑定
     */
    public List<SkillRoleBinding> replaceBindings(String role, List<SkillRoleBinding> bindings) {
        return catalogPort.replaceBindings(role, bindings == null ? List.of() : bindings);
    }

    private Frontmatter extractUpload(MultipartFile file, Path extractRoot) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            unzip(file.getInputStream(), extractRoot);
        } else {
            Path skillFile = extractRoot.resolve("SKILL.md").normalize();
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, skillFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path skillMd = findSkillMarkdown(extractRoot);
        if (skillMd == null) {
            throw new IllegalArgumentException("upload must contain SKILL.md");
        }
        // 若 SKILL.md 不在根目录，整树保留；frontmatter 仍从该文件解析。
        return parseFrontmatter(Files.readString(skillMd, StandardCharsets.UTF_8));
    }

    private static Path findSkillMarkdown(Path root) throws IOException {
        Path direct = root.resolve("SKILL.md");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("SKILL.md"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).getNameCount()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void unzip(InputStream inputStream, Path targetRoot) throws IOException {
        Path normalizedRoot = targetRoot.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path destination = normalizedRoot.resolve(entry.getName()).normalize();
                if (!destination.startsWith(normalizedRoot)) {
                    throw new IOException("zip entry escapes extract root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            try (var stream = Files.walk(target)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString()).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("source contains invalid path: " + path);
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Frontmatter parseFrontmatter(String content) {
        String text = content == null ? "" : content;
        if (!text.startsWith("---")) {
            return new Frontmatter("", "");
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return new Frontmatter("", "");
        }
        String block = text.substring(3, end).strip();
        String name = "";
        String description = "";
        for (String line : block.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("name:")) {
                name = trimmed.substring("name:".length()).strip();
            } else if (trimmed.startsWith("description:")) {
                description = trimmed.substring("description:".length()).strip();
            }
        }
        return new Frontmatter(name, description);
    }

    private static List<String> parseRoles(String raw) {
        String value = safe(raw);
        if (value.isBlank()) {
            return List.of();
        }
        if (value.startsWith("[")) {
            String inner = value.substring(1, value.endsWith("]") ? value.length() - 1 : value.length());
            List<String> roles = new ArrayList<>();
            for (String part : inner.split(",")) {
                String role = part.strip().replace("\"", "").replace("'", "");
                if (!role.isBlank()) {
                    roles.add(role.toUpperCase(Locale.ROOT));
                }
            }
            return List.copyOf(roles);
        }
        List<String> roles = new ArrayList<>();
        for (String part : value.split(",")) {
            String role = part.strip();
            if (!role.isBlank()) {
                roles.add(role.toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(roles);
    }

    private static SkillRiskLevel parseRisk(String raw) {
        String value = safe(raw);
        if (value.isBlank()) {
            return SkillRiskLevel.LOW;
        }
        return SkillRiskLevel.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String deriveSkillId(String filename) {
        String name = filename == null ? "" : filename.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.isBlank() || "SKILL".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("unable to derive skillId from upload; provide SKILL.md frontmatter name");
        }
        return name;
    }

    private static SkillRegistryPort registry(AgentSkillDescriptor descriptor) {
        return new SkillRegistryPort() {
            @Override
            public List<AgentSkillDescriptor> listAvailable() {
                return List.of(descriptor);
            }

            @Override
            public Optional<AgentSkillDescriptor> findById(String skillId) {
                return descriptor.skillId().equals(skillId) ? Optional.of(descriptor) : Optional.empty();
            }
        };
    }

    private static String blankTo(String value, String fallback) {
        String normalized = safe(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record Frontmatter(String name, String description) {
    }
}
