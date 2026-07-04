package com.wish.rd.bootstrap.skill;

import com.wish.rd.skill.AgentSkillDescriptor;
import com.wish.rd.skill.SkillInstallCommand;
import com.wish.rd.skill.SkillInstallResult;
import com.wish.rd.skill.SkillInstallerPort;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bootstrap adapter that installs file-based Agent skills into a controlled local directory.
 *
 * <p>Used by production smoke tests and future bootstrap wiring for the skill installation port.
 */
public final class LocalFileSystemSkillInstaller implements SkillInstallerPort {

    private final Path installRoot;

    /**
     * Creates a file-system installer limited to the provided root.
     *
     * @param installRoot directory that receives installed skill snapshots
     */
    public LocalFileSystemSkillInstaller(Path installRoot) {
        this.installRoot = (installRoot == null ? Path.of("rd-bot-skills") : installRoot)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Installs a skill after verifying that the source checksum matches the descriptor.
     *
     * @param command    skill install command
     * @param descriptor skill descriptor from the registry
     * @return install result containing the real target path or a rejection reason
     */
    @Override
    public SkillInstallResult install(SkillInstallCommand command, AgentSkillDescriptor descriptor) {
        SkillInstallCommand safeCommand = command == null
                ? new SkillInstallCommand("", "", "", "", "")
                : command;
        if (descriptor == null) {
            return rejected(safeCommand, "", "skill descriptor missing");
        }
        try {
            Path source = sourcePath(descriptor.sourceUri());
            if (!Files.exists(source)) {
                return rejected(safeCommand, descriptor.version(), "skill source not found: " + source);
            }
            String actualChecksum = sha256(source);
            if (!descriptor.checksum().equals(actualChecksum)) {
                return rejected(
                        safeCommand,
                        descriptor.version(),
                        "checksum mismatch: expected=" + descriptor.checksum() + ", actual=" + actualChecksum
                );
            }
            Path target = targetPath(descriptor);
            copy(source, target);
            return new SkillInstallResult(
                    true,
                    descriptor.skillId(),
                    descriptor.version(),
                    target.toString(),
                    "installed from " + descriptor.sourceUri(),
                    metadata(descriptor, actualChecksum, target)
            );
        } catch (IllegalArgumentException | IOException exception) {
            return rejected(safeCommand, descriptor.version(), exception.getMessage());
        }
    }

    /**
     * Computes the stable SHA-256 checksum for a skill file or directory.
     *
     * @param source skill source file or directory
     * @return checksum string with the {@code sha256:} prefix
     */
    public static String sha256(Path source) {
        Path normalized = (source == null ? Path.of("") : source).toAbsolutePath().normalize();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isDirectory(normalized)) {
                for (Path file : regularFiles(normalized)) {
                    String relative = normalized.relativize(file).toString().replace('\\', '/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(file));
                    digest.update((byte) 0);
                }
            } else {
                digest.update(Files.readAllBytes(normalized));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("failed to checksum skill source: " + normalized, exception);
        }
    }

    private static List<Path> regularFiles(Path source) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> source.relativize(path).toString()))
                    .toList();
        }
    }

    private Path sourcePath(String sourceUri) {
        try {
            URI uri = new URI(sourceUri == null ? "" : sourceUri.strip());
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("only file skill source is supported: " + sourceUri);
            }
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new IllegalArgumentException("invalid skill sourceUri: " + sourceUri, exception);
        }
    }

    private Path targetPath(AgentSkillDescriptor descriptor) {
        Path target = installRoot
                .resolve(descriptor.skillId())
                .resolve(descriptor.version())
                .toAbsolutePath()
                .normalize();
        if (!target.startsWith(installRoot)) {
            throw new IllegalArgumentException("skill install target escapes install root: " + target);
        }
        return target;
    }

    private void copy(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            try (Stream<Path> stream = Files.walk(source)) {
                for (Path path : stream.toList()) {
                    Path destination = target.resolve(source.relativize(path).toString()).normalize();
                    if (!destination.startsWith(target)) {
                        throw new IllegalArgumentException("skill source contains invalid path: " + path);
                    }
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else if (Files.isRegularFile(path)) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else if (Files.isRegularFile(source)) {
            Files.createDirectories(target);
            Files.copy(source, target.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } else {
            throw new IllegalArgumentException("skill source must be a file or directory: " + source);
        }
    }

    private SkillInstallResult rejected(SkillInstallCommand command, String version, String message) {
        return new SkillInstallResult(
                false,
                command.skillId(),
                version,
                "",
                message == null ? "" : message,
                "{}"
        );
    }

    private String metadata(AgentSkillDescriptor descriptor, String checksum, Path target) {
        return """
                {"sourceUri":"%s","sourceChecksum":"%s","installPath":"%s","riskLevel":"%s"}
                """.formatted(
                json(descriptor.sourceUri()),
                json(checksum),
                json(target.toString()),
                descriptor.riskLevel().name()
        ).strip();
    }

    private String json(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");
    }
}
