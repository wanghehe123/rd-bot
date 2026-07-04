package com.wish.rd.bootstrap.skill;

import com.wish.rd.skill.AgentSkillDescriptor;
import com.wish.rd.skill.SkillInstallCommand;
import com.wish.rd.skill.SkillInstallResult;
import com.wish.rd.skill.SkillRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileSystemSkillInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldInstallDirectorySkillAfterChecksumVerification() throws Exception {
        Path source = createSkillSource();
        String checksum = LocalFileSystemSkillInstaller.sha256(source);
        Path installRoot = tempDir.resolve("installed");
        LocalFileSystemSkillInstaller installer = new LocalFileSystemSkillInstaller(installRoot);

        SkillInstallResult result = installer.install(command(), descriptor(source, checksum));

        Path installPath = Path.of(result.installPath());
        assertTrue(result.installed());
        assertEquals("qa-real-runner", result.skillId());
        assertEquals("v1", result.version());
        assertTrue(installPath.startsWith(installRoot));
        assertEquals(
                Files.readString(source.resolve("SKILL.md")),
                Files.readString(installPath.resolve("SKILL.md"))
        );
        assertEquals(
                Files.readString(source.resolve("scripts/run.sh")),
                Files.readString(installPath.resolve("scripts/run.sh"))
        );
        assertTrue(result.message().contains("installed"));
    }

    @Test
    void shouldRejectSkillWhenChecksumDoesNotMatch() throws Exception {
        Path source = createSkillSource();
        Path installRoot = tempDir.resolve("installed");
        LocalFileSystemSkillInstaller installer = new LocalFileSystemSkillInstaller(installRoot);

        SkillInstallResult result = installer.install(command(), descriptor(source, "sha256:bad"));

        assertFalse(result.installed());
        assertTrue(result.message().contains("checksum mismatch"));
        assertFalse(Files.exists(installRoot.resolve("qa-real-runner").resolve("v1")));
    }

    private Path createSkillSource() throws Exception {
        Path source = tempDir.resolve("source").resolve("qa-real-runner");
        Files.createDirectories(source.resolve("scripts"));
        Files.writeString(source.resolve("SKILL.md"), "# QA Real Runner\n");
        Files.writeString(source.resolve("scripts/run.sh"), "#!/bin/sh\nexit 0\n");
        return source;
    }

    private SkillInstallCommand command() {
        return new SkillInstallCommand(
                "task-1",
                "stage-1",
                "QA_AGENT",
                "qa-real-runner",
                "v1"
        );
    }

    private AgentSkillDescriptor descriptor(Path source, String checksum) {
        return new AgentSkillDescriptor(
                "qa-real-runner",
                "v1",
                source.toUri().toString(),
                checksum,
                List.of("QA_AGENT"),
                SkillRiskLevel.LOW,
                "local-copy",
                "真实 QA 验收 Skill"
        );
    }
}
