package com.wish.rd.bootstrap.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.pi.PiSkillMaterializerPort;
import com.wish.rd.skill.SkillCatalogPort;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillRoleBinding;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies ACTIVE role-bound Skill Hub install trees into a Pi input directory.
 *
 * <p>Writes {@code skills/{skillId}/} and {@code skill-manifest.json} with container paths under
 * {@code /work/input/skills/...}. Non-ACTIVE or missing install trees are skipped.
 */
@Component
public final class FileSystemPiSkillMaterializer implements PiSkillMaterializerPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONTAINER_SKILL_ROOT = "/work/input/skills";

    private final SkillCatalogPort catalogPort;

    /**
     * @param catalogPort Skill Hub 目录端口
     */
    public FileSystemPiSkillMaterializer(SkillCatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    @Override
    public void materialize(String role, Path inputDirectory) throws IOException {
        Path inputRoot = (inputDirectory == null ? Path.of(".") : inputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(inputRoot);
        Path skillsRoot = inputRoot.resolve("skills").normalize();
        if (!skillsRoot.startsWith(inputRoot)) {
            throw new IOException("skills directory escapes input directory");
        }
        if (Files.exists(skillsRoot)) {
            deleteRecursively(skillsRoot);
        }
        Files.createDirectories(skillsRoot);

        String normalizedRole = role == null ? "" : role.strip().toUpperCase();
        List<SkillRoleBinding> bindings = catalogPort.listBindings(normalizedRole).stream()
                .sorted(Comparator.comparingInt(SkillRoleBinding::sortOrder).thenComparing(SkillRoleBinding::skillId))
                .toList();

        List<Map<String, Object>> skills = new ArrayList<>();
        List<String> skillPaths = new ArrayList<>();
        for (SkillRoleBinding binding : bindings) {
            SkillCatalogEntry entry = catalogPort.findById(binding.skillId()).orElse(null);
            if (entry == null || entry.status() != SkillCatalogStatus.ACTIVE) {
                continue;
            }
            Path installPath = Path.of(entry.installPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(installPath)) {
                continue;
            }
            Path destination = skillsRoot.resolve(entry.skillId()).normalize();
            if (!destination.startsWith(skillsRoot)) {
                throw new IOException("skill destination escapes skills root: " + entry.skillId());
            }
            copyTree(installPath, destination);
            String containerPath = CONTAINER_SKILL_ROOT + "/" + entry.skillId();
            boolean forceGuide = binding.forceGuide() || entry.forceGuide();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillId", entry.skillId());
            item.put("version", entry.version());
            item.put("skillPath", containerPath);
            item.put("forceGuide", forceGuide);
            item.put("guidePrompt", forceGuide ? entry.guidePrompt() : "");
            item.put("description", entry.description());
            skills.add(item);
            skillPaths.add(containerPath);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("protocol", "rd-skill-manifest/v1");
        manifest.put("role", normalizedRole);
        manifest.put("skills", skills);
        manifest.put("skillPaths", skillPaths);
        Path manifestPath = inputRoot.resolve("skill-manifest.json").normalize();
        if (!manifestPath.startsWith(inputRoot)) {
            throw new IOException("skill manifest escapes input directory");
        }
        Files.writeString(
                manifestPath,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                StandardCharsets.UTF_8
        );
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(dir).toString()).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("skill source contains invalid directory: " + dir);
                }
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(file).toString()).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("skill source contains invalid file: " + file);
                }
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
