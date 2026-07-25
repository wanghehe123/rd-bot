package com.wish.rd.bootstrap.skill;

import com.wish.rd.bootstrap.skill.impl.RoleHandoffDocumentSkillProvisioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleHandoffDocumentSkillProvisionerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldInstallVerifiedMarkdownHandoffSkillForNonQaRoles() throws Exception {
        RoleHandoffDocumentSkillProvisioner.Provision provision = new RoleHandoffDocumentSkillProvisioner(
                temporaryDirectory
        ).provision();

        assertTrue(provision.installed(), provision.message());
        assertEquals("role-handoff-document", provision.skillId());
        assertEquals("1.0.0", provision.version());
        assertTrue(provision.checksum().startsWith("sha256:"));
        assertTrue(provision.policyJson().contains("\"allowed\":true"));
        Path skillFile = Path.of(provision.installPath()).resolve("SKILL.md");
        assertTrue(Files.isRegularFile(skillFile));
        String skill = Files.readString(skillFile);
        assertTrue(skill.contains("name: role-handoff-document"));
        assertTrue(skill.contains("/work/output/handoff/next.md"));
        assertTrue(skill.contains("Do not include object-store URLs"));
    }
}
