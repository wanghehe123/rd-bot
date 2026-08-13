package com.wish.rd.rag.knowledge.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenVikingLocalRetrievalBaselineTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldIndexTwentyToFiftySamplesAndReachPerfectRecallAtEight() throws Exception {
        JsonNode corpus = readJson("/openviking-baseline/corpus.json");
        JsonNode questions = readJson("/openviking-baseline/gold-questions.json");
        assertTrue(corpus.size() >= 20 && corpus.size() <= 50, "WP-0 corpus must contain 20-50 documents");
        assertEquals(corpus.size(), questions.size(), "each sample needs exactly one gold question");

        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        String knowledgeBaseId = workspace.createBase(new CreateKnowledgeBaseCommand(
                "openviking-wp0-baseline",
                "LOCAL retrieval baseline frozen by WP-0"
        )).id();
        for (JsonNode document : corpus) {
            String markdown = markdown(document);
            workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                    knowledgeBaseId,
                    document.path("id").asText(),
                    document.path("knowledgeType").asText("document"),
                    "text/markdown",
                    markdown.getBytes(StandardCharsets.UTF_8),
                    ChunkingMode.STRUCTURE_AWARE,
                    256,
                    32
            ));
        }

        List<Long> latenciesNanos = new ArrayList<>();
        int hits = 0;
        for (JsonNode question : questions) {
            long started = System.nanoTime();
            List<RetrievedChunk> ranked = workspace.vectorStore().vectorSearch(
                    question.path("query").asText(),
                    List.of(knowledgeBaseId),
                    8
            );
            latenciesNanos.add(System.nanoTime() - started);
            boolean found = ranked.stream().anyMatch(chunk ->
                    question.path("expectedDocumentId").asText().equals(chunk.sourceName()));
            if (found) {
                hits++;
            }
        }

        latenciesNanos.sort(Comparator.naturalOrder());
        double recallAt8 = hits / (double) questions.size();
        long p50Nanos = percentile(latenciesNanos, 0.50d);
        long p95Nanos = percentile(latenciesNanos, 0.95d);
        assertEquals(1.0d, recallAt8, 0.0001d, "LOCAL baseline Recall@8 must stay perfect on this corpus");
        assertTrue(p95Nanos < 50_000_000L, "LOCAL overlap search p95 must stay under 50ms, was " + p95Nanos);
        assertTrue(p50Nanos <= p95Nanos);
        assertTrue(
                workspace.vectorStore().allChunks().size() >= corpus.size(),
                "every sample must produce at least one indexed chunk"
        );
    }

    private static JsonNode readJson(String classpath) throws Exception {
        try (InputStream input = OpenVikingLocalRetrievalBaselineTest.class.getResourceAsStream(classpath)) {
            if (input == null) {
                throw new IllegalStateException("missing classpath resource: " + classpath);
            }
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static String markdown(JsonNode document) {
        return "# " + document.path("title").asText() + "\n\n"
                + document.path("body").asText() + "\n\n"
                + "Unique token: `" + document.path("uniqueToken").asText() + "`\n";
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }
}
