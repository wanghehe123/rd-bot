package com.wish.rd.rag.retrieval;

import com.wish.rd.rag.project.memory.ProjectMemorySearchPort;
import com.wish.rd.rag.project.memory.ProjectMemorySearchResult;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchHit;
import com.wish.rd.rag.retrieval.impl.ProjectMemoryRetrievalChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemoryRetrievalChannelTest {
    @Test
    void emitsIndependentMemoryUrisAndExplainsSelectionWithoutLegacyExperienceWrapping() {
        ProjectMemorySearchPort port = request -> new ProjectMemorySearchResult(List.of(
                new ProjectMemorySearchHit("44", 3L, "101", "CODING_AGENT", true, true, true, .9, "build", "a".repeat(64))
        ), 1);

        var evidence = new ProjectMemoryRetrievalChannel(port).retrieve("101", "CODING_AGENT", "build", 3, .5, 1L);

        assertEquals("rd-memory://projects/101/memories/44/revisions/3", evidence.getFirst().sourceUri());
        assertEquals("UNTRUSTED_PROJECT_MEMORY lexical relevance", evidence.getFirst().selectionReason());
    }
}
