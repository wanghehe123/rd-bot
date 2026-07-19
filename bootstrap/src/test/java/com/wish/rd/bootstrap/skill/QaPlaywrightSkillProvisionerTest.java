package com.wish.rd.bootstrap.skill;

import com.wish.rd.bootstrap.skill.impl.QaPlaywrightSkillProvisioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaPlaywrightSkillProvisionerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRegisterPolicyCheckAndInstallBundledQaSkill() throws Exception {
        QaPlaywrightSkillProvisioner provisioner = new QaPlaywrightSkillProvisioner(temporaryDirectory);

        QaPlaywrightSkillProvisioner.Provision provision = provisioner.provision();

        assertTrue(provision.installed(), provision.message());
        assertEquals("qa-playwright-cli", provision.skillId());
        assertEquals("1.0.2", provision.version());
        assertTrue(provision.checksum().startsWith("sha256:"));
        assertTrue(provision.policyJson().contains("\"allowed\":true"));
        Path skillFile = Path.of(provision.installPath()).resolve("SKILL.md");
        assertTrue(Files.isRegularFile(skillFile));
        String skill = Files.readString(skillFile);
        assertTrue(skill.contains("name: qa-playwright-cli"));
        assertTrue(skill.contains("rd-qa-evidence.mjs playwright"));
        assertTrue(skill.contains("rd-qa-evidence.mjs trace"));
        assertTrue(skill.contains("isError=true"));
        assertTrue(skill.contains("set -euo pipefail"));
        assertTrue(skill.contains("Stop dependent browser steps after the first failed required assertion"));
        assertTrue(skill.contains("Reserve at least two minutes"));
    }
}
