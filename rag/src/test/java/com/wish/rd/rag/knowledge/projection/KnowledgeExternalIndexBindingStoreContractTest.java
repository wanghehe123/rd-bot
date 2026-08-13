package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按命中 URI 反查绑定的契约：最长路径前缀、路径段边界、空白入参不抛。
 * PostgreSQL 实现必须用祖先等值 IN，而不是 {@code hit LIKE remote_uri || '%'}。
 */
class KnowledgeExternalIndexBindingStoreContractTest {

    private static final String KB = "7475766492030701568";
    private static final String DOC = "7474332749017518080";
    private static final String SIBLING = "7474332749017518081";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void exactRemoteUriResolvesToTheBinding() {
        InMemoryKnowledgeExternalIndexBindingStore store = seeded();
        String root = OpenVikingProjectionUris.documentRootUri(KB, DOC);

        Optional<KnowledgeExternalIndexBinding> found =
                store.findByProviderAndLongestRemoteUriPrefix(KnowledgeExternalIndexBinding.OPENVIKING, root);

        assertEquals(DOC, found.orElseThrow().documentId());
    }

    @Test
    void derivedChildPathResolvesToTheDocumentRootBinding() {
        InMemoryKnowledgeExternalIndexBindingStore store = seeded();
        String root = OpenVikingProjectionUris.documentRootUri(KB, DOC);

        Optional<KnowledgeExternalIndexBinding> abstractHit =
                store.findByProviderAndLongestRemoteUriPrefix(
                        KnowledgeExternalIndexBinding.OPENVIKING, root + "/.abstract.md");
        Optional<KnowledgeExternalIndexBinding> nestedHit =
                store.findByProviderAndLongestRemoteUriPrefix(
                        KnowledgeExternalIndexBinding.OPENVIKING, root + "/source.md/source_v2.md");

        assertEquals(DOC, abstractHit.orElseThrow().documentId());
        assertEquals(DOC, nestedHit.orElseThrow().documentId());
    }

    @Test
    void shorterNumericDocumentIdIsNotAPrefixOfALongerSibling() {
        InMemoryKnowledgeExternalIndexBindingStore store = new InMemoryKnowledgeExternalIndexBindingStore();
        store.save(binding("12", "12"));
        store.save(binding("123", "12"));
        String hit = OpenVikingProjectionUris.documentRootUri("12", "123") + "/source.md";

        Optional<KnowledgeExternalIndexBinding> found =
                store.findByProviderAndLongestRemoteUriPrefix(KnowledgeExternalIndexBinding.OPENVIKING, hit);

        assertEquals("123", found.orElseThrow().documentId());
    }

    @Test
    void longestMatchingPrefixWinsWhenAChildUriIsAlsoStored() {
        InMemoryKnowledgeExternalIndexBindingStore store = new InMemoryKnowledgeExternalIndexBindingStore();
        String root = OpenVikingProjectionUris.documentRootUri(KB, DOC);
        store.save(binding(DOC, KB, root));
        store.save(binding(SIBLING, KB, root + "/source.md"));

        Optional<KnowledgeExternalIndexBinding> found = store.findByProviderAndLongestRemoteUriPrefix(
                KnowledgeExternalIndexBinding.OPENVIKING, root + "/source.md/source_v2.md");

        assertEquals(SIBLING, found.orElseThrow().documentId());
    }

    @Test
    void blankOrIllegalHitUriReturnsEmptyWithoutThrowing() {
        InMemoryKnowledgeExternalIndexBindingStore store = seeded();

        assertTrue(store.findByProviderAndLongestRemoteUriPrefix(
                KnowledgeExternalIndexBinding.OPENVIKING, "").isEmpty());
        assertTrue(store.findByProviderAndLongestRemoteUriPrefix(
                KnowledgeExternalIndexBinding.OPENVIKING, "   ").isEmpty());
        assertTrue(store.findByProviderAndLongestRemoteUriPrefix(
                KnowledgeExternalIndexBinding.OPENVIKING, null).isEmpty());
        assertTrue(store.findByProviderAndLongestRemoteUriPrefix(
                KnowledgeExternalIndexBinding.OPENVIKING,
                OpenVikingProjectionUris.documentRootUri(KB, DOC) + "//source.md").isEmpty());
    }

