package com.wish.rd.bootstrap.skill;

import com.wish.rd.bootstrap.skill.impl.FileSystemPiSkillMaterializer;
import com.wish.rd.bootstrap.skill.impl.FileSystemSkillCatalogStore;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillRiskLevel;
import com.wish.rd.skill.model.SkillRoleBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemPiSkillMaterializerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldMaterializeActiveBoundSkillIntoInputSkills() throws Exception {
        Path hubRoot = temporaryDirectory.resolve("hub");
        Path install = hubRoot.resolve("installed").resolve("demo-skill").resolve("1.0.0");
        Files.createDirectories(install);
        Files.writeString(install.resolve("SKILL.md"), "# Demo\n", StandardCharsets.UTF_8);

        FileSystemSkillCatalogStore store = new FileSystemSkillCatalogStore(hubRoot, false);
        store.upsert(new SkillCatalogEntry(
                "demo-skill",
                "1.0.0",
                "demo description",
                "use this guide",
                false,
                SkillRiskLevel.LOW,
                "sha256:demo",
                install.toUri().toString(),
                install.toString(),
                SkillCatalogStatus.ACTIVE,
                List.of("CODING_AGENT"),
                Instant.now()
        ));
        store.replaceBindings("CODING_AGENT", List.of(
                new SkillRoleBinding("CODING_AGENT", "demo-skill", 0, true)
        ));

        Path input = temporaryDirectory.resolve("input");
        Files.createDirectories(input);
        new FileSystemPiSkillMaterializer(store).materialize("CODING_AGENT", input);

        assertTrue(Files.isRegularFile(input.resolve("skills/demo-skill/SKILL.md")));
        String manifest = Files.readString(input.resolve("skill-manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"protocol\" : \"rd-skill-manifest/v1\"")
                || manifest.contains("\"protocol\":\"rd-skill-manifest/v1\""));
        assertTrue(manifest.contains("/work/input/skills/demo-skill"));
        assertTrue(manifest.contains("use this guide"));
        assertTrue(manifest.contains("\"forceGuide\" : true") || manifest.contains("\"forceGuide\":true"));
    }

    @Test
    void shouldSkipNonActiveSkillsAndWriteEmptyManifestWhenUnbound() throws Exception {
        Path hubRoot = temporaryDirectory.resolve("hub");
        FileSystemSkillCatalogStore store = new FileSystemSkillCatalogStore(hubRoot, false);
        Path input = temporaryDirectory.resolve("input");
        Files.createDirectories(input);

        new FileSystemPiSkillMaterializer(store).materialize("QA_AGENT", input);

        assertTrue(Files.isDirectory(input.resolve("skills")));
        assertTrue(Files.isRegularFile(input.resolve("skill-manifest.json")));
        String manifest = Files.readString(input.resolve("skill-manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"skillPaths\" : [ ]") || manifest.contains("\"skillPaths\":[]"));
        assertFalse(Files.exists(input.resolve("skills/qa-playwright-cli")));
    }
}
