package com.wish.rd.exec.repair.qa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaDocsOnlyChangeClassifierTest {

    private final QaDocsOnlyChangeClassifier classifier = new QaDocsOnlyChangeClassifier();

    @Test
    void shouldClassifyMarkdownAndDocsAssetsAsDocsOnly() {
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.DOCS_ONLY,
                classifier.classify(List.of(
                        "README.md",
                        "docs/guide/getting-started.md",
                        "docs/images/architecture.png",
                        "CHANGELOG.md",
                        "LICENSE"
                ))
        );
    }

    @Test
    void shouldRejectSourcePackageJsonAndLockfilesAsNotDocsOnly() {
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of("README.md", "src/App.tsx"))
        );
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of("package.json"))
        );
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of("package-lock.json"))
        );
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of("pnpm-lock.yaml"))
        );
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of(".github/workflows/ci.yml"))
        );
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of("Dockerfile"))
        );
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.NOT_DOCS_ONLY,
                classifier.classify(List.of("next.config.js"))
        );
    }

    @Test
    void shouldFailClosedWhenChangedFileSetIsMissingOrEmpty() {
        assertEquals(QaDocsOnlyChangeClassifier.Decision.UNDETERMINABLE, classifier.classify(null));
        assertEquals(QaDocsOnlyChangeClassifier.Decision.UNDETERMINABLE, classifier.classify(List.of()));
        assertEquals(
                QaDocsOnlyChangeClassifier.Decision.UNDETERMINABLE,
                classifier.classify(List.of("  ", ""))
        );
    }

    @Test
    void shouldExposeAllowlistPredicateUsedByHostAndBridge() {
        assertTrue(classifier.isDocsOnlyPath("docs/overview.md"));
        assertTrue(classifier.isDocsOnlyPath("README"));
        assertTrue(classifier.isDocsOnlyPath("./docs/logo.svg"));
        assertFalse(classifier.isDocsOnlyPath("src/main/java/App.java"));
        assertFalse(classifier.isDocsOnlyPath("../escape.md"));
        assertFalse(classifier.isDocsOnlyPath("/abs/README.md"));
        assertFalse(classifier.isDocsOnlyPath("docs/package.json"));
    }
}