    @Test
    void pathTraversalIsResolvedBeforePrefixMatchSoItCannotPinTheWrongDocument() {
        InMemoryKnowledgeExternalIndexBindingStore store = seeded();
        String hit = OpenVikingProjectionUris.documentRootUri(KB, DOC) + "/../" + SIBLING;

        Optional<KnowledgeExternalIndexBinding> found =
                store.findByProviderAndLongestRemoteUriPrefix(KnowledgeExternalIndexBinding.OPENVIKING, hit);

        assertEquals(SIBLING, found.orElseThrow().documentId());
    }

    @Test
    void otherProviderIsIgnoredEvenWhenTheUriMatches() {
        InMemoryKnowledgeExternalIndexBindingStore store = seeded();
        String root = OpenVikingProjectionUris.documentRootUri(KB, DOC);

        assertTrue(store.findByProviderAndLongestRemoteUriPrefix("OTHER", root).isEmpty());
    }

    @Test
    void postgresMapperUsesIndexedEqualityOnAncestorUris() throws Exception {
        Path repoRoot = repoRoot();
        String mapper = Files.readString(repoRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeExternalIndexBindingMapper.java"));
        String store = Files.readString(repoRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeExternalIndexBindingStore.java"));
        String schema = Files.readString(repoRoot.resolve(
                "bootstrap/src/main/resources/sql/postgres/p11_openviking_projection.sql"));

        assertTrue(mapper.contains("remote_uri IN"), "lookup must be equality IN, not a leading-wildcard LIKE");
        assertTrue(mapper.contains("char_length(remote_uri) DESC"), "longest prefix must win in SQL");
        assertTrue(mapper.contains("LIMIT 1"));
        assertTrue(!mapper.contains("LIKE remote_uri") && !mapper.contains("remote_uri ||"),
                "LIKE remote_uri || '%' cannot use UNIQUE (provider, remote_uri)");
        String lookup = methodBody(store, "findByProviderAndLongestRemoteUriPrefix");
        assertTrue(lookup.contains("findByProviderAndLongestRemoteUriPrefix"), "Postgres store must implement the lookup");
        assertTrue(!lookup.contains("parseId("), "URI lookup must not parse the hit as a numeric id");
        assertTrue(schema.contains("UNIQUE (provider, remote_uri)"));
        assertTrue(store.contains("ancestorUrisInclusive"),
                "Postgres must query the bounded ancestor list, not scan a knowledge base in Java");
    }

    private static InMemoryKnowledgeExternalIndexBindingStore seeded() {
        InMemoryKnowledgeExternalIndexBindingStore store = new InMemoryKnowledgeExternalIndexBindingStore();
        store.save(binding(DOC, KB));
        store.save(binding(SIBLING, KB));
        return store;
    }

    private static KnowledgeExternalIndexBinding binding(String documentId, String knowledgeBaseId) {
        return binding(documentId, knowledgeBaseId, OpenVikingProjectionUris.documentRootUri(knowledgeBaseId, documentId));
    }

    private static KnowledgeExternalIndexBinding binding(String documentId, String knowledgeBaseId, String remoteUri) {
        return new KnowledgeExternalIndexBinding(
                KnowledgeExternalIndexBinding.OPENVIKING,
                documentId,
                knowledgeBaseId,
                remoteUri,
                "rd-bot:" + knowledgeBaseId + ":" + documentId,
                ExternalKnowledgeDesiredState.PRESENT,
                1L,
                "ck",
                ExternalKnowledgeObservedState.READY,
                1L,
                "ck",
                ExternalKnowledgeProjectionStatus.IN_SYNC,
                "",
                "",
                "",
                NOW,
                NOW,
                "",
                "",
                0L,
                NOW,
                NOW
        );
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        return Files.isDirectory(root.resolve("bootstrap")) ? root : root.getParent();
    }

    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(methodName);
        if (start < 0) {
            return "";
        }
        int brace = source.indexOf('{', start);
        if (brace < 0) {
            return source.substring(start);
        }
        int depth = 0;
        for (int index = brace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        return source.substring(start);
    }
}
