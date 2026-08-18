package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.security.RegisteredRepositoryCatalog;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.model.RdProject;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Treats repositories already saved on enabled RD-Bot projects as allowlisted execution targets.
 */
@Component
public final class RdProjectRegisteredRepositoryCatalog implements RegisteredRepositoryCatalog {

    private final RdProjectStore projectStore;

    public RdProjectRegisteredRepositoryCatalog(RdProjectStore projectStore) {
        this.projectStore = Objects.requireNonNull(projectStore, "projectStore must not be null");
    }

    @Override
    public boolean contains(String repositoryUrl, String ownerAndName) {
        String url = normalizeRepositoryUrl(repositoryUrl);
        String name = normalize(ownerAndName).toLowerCase(Locale.ROOT);
        for (RdProject project : projectStore.list()) {
            if (project.deleted() || !project.enabled()) {
                continue;
            }
            if (!url.isBlank() && url.equals(normalizeRepositoryUrl(project.repositoryUrl()))) {
                return true;
            }
            String projectName = normalize(project.repoOwner() + "/" + project.repoName()).toLowerCase(Locale.ROOT);
            if (!name.isBlank() && !projectName.equals("/") && name.equals(projectName)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeRepositoryUrl(String value) {
        String normalized = normalize(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.toLowerCase(Locale.ROOT).endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
