package com.wish.rd.engine.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVerifyRemediationPackageBuilderTest {

    private final HostVerifyRemediationPackageBuilder builder = new HostVerifyRemediationPackageBuilder();

    @Test
    void shouldBuildBoundedHashBoundAttachment() {
        HostVerifyRemediationPackageBuilder.Package value = builder.build(
                "verify-1", "coding-1", 1, "PRODUCT_DEFECT", "BUILD exit 1: cannot find symbol Foo");

        assertEquals(HostVerifyRemediationPackageBuilder.ATTACHMENT_PATH, value.attachment().path());
        assertTrue(value.attachment().hash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(value.attachment().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                value.attachment().bytes());
        assertTrue(value.attachment().bytes() <= 65_536);
        assertTrue(value.promptSection().contains(HostVerifyRemediationPackageBuilder.CONTAINER_PATH));
        assertTrue(value.promptSection().contains(value.attachment().hash()));
        assertFalse(value.promptSection().contains("cannot find symbol Foo"));
        assertFalse(value.promptSection().contains("Failure:"));
        assertTrue(value.attachment().content().contains("cannot find symbol Foo"));
        assertTrue(value.promptSection().contains("PRODUCT_DEFECT"));
        assertEquals(1, value.todos().size());
    }

    @Test
    void shouldRejectSecretsExternalUrlsAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("verify-1", "coding-1", 1, "PRODUCT_DEFECT", "Bearer secret-token"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("verify-1", "coding-1", 1, "PRODUCT_DEFECT", "see https://evil.test"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("verify-1", "coding-1", 1, "PRODUCT_DEFECT", "x".repeat(70_000)));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("verify-1", "coding-1", 3, "PRODUCT_DEFECT", "BUILD exit 1"));
    }

    @Test
    void shouldRehydrateFrozenCanonicalAndNonCanonicalJsonWhenHashMatches() {
        HostVerifyRemediationPackageBuilder.Package original = builder.build(
                "verify-1", "coding-1", 1, "PRODUCT_DEFECT", "BUILD exit 1: cannot find symbol Foo");
        HostVerifyRemediationPackageBuilder.Package frozen = builder.fromFrozen(
                original.attachment().content(), original.requestHash());
        assertEquals(original.requestHash(), frozen.requestHash());
        assertEquals(original.attachment().content(), frozen.attachment().content());
        assertTrue(frozen.promptSection().contains(HostVerifyRemediationPackageBuilder.CONTAINER_PATH));
        assertFalse(frozen.promptSection().contains("cannot find symbol Foo"));

        String pretty = original.attachment().content()
                .replace("{", "{\n")
                .replace(",", ",\n")
                .replace("}", "\n}");
        HostVerifyRemediationPackageBuilder.Package roundTripped = builder.fromFrozen(pretty, original.requestHash());
        assertEquals(original.attachment().content(), roundTripped.attachment().content());
        assertThrows(IllegalArgumentException.class,
                () -> builder.fromFrozen(pretty, "sha256:" + "0".repeat(64)));
    }
}
