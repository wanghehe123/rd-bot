package com.wish.rd.bootstrap.skill;

import com.wish.rd.bootstrap.skill.impl.FileSystemSkillCatalogStore;
import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillCatalogStatus;
import com.wish.rd.skill.model.SkillRiskLevel;
import com.wish.rd.skill.model.SkillRoleBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemSkillCatalogStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldSeedBundledSkillsAndPersistBindings() {
        FileSystemSkillCatalogStore store = new FileSystemSkillCatalogStore(temporaryDirectory, true);

        assertTrue(store.findById("qa-playwright-cli").isPresent());
        assertTrue(store.findById("role-handoff-document").isPresent());
        assertEquals(1, store.listBindings("QA_AGENT").size());
        assertEquals("qa-playwright-cli", store.listBindings("QA_AGENT").getFirst().skillId());
        assertEquals(1, store.listBindings("CODING_AGENT").size());
        assertEquals("role-handoff-document", store.listBindings("CODING_AGENT").getFirst().skillId());
        Map<String, List<SkillRoleBinding>> all = store.listBindingsForAllRoles();
        assertTrue(all.containsKey("REQUIREMENT_REVIEWER"));
        assertTrue(all.containsKey("SOLUTION_ARCHITECT"));
    }

    @Test
    void shouldUpsertCatalogAndReplaceBindings() throws Exception {
        FileSystemSkillCatalogStore store = new FileSystemSkillCatalogStore(temporaryDirectory, false);

        SkillCatalogEntry entry = store.upsert(new SkillCatalogEntry(
                "demo-skill",
                "1.0.0",
                "demo",
                "guide",
                true,
                SkillRiskLevel.LOW,
                "sha256:abc",
                "file:///tmp/demo",
                temporaryDirectory.resolve("installed/demo").toString(),
                SkillCatalogStatus.ACTIVE,
                List.of("CODING_AGENT"),
                Instant.now()
        ));
        assertEquals("demo-skill", entry.skillId());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("catalog/demo-skill.json")));

        store.replaceBindings("CODING_AGENT", List.of(
                new SkillRoleBinding("CODING_AGENT", "demo-skill", 1, true)
        ));
        assertEquals(1, store.listBindings("CODING_AGENT").size());
        assertTrue(store.listBindings("CODING_AGENT").getFirst().forceGuide());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("bindings/CODING_AGENT.json")));

        FileSystemSkillCatalogStore reloaded = new FileSystemSkillCatalogStore(temporaryDirectory, false);
        assertEquals("demo-skill", reloaded.findById("demo-skill").orElseThrow().skillId());
        assertEquals(1, reloaded.listBindings("CODING_AGENT").size());
    }
}
