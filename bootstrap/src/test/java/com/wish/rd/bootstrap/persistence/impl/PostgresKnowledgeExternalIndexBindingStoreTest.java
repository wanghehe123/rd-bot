package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.KnowledgeExternalIndexBindingRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeExternalIndexBindingMapper;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * URI 反查不得把命中当成数字 id 去 parse，也不得在空白入参时访问 mapper。
 */
class PostgresKnowledgeExternalIndexBindingStoreTest {

    private static final String KB = "7475766492030701568";
    private static final String DOC = "7474332749017518080";

    @Test
    void blankHitUriReturnsEmptyWithoutTouchingTheMapper() {
        KnowledgeExternalIndexBindingMapper mapper = mock(KnowledgeExternalIndexBindingMapper.class);
        PostgresKnowledgeExternalIndexBindingStore store = new PostgresKnowledgeExternalIndexBindingStore(mapper);

        assertTrue(store.findByProviderAndLongestRemoteUriPrefix("OPENVIKING", "").isEmpty());
        assertTrue(store.findByProviderAndLongestRemoteUriPrefix("OPENVIKING", "   ").isEmpty());
        assertTrue(store.findByProviderAndLongestRemoteUriPrefix("OPENVIKING", null).isEmpty());
        verify(mapper, never()).selectLongestRemoteUriPrefix(anyString(), any());
    }

    @Test
    void childPathQueriesTheDocumentRootAsAnIndexedAncestor() {
        KnowledgeExternalIndexBindingMapper mapper = mock(KnowledgeExternalIndexBindingMapper.class);
        KnowledgeExternalIndexBindingRow row = new KnowledgeExternalIndexBindingRow();
        row.provider = KnowledgeExternalIndexBinding.OPENVIKING;
        row.documentId = Long.parseLong(DOC);
        row.knowledgeBaseId = Long.parseLong(KB);
        row.remoteUri = OpenVikingProjectionUris.documentRootUri(KB, DOC);
        row.ownershipMarker = OpenVikingProjectionUris.ownershipMarker(KB, DOC);
        row.desiredState = ExternalKnowledgeDesiredState.PRESENT.name();
        row.desiredVersion = 1L;
        row.desiredChecksum = "ck";
        row.observedState = ExternalKnowledgeObservedState.READY.name();
        row.observedVersion = 1L;
        row.observedChecksum = "ck";
        row.projectionStatus = ExternalKnowledgeProjectionStatus.IN_SYNC.name();
        row.remoteTaskId = "";
        row.semanticConfigFingerprint = "";
        row.lastErrorCode = "";
        row.lastErrorMessage = "";
        row.rowVersion = 0L;
        when(mapper.selectLongestRemoteUriPrefix(anyString(), any())).thenReturn(row);

        PostgresKnowledgeExternalIndexBindingStore store = new PostgresKnowledgeExternalIndexBindingStore(mapper);
        String hit = OpenVikingProjectionUris.documentRootUri(KB, DOC) + "/.abstract.md";

        KnowledgeExternalIndexBinding found = store
                .findByProviderAndLongestRemoteUriPrefix(KnowledgeExternalIndexBinding.OPENVIKING, hit)
                .orElseThrow();

        assertEquals(DOC, found.documentId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> uris = ArgumentCaptor.forClass(List.class);
        verify(mapper).selectLongestRemoteUriPrefix(anyString(), uris.capture());
        assertTrue(uris.getValue().contains(OpenVikingProjectionUris.documentRootUri(KB, DOC)));
        assertTrue(uris.getValue().contains(hit));
        assertTrue(!uris.getValue().contains(OpenVikingProjectionUris.documentRootUri(KB, "12")));
    }
}
