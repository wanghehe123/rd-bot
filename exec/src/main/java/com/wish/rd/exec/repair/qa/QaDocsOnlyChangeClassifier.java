package com.wish.rd.exec.repair.qa;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed classifier for whether a candidate change set is docs-only.
 *
 * <p>Used by {@link QaRepositoryProfileDetector} (host profile resolution),
 * {@link com.wish.rd.exec.repair.result.QaEvidenceBundleValidator} (host final
 * evidence rules), and mirrored in the in-container {@code result-tool.mjs}
 * pre-validation so prompt, bridge, and host stay in lockstep.
 */
public final class QaDocsOnlyChangeClassifier {

    /**
     * Classification of a candidate changed-file set.
     */
    public enum Decision {
        /** Every changed path matches the docs-only allowlist. */
        DOCS_ONLY,
        /** At least one changed path can affect runtime or is outside the allowlist. */
        NOT_DOCS_ONLY,
        /** Changed-file set is missing, empty, or otherwise unusable; callers must run the full QA profile. */
        UNDETERMINABLE
    }

    private static final List<String> DOC_BASENAME_PREFIXES = List.of(
            "readme",
            "changelog",
            "changes",
            "history",
            "authors",
            "contributors",
            "license",
            "licence",
            "notice",
            "copying",
            "code_of_conduct",
            "security",
            "contributing"
    );

    private static final Set<String> DOC_TEXT_EXTENSIONS = Set.of(
            ".md",
            ".mdx",
            ".markdown",
            ".txt",
            ".rst",
            ".adoc"
    );

    private static final Set<String> DOC_ASSET_EXTENSIONS = Set.of(
            ".png",
            ".jpg",
            ".jpeg",
            ".gif",
            ".svg",
            ".webp"
    );

    private static final Set<String> RUNTIME_EXTENSIONS = Set.of(
            ".js",
            ".mjs",
            ".cjs",
            ".ts",
            ".tsx",
            ".jsx",
            ".java",
            ".kt",
            ".go",
            ".py",
            ".rb",
            ".rs",
            ".c",
            ".cc",
            ".cpp",
            ".h",
            ".hpp",
            ".cs",
            ".php",
            ".sh",
            ".bash",
            ".zsh",
            ".json",
            ".yml",
            ".yaml",
            ".toml",
            ".xml",
            ".gradle",
            ".properties",
            ".env",
            ".dockerfile"
    );

    /**
     * Classifies the candidate changed-file set.
     *
     * @param changedFiles real paths from the candidate patch / git diff; {@code null} or blank-only is undeterminable
     * @return docs-only decision; ambiguity always fails closed to {@link Decision#UNDETERMINABLE}
     */
    public Decision classify(Collection<String> changedFiles) {
        if (changedFiles == null) {
            return Decision.UNDETERMINABLE;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : changedFiles) {
            String path = normalize(raw);
            if (!path.isBlank()) {
                normalized.add(path);
            }
        }
        if (normalized.isEmpty()) {
            return Decision.UNDETERMINABLE;
        }
        for (String path : normalized) {
            if (!isDocsOnlyPath(path)) {
                return Decision.NOT_DOCS_ONLY;
            }
        }
        return Decision.DOCS_ONLY;
    }

    /**
     * Returns whether a single repository-relative path is on the docs-only allowlist.
     *
     * @param path repository-relative path from the candidate change set
     * @return {@code true} only for markdown/text/doc basenames and docs/ image assets
     */
    public boolean isDocsOnlyPath(String path) {
        String normalized = normalize(path);
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.contains("..")
                || normalized.contains("//")) {
            return false;
        }
        String lowerPath = normalized.toLowerCase(Locale.ROOT);
        String baseName = baseName(lowerPath);
        if (matchesDocBasename(baseName) && !hasRuntimeExtension(baseName)) {
            return true;
        }
        for (String extension : DOC_TEXT_EXTENSIONS) {
            if (lowerPath.endsWith(extension)) {
                return true;
            }
        }
        if (isUnderDocsDirectory(lowerPath)) {
            for (String extension : DOC_ASSET_EXTENSIONS) {
                if (lowerPath.endsWith(extension)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesDocBasename(String baseName) {
        for (String prefix : DOC_BASENAME_PREFIXES) {
            if (baseName.equals(prefix)
                    || baseName.startsWith(prefix + ".")
                    || baseName.startsWith(prefix + "-")
                    || baseName.startsWith(prefix + "_")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRuntimeExtension(String baseName) {
        for (String extension : RUNTIME_EXTENSIONS) {
            if (baseName.endsWith(extension)) {
                return true;
            }
        }
        return baseName.equals("dockerfile") || baseName.startsWith("dockerfile.");
    }

    private static boolean isUnderDocsDirectory(String lowerPath) {
        return lowerPath.equals("docs")
                || lowerPath.startsWith("docs/")
                || lowerPath.contains("/docs/");
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
