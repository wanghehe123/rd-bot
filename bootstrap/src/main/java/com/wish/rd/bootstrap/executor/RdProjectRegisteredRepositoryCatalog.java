package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.security.RegisteredRepositoryCatalog;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.rag.project.model.RdProject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Treats repositories already saved on enabled RD-Bot projects as allowlisted execution targets.
 *
 * <p>{@link RdProjectStore} is deliberately database-only, so it is absent whenever
 * {@code rd.knowledge.store} is not {@code postgres}. This catalog then contributes nothing and the
 * static allowlist alone decides, rather than failing the context at startup.
 */
@Component
public final class RdProjectRegisteredRepositoryCatalog implements RegisteredRepositoryCatalog {

    private final Supplier<RdProjectStore> projectStoreSupplier;

    @Autowired
    public RdProjectRegisteredRepositoryCatalog(ObjectProvider<RdProjectStore> projectStoreProvider) {
        Objects.requireNonNull(projectStoreProvider, "projectStoreProvider must not be null");
        this.projectStoreSupplier = projectStoreProvider::getIfAvailable;
    }

    public RdProjectRegisteredRepositoryCatalog(RdProjectStore projectStore) {
        Objects.requireNonNull(projectStore, "projectStore must not be null");
        this.projectStoreSupplier = () -> projectStore;
    }

    @Override
    public boolean contains(String repositoryUrl, String ownerAndName) {
        RdProjectStore projectStore = projectStoreSupplier.get();
        if (projectStore == null) {
            return false;
        }
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
