package com.wish.rd.exec.repair.docker.trace;

import com.wish.rd.exec.repair.docker.trace.model.ClaudeExecutionTraceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeExecutionTraceParserTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldExposeOnlyVisibleEventsAndKeepCursorWithinOneJsonLine() throws Exception {
        String events = """
                {"type":"system","subtype":"init"}
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"PRIVATE_CHAIN secret=never-show"},{"type":"text","text":"Inspecting compiler ordering."},{"type":"tool_use","id":"tool-1","name":"Bash","input":{"command":"git grep secret=never-show"}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"token=never-show","is_error":false}]}}
                {"type":"result","subtype":"success"}
                """;
        ClaudeExecutionTraceParser parser = new ClaudeExecutionTraceParser();

        ClaudeExecutionTraceSnapshot first = parser.parse(events, 0L, 1);
        ClaudeExecutionTraceSnapshot remainder = parser.parse(events, first.nextSequence(), 100);

        String visible = first.entries().toString() + remainder.entries();
        assertTrue(first.available());
        assertTrue(first.hasMore());
        assertEquals("SESSION_STARTED", first.entries().getFirst().kind());
        assertTrue(visible.contains("Inspecting compiler ordering."));
        assertTrue(visible.contains("Bash: git"));
        assertTrue(remainder.finalized());
        assertFalse(visible.contains("PRIVATE_CHAIN"));
        assertFalse(visible.contains("never-show"));
        assertFalse(visible.contains("git grep secret"));
    }

    @Test
    void shouldPersistAndReadOnlyTheVersionedSafeSnapshot() throws Exception {
        Path events = temporaryDirectory.resolve("claude-events.jsonl");
        Files.writeString(events, """
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"PRIVATE_CHAIN token=never-show"},{"type":"text","text":"Visible implementation step."}]}}
                {"type":"result","subtype":"success"}
                """);
        ClaudeExecutionTraceParser parser = new ClaudeExecutionTraceParser();

        String persisted = parser.persistedSnapshot(events);
        ClaudeExecutionTraceSnapshot archived = parser.parsePersistedSnapshot(persisted);

        assertTrue(persisted.contains("\"version\":1"));
        assertTrue(archived.available());
        assertEquals("ARCHIVED", archived.source());
        assertTrue(archived.entries().stream().anyMatch(entry -> entry.detail().contains("Visible implementation step.")));
        assertFalse(persisted.contains("PRIVATE_CHAIN"));
        assertFalse(persisted.contains("never-show"));
        assertFalse(parser.parsePersistedSnapshot("{\"type\":\"assistant\"}").available());
    }

    @Test
    void shouldNeverExposeShellEnvironmentAssignmentsFromToolCommands() {
        String events = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool-1","name":"Bash","input":{"command":"ANTHROPIC_API_KEY=super-secret-token curl https://example.test"}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"ok","is_error":false}]}}
                """;
        ClaudeExecutionTraceSnapshot trace = new ClaudeExecutionTraceParser().parse(events, 0L, 100);

        String visible = trace.entries().toString();
        assertTrue(visible.contains("Bash"));
        assertFalse(visible.contains("ANTHROPIC_API_KEY"));
        assertFalse(visible.contains("super-secret-token"));
        assertFalse(visible.contains("curl"));
    }

    @Test
    void shouldRedactJsonStyleSecretsFromVisibleAssistantText() {
        String events = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Runtime config {\\\"api_key\\\":\\\"super-secret-value\\\",\\\"password\\\":\\\"also-secret\\\"}"}]}}
                """;

        ClaudeExecutionTraceSnapshot trace = new ClaudeExecutionTraceParser().parse(events, 0L, 100);

        String visible = trace.entries().toString();
        assertTrue(visible.contains("<redacted>"));
        assertFalse(visible.contains("super-secret-value"));
        assertFalse(visible.contains("also-secret"));
    }

    @Test
    void shouldPersistTheMostRecentBoundedTraceEvents() throws Exception {
        StringBuilder events = new StringBuilder();
        for (int index = 0; index < 205; index++) {
            events.append("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"step-")
                    .append(index)
                    .append("\"}]}}\n");
        }
        Path eventFile = temporaryDirectory.resolve("many-claude-events.jsonl");
        Files.writeString(eventFile, events);

        ClaudeExecutionTraceSnapshot archived = new ClaudeExecutionTraceParser()
                .parsePersistedSnapshot(new ClaudeExecutionTraceParser().persistedSnapshot(eventFile));

        String visible = archived.entries().toString();
        assertEquals(200, archived.entries().size());
        assertTrue(visible.contains("step-204"));
        assertFalse(visible.contains("step-0"));
    }
}
