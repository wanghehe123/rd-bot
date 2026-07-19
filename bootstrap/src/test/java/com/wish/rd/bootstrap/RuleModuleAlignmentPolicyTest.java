package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleModuleAlignmentPolicyTest {

    @Test
    void shouldDocumentEveryCurrentMavenModuleWithoutRemovedModules() throws Exception {
        Path repositoryRoot = Path.of(System.getProperty("user.dir")).getParent();
        String pom = Files.readString(repositoryRoot.resolve("pom.xml"));
        String rule = Files.readString(repositoryRoot.resolve("RULE.md"));

        for (String module : List.of("rag", "engine", "exec", "skill", "bootstrap")) {
            assertTrue(pom.contains("<module>" + module + "</module>"));
            assertTrue(rule.contains("`" + module + "`"), "RULE.md must document current module: " + module);
        }
        assertFalse(rule.contains("adapter（外部系统端口"));
        assertFalse(rule.contains("framework（跨层约定"));
        assertFalse(rule.contains("exec（修复执行占位"));
        assertFalse(rule.contains("skill（修复技能占位"));
    }
}
