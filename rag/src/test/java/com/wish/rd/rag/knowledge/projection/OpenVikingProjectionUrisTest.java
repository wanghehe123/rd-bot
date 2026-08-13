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

    @Test
    void ancestorUrisIncludeTheHitAndEachPathParentDownToTheScheme() {
        String hit = OpenVikingProjectionUris.documentRootUri("12", "34") + "/.abstract.md";

        List<String> ancestors = OpenVikingProjectionUris.ancestorUrisInclusive(hit);

        assertEquals(
                List.of(
                        "viking://resources/rd-bot/kb/12/documents/34/.abstract.md",
                        "viking://resources/rd-bot/kb/12/documents/34",
                        "viking://resources/rd-bot/kb/12/documents",
                        "viking://resources/rd-bot/kb/12",
                        "viking://resources/rd-bot/kb",
                        "viking://resources/rd-bot",
                        "viking://resources"
                ),
                ancestors
        );
        assertFalse(ancestors.contains("viking://resources/rd-bot/kb/12/documents/12"));
    }

    @Test
    void ancestorUrisDoNotTreatAShorterNumericIdAsAPrefix() {
        String hit = OpenVikingProjectionUris.documentRootUri("12", "123") + "/source.md";

        List<String> ancestors = OpenVikingProjectionUris.ancestorUrisInclusive(hit);

        assertTrue(ancestors.contains("viking://resources/rd-bot/kb/12/documents/123"));
        assertFalse(ancestors.contains("viking://resources/rd-bot/kb/12/documents/12"));
        assertFalse(OpenVikingProjectionUris.isRemoteUriPrefixOf(
                OpenVikingProjectionUris.documentRootUri("12", "12"), hit));
        assertTrue(OpenVikingProjectionUris.isRemoteUriPrefixOf(
                OpenVikingProjectionUris.documentRootUri("12", "123"), hit));
    }

    @Test
    void ancestorUrisRejectBlankAndEmptySegmentsRatherThanThrowing() {
        assertEquals(List.of(), OpenVikingProjectionUris.ancestorUrisInclusive(""));
        assertEquals(List.of(), OpenVikingProjectionUris.ancestorUrisInclusive("   "));
        assertEquals(List.of(), OpenVikingProjectionUris.ancestorUrisInclusive(null));
        assertEquals(List.of(), OpenVikingProjectionUris.ancestorUrisInclusive(
                "viking://resources/rd-bot/kb/12/documents/34//source.md"));
    }

    @Test
    void ancestorUrisStayBoundedSoARemoteHitCannotSizeTheLookupQuery() {
        String hit = OpenVikingProjectionUris.documentRootUri("12", "34")
                + "/nested".repeat(500) + "/source.md";

        List<String> ancestors = OpenVikingProjectionUris.ancestorUrisInclusive(hit);

        assertEquals(OpenVikingProjectionUris.MAX_ANCESTOR_LOOKUP, ancestors.size(),
                "祖先数会变成 SQL 的 IN 参数个数，不能由远端命中的深度决定");
        assertTrue(ancestors.contains(OpenVikingProjectionUris.documentRootUri("12", "34")),
                "裁剪必须留下浅端，绑定就落在那里");
    }

    @Test
    void ancestorUrisCanonicalizeTraversalBeforeMatching() {
        String hit = "viking://resources/rd-bot/kb/12/documents/34/../56/source.md";

        List<String> ancestors = OpenVikingProjectionUris.ancestorUrisInclusive(hit);

        assertTrue(ancestors.contains("viking://resources/rd-bot/kb/12/documents/56"));
        assertFalse(ancestors.contains("viking://resources/rd-bot/kb/12/documents/34"));
        assertFalse(OpenVikingProjectionUris.isRemoteUriPrefixOf(
                OpenVikingProjectionUris.documentRootUri("12", "34"), hit));
        assertTrue(OpenVikingProjectionUris.isRemoteUriPrefixOf(
                OpenVikingProjectionUris.documentRootUri("12", "56"), hit));
    }
}
