package com.wish.rd.bootstrap.executor;

import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.model.RdProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdProjectRegisteredRepositoryCatalogTest {

    @Test
    void shouldMatchEnabledProjectByUrlIgnoringGitSuffix() {
        RdProjectRegisteredRepositoryCatalog catalog = new RdProjectRegisteredRepositoryCatalog(store(
                project("https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git",
                        "wanghehe123", "rd-bot-waimai-acceptance-20260624-141045", true, false)
        ));

        assertTrue(catalog.contains(
                "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045",
                "wanghehe123/rd-bot-waimai-acceptance-20260624-141045"
        ));
    }

    @Test
    void shouldIgnoreDeletedOrDisabledProjects() {
        RdProjectRegisteredRepositoryCatalog catalog = new RdProjectRegisteredRepositoryCatalog(store(
                project("https://github.com/acme/gone.git", "acme", "gone", false, false),
                project("https://github.com/acme/deleted.git", "acme", "deleted", true, true)
        ));

        assertFalse(catalog.contains("https://github.com/acme/gone.git", "acme/gone"));
        assertFalse(catalog.contains("https://github.com/acme/deleted.git", "acme/deleted"));
    }

    private static RdProject project(
            String url,
            String owner,
            String name,
            boolean enabled,
            boolean deleted
    ) {
        return new RdProject(
                "p-" + name, name, name, "", url, owner, name, "main",
                enabled, deleted, 1L, 1L, ""
        );
    }

    private static RdProjectStore store(RdProject... projects) {
        List<RdProject> values = List.of(projects);
        return new RdProjectStore() {
            @Override
            public RdProject save(RdProject project) {
                return project;
            }

            @Override
            public Optional<RdProject> findById(String projectId) {
                return Optional.empty();
            }

            @Override
            public Optional<RdProject> findByKey(String projectKey) {
                return Optional.empty();
            }

            @Override
            public List<RdProject> list() {
                return values;
            }
        };
    }
}
