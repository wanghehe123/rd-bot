package com.wish.rd.bootstrap.skill;

import com.wish.rd.bootstrap.skill.impl.FileSystemSkillCatalogStore;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillHubAdminServiceUploadTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldUploadSkillMarkdownAndActivateLowRiskSkill() {
        FileSystemSkillCatalogStore store = new FileSystemSkillCatalogStore(temporaryDirectory, false);
        SkillHubAdminService service = new SkillHubAdminService(store, store);

        String body = """
                ---
                name: uploaded-demo
                description: Uploaded demo skill
                ---

                # Uploaded Demo
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "SKILL.md",
                "text/markdown",
                body.getBytes(StandardCharsets.UTF_8)
        );

        SkillCatalogEntry entry = service.upload(
                file,
                "1.2.0",
                "LOW",
                "CODING_AGENT,QA_AGENT",
                "follow the guide",
                true
        );

        assertEquals("uploaded-demo", entry.skillId());
        assertEquals("1.2.0", entry.version());
        assertEquals(SkillCatalogStatus.ACTIVE, entry.status());
        assertEquals(SkillRiskLevel.LOW, entry.riskLevel());
        assertTrue(entry.forceGuide());
        assertEquals(List.of("CODING_AGENT", "QA_AGENT"), entry.allowedRoles());
        assertTrue(store.findById("uploaded-demo").isPresent());
    }

    @Test
    void shouldMarkHighRiskUploadAsWaitingApproval() {
        FileSystemSkillCatalogStore store = new FileSystemSkillCatalogStore(temporaryDirectory, false);
        SkillHubAdminService service = new SkillHubAdminService(store, store);

        String body = """
                ---
                name: risky-skill
                description: Needs approval
                ---

                # Risky
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "SKILL.md",
                "text/markdown",
                body.getBytes(StandardCharsets.UTF_8)
        );

        SkillCatalogEntry pending = service.upload(
                file,
                "0.1.0",
                "HIGH",
                "CODING_AGENT",
                "",
                false
        );
        assertEquals(SkillCatalogStatus.WAITING_APPROVAL, pending.status());
        assertTrue(pending.installPath() != null && !pending.installPath().isBlank());

        SkillCatalogEntry approved = service.approve("risky-skill");
        assertEquals(SkillCatalogStatus.ACTIVE, approved.status());
    }
}
