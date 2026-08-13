package com.wish.rd.rag.knowledge.projection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenVikingProjectionUrisTest {

    @Test
    void shouldBuildDeterministicDocumentUriFromNumericIdsOnly() {
        String root = OpenVikingProjectionUris.documentRootUri("7487468535443230720", "7493354884037742592");
        String source = OpenVikingProjectionUris.documentSourceUri("7487468535443230720", "7493354884037742592");

        assertEquals(
                "viking://resources/rd-bot/kb/7487468535443230720/documents/7493354884037742592",
                root
        );
        assertEquals(
                "viking://resources/rd-bot/kb/7487468535443230720/documents/7493354884037742592/source.md",
                source
        );
        assertEquals(source, OpenVikingProjectionUris.l2ContentUri(root, "source.md"));
    }

    @Test
    void shouldKeepUriStableWhenDisplayNameOrSourceUrlWouldChange() {
        String first = OpenVikingProjectionUris.documentSourceUri("12", "34");
        String afterRename = OpenVikingProjectionUris.documentSourceUri("12", "34");

        assertEquals(first, afterRename);
        assertFalse(first.contains("payment-api.md"));
        assertFalse(first.contains("feishu"));
    }

    @Test
    void shouldRejectUserSuppliedPathFragments() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenVikingProjectionUris.documentSourceUri("../other", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenVikingProjectionUris.documentSourceUri("1", "docs/source.md"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenVikingProjectionUris.documentSourceUri("abc", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenVikingProjectionUris.contractTestRoot("../prod"));
    }

    @Test
    void shouldScopeContractTestsUnderDedicatedRootAndRefuseProductionKbPaths() {
        String runRoot = OpenVikingProjectionUris.contractTestRoot("wp0run1");
        String testRoot = OpenVikingProjectionUris.contractTestDocumentRoot("wp0run1", "1001");
        String testDoc = OpenVikingProjectionUris.contractTestDocumentUri("wp0run1", "1001");
        assertEquals("viking://resources/rd-bot/wp0-contract/wp0run1/documents/1001", testRoot);
        assertEquals(testRoot + "/source.md", testDoc);
        String productionDoc = OpenVikingProjectionUris.documentSourceUri("12", "34");

        assertEquals("viking://resources/rd-bot/wp0-contract/wp0run1/", runRoot);
        assertTrue(OpenVikingProjectionUris.isWithinOwnedRoot(testDoc, runRoot));
        assertFalse(OpenVikingProjectionUris.isWithinOwnedRoot(productionDoc, runRoot));
        assertFalse(OpenVikingProjectionUris.isWithinOwnedRoot("viking://resources/", runRoot));
        assertFalse(OpenVikingProjectionUris.isWithinOwnedRoot("viking://resources/rd-bot/", runRoot));
        assertThrows(IllegalArgumentException.class,
                () -> OpenVikingProjectionUris.requireCleanupUri("viking://resources/rd-bot/", runRoot));
        assertThrows(IllegalArgumentException.class,
                () -> OpenVikingProjectionUris.requireCleanupUri(productionDoc, runRoot));
        assertEquals(testDoc, OpenVikingProjectionUris.requireCleanupUri(testDoc, runRoot));
        assertEquals(
                runRoot.replaceAll("/+$", ""),
                OpenVikingProjectionUris.requireCleanupUri(runRoot.replaceAll("/+$", ""), runRoot)
        );
    }

    @Test
    void shouldRejectCleanupTraversalEmptySegmentsAndForbiddenRoots() {
        String runRoot = OpenVikingProjectionUris.contractTestRoot("wp0run1");
        assertThrows(IllegalArgumentException.class, () -> OpenVikingProjectionUris.requireCleanupUri(
                "viking://resources/rd-bot/wp0-contract/wp0run1/../kb/12/documents/34",
                runRoot
        ));
        assertThrows(IllegalArgumentException.class, () -> OpenVikingProjectionUris.requireCleanupUri(
                "viking://resources/rd-bot/wp0-contract/wp0run1/%2e%2e/kb/12",
                runRoot
        ));
        assertThrows(IllegalArgumentException.class, () -> OpenVikingProjectionUris.requireCleanupUri(
                "viking://resources/rd-bot/wp0-contract/wp0run1//documents/1001",
                runRoot
        ));
        assertThrows(IllegalArgumentException.class, () -> OpenVikingProjectionUris.requireCleanupUri(
                "viking://resources/",
                "viking://resources/"
        ));
        assertThrows(IllegalArgumentException.class, () -> OpenVikingProjectionUris.requireCleanupUri(
                "viking://resources/rd-bot/",
                "viking://resources/rd-bot/"
        ));
        assertFalse(OpenVikingProjectionUris.isWithinOwnedRoot(
                "viking://resources/rd-bot/wp0-contract/wp0run1/../kb/12/documents/34",
                runRoot
        ));
    }

    @Test
    void shouldBuildOwnershipMarkerAndNonSelfReferentialTags() {
        List<String> tags = OpenVikingProjectionUris.ownershipTags(
                "12",
                "34",
                7L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );

        assertEquals("rd-bot:12:34", OpenVikingProjectionUris.ownershipMarker("12", "34"));
        assertEquals(
                List.of(
                        "rd.owner=rd-bot",
                        "rd.kb_id=12",
                        "rd.doc_id=34",
                        "rd.sync_version=7",
                        "rd.checksum=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                ),
                tags
        );
        assertThrows(IllegalArgumentException.class, () -> OpenVikingProjectionUris.ownershipTags(
                "12",
                "34",
                7L,
                "b3a2c0ffee"
        ));
    }
}
