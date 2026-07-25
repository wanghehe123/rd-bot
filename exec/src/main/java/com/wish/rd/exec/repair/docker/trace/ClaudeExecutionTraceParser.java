package com.wish.rd.exec.repair.docker.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.trace.model.ClaudeExecutionTraceEntry;
import com.wish.rd.exec.repair.docker.trace.model.ClaudeExecutionTraceSnapshot;
import com.wish.rd.exec.repair.security.SecretRedactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Claude Code stream-json file and emits a bounded, redacted execution trace.
 *
 * <p>Only visible assistant text and coarse tool lifecycle information are surfaced. Hidden
 * reasoning blocks, raw tool arguments, and raw tool results are intentionally discarded.
 */
public final class ClaudeExecutionTraceParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_READ_BYTES = 1_048_576;
    private static final int MAX_LIMIT = 200;
    private static final int ARCHIVE_LIMIT = MAX_LIMIT;
    private static final int MAX_DETAIL_CHARS = 600;
    private static final Set<String> SAFE_KINDS = Set.of(
            "SESSION_STARTED", "ASSISTANT_TEXT", "TOOL_STARTED", "TOOL_COMPLETED", "RESULT", "RUNTIME_ERROR"
    );
    private static final Set<String> SAFE_SHELL_VERBS = Set.of(
            "git", "rg", "grep", "find", "sed", "cat", "ls", "pwd",
            "pytest", "python", "python3", "npm", "pnpm", "yarn", "mvn", "java"
    );

    /**
     * Reads a live event file. The cursor is the byte offset immediately after the last returned line.
     */
    public ClaudeExecutionTraceSnapshot parse(Path eventFile, long afterSequence, int limit) {
        if (eventFile == null || !Files.isRegularFile(eventFile)) {
            return ClaudeExecutionTraceSnapshot.unavailable("LIVE");
        }
        try {
            long size = Files.size(eventFile);
            long offset = Math.max(0L, size - MAX_READ_BYTES);
            byte[] bytes = readRange(eventFile, offset, size - offset);
            return parseBytes(bytes, offset, afterSequence, limit, "LIVE", offset > 0L, false);
        } catch (IOException ignored) {
            return ClaudeExecutionTraceSnapshot.unavailable("LIVE");
        }
    }

    /**
     * Parses a test or in-memory stream with the same safety policy as a live file.
     */
    public ClaudeExecutionTraceSnapshot parse(String eventsJsonl, long afterSequence, int limit) {
        byte[] bytes = eventsJsonl == null ? new byte[0] : eventsJsonl.getBytes(StandardCharsets.UTF_8);
        return parseBytes(bytes, 0L, afterSequence, limit, "LIVE", false, false);
    }

    /**
     * Produces an archive-safe JSON snapshot. It must never return the raw stream-json payload.
     */
    public String persistedSnapshot(Path eventFile) {
        ClaudeExecutionTraceSnapshot live = parseMostRecent(eventFile);
        ClaudeExecutionTraceSnapshot archived = new ClaudeExecutionTraceSnapshot(
                ClaudeExecutionTraceSnapshot.VERSION,
                "ARCHIVED",
                live.available(),
                live.finalized(),
                live.truncated() || live.hasMore(),
                false,
                live.nextSequence(),
                live.entries()
        );
        try {
            return OBJECT_MAPPER.writeValueAsString(archived);
        } catch (JsonProcessingException ignored) {
            return "{\"version\":1,\"source\":\"ARCHIVED\",\"available\":false,\"finalized\":false,\"truncated\":false,\"hasMore\":false,\"nextSequence\":0,\"entries\":[]}";
        }
    }

    /**
     * Accepts only the versioned snapshot schema. Legacy raw event files are rejected rather than exposed.
     */
    public ClaudeExecutionTraceSnapshot parsePersistedSnapshot(String storedSnapshot) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(storedSnapshot == null ? "" : storedSnapshot);
            if (root == null || !root.isObject()
                    || root.path("version").asInt(0) != ClaudeExecutionTraceSnapshot.VERSION
                    || !"ARCHIVED".equals(text(root, "source"))) {
                return ClaudeExecutionTraceSnapshot.unavailable("ARCHIVED");
            }
            List<ClaudeExecutionTraceEntry> entries = new ArrayList<>();
            JsonNode rawEntries = root.path("entries");
            if (rawEntries.isArray()) {
                for (JsonNode rawEntry : rawEntries) {
                    String kind = text(rawEntry, "kind");
                    if (!SAFE_KINDS.contains(kind)) {
                        continue;
                    }
                    entries.add(new ClaudeExecutionTraceEntry(
                            Math.max(0L, rawEntry.path("sequence").asLong()),
                            kind,
                            sanitize(text(rawEntry, "label")),
                            sanitize(text(rawEntry, "detail")),
                            rawEntry.path("error").asBoolean(false)
                    ));
                }
            }
            return new ClaudeExecutionTraceSnapshot(
                    ClaudeExecutionTraceSnapshot.VERSION,
                    "ARCHIVED",
                    root.path("available").asBoolean(false) || !entries.isEmpty(),
                    root.path("finalized").asBoolean(false),
                    root.path("truncated").asBoolean(false),
                    false,
                    Math.max(0L, root.path("nextSequence").asLong()),
                    entries
            );
        } catch (IOException ignored) {
            return ClaudeExecutionTraceSnapshot.unavailable("ARCHIVED");
        }
    }

    private ClaudeExecutionTraceSnapshot parseBytes(
            byte[] bytes,
            long baseOffset,
            long afterSequence,
            int requestedLimit,
            String source,
            boolean truncated,
            boolean retainMostRecent
    ) {
        if (bytes.length == 0) {
            return ClaudeExecutionTraceSnapshot.unavailable(source);
        }
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit <= 0 ? MAX_LIMIT : requestedLimit));
        int lineStart = skipPartialFirstLine(bytes, truncated);
        List<ClaudeExecutionTraceEntry> entries = new ArrayList<>();
        Map<String, String> toolsById = new HashMap<>();
        TraceState state = new TraceState();
        long nextSequence = Math.max(0L, afterSequence);

        for (int index = lineStart; index <= bytes.length; index++) {
            if (index < bytes.length && bytes[index] != '\n') {
                continue;
            }
            int lineEnd = index;
            if (lineEnd > lineStart && bytes[lineEnd - 1] == '\r') {
                lineEnd--;
            }
            long sequence = baseOffset + Math.min(bytes.length, index + 1L);
            processLine(
                    new String(bytes, lineStart, Math.max(0, lineEnd - lineStart), StandardCharsets.UTF_8),
                    sequence,
                    afterSequence,
                    entries,
                    limit,
                    toolsById,
                    state,
                    retainMostRecent
            );
            if (!entries.isEmpty()) {
                nextSequence = entries.getLast().sequence();
            }
            lineStart = index + 1;
        }
        return new ClaudeExecutionTraceSnapshot(
                ClaudeExecutionTraceSnapshot.VERSION,
                source,
                state.available,
                state.finalized,
                truncated,
                state.hasMore,
                nextSequence,
                entries
        );
    }

    /** Reads the same bounded tail as live polling but retains the final events, not the first ones in that tail. */
    private ClaudeExecutionTraceSnapshot parseMostRecent(Path eventFile) {
        if (eventFile == null || !Files.isRegularFile(eventFile)) {
            return ClaudeExecutionTraceSnapshot.unavailable("LIVE");
        }
        try {
            long size = Files.size(eventFile);
            long offset = Math.max(0L, size - MAX_READ_BYTES);
            byte[] bytes = readRange(eventFile, offset, size - offset);
            return parseBytes(bytes, offset, 0L, ARCHIVE_LIMIT, "LIVE", offset > 0L, true);
        } catch (IOException ignored) {
            return ClaudeExecutionTraceSnapshot.unavailable("LIVE");
        }
    }

    private static int skipPartialFirstLine(byte[] bytes, boolean truncated) {
        if (!truncated) {
            return 0;
        }
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                return index + 1;
            }
        }
        return bytes.length;
    }

    private void processLine(
            String line,
            long sequence,
            long afterSequence,
            List<ClaudeExecutionTraceEntry> entries,
            int limit,
            Map<String, String> toolsById,
            TraceState state,
            boolean retainMostRecent
    ) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode event = parseLine(line);
        if (event == null) {
            if (looksLikeRuntimeError(line)) {
                emit(entries, limit, afterSequence, state, retainMostRecent, new ClaudeExecutionTraceEntry(
                        sequence,
                        "RUNTIME_ERROR",
                        "Container runtime error",
                        runtimeErrorSummary(line),
                        true
                ));
            }
            return;
        }

        String type = text(event, "type");
        if ("system".equals(type) && "init".equals(text(event, "subtype"))) {
            emit(entries, limit, afterSequence, state, retainMostRecent, new ClaudeExecutionTraceEntry(
                    sequence, "SESSION_STARTED", "Session started", "Claude Code container session is ready.", false
            ));
            return;
        }
        if ("assistant".equals(type)) {
            emitAssistantEntries(
                    event.path("message").path("content"),
                    sequence,
                    afterSequence,
                    entries,
                    limit,
                    toolsById,
                    state,
                    retainMostRecent
            );
            return;
        }
        if ("user".equals(type)) {
            emitToolResults(
                    event.path("message").path("content"),
                    sequence,
                    afterSequence,
                    entries,
                    limit,
                    toolsById,
                    state,
                    retainMostRecent
            );
            return;
        }
        if ("result".equals(type)) {
            state.finalized = true;
            boolean error = resultIsError(event);
            emit(entries, limit, afterSequence, state, retainMostRecent, new ClaudeExecutionTraceEntry(
                    sequence,
                    "RESULT",
                    error ? "Claude Code reported failure" : "Claude Code completed",
                    error ? "Final result reported a failure." : "Final result event received.",
                    error
            ));
        }
    }

    private void emitAssistantEntries(
            JsonNode content,
            long sequence,
            long afterSequence,
            List<ClaudeExecutionTraceEntry> entries,
            int limit,
            Map<String, String> toolsById,
            TraceState state,
            boolean retainMostRecent
    ) {
        if (content.isTextual()) {
            emitVisibleText(content.asText(), sequence, afterSequence, entries, limit, state, retainMostRecent);
            return;
        }
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            String blockType = text(block, "type");
            if ("text".equals(blockType)) {
                emitVisibleText(
                        text(block, "text"),
                        sequence,
                        afterSequence,
                        entries,
                        limit,
                        state,
                        retainMostRecent
                );
                continue;
            }
            if (!"tool_use".equals(blockType)) {
                continue;
            }
            String tool = safeToolName(text(block, "name"));
            String label = toolDisplay(tool, text(block.path("input"), "command"));
            String toolUseId = text(block, "id");
            if (!toolUseId.isBlank()) {
                toolsById.put(toolUseId, label);
            }
            emit(entries, limit, afterSequence, state, retainMostRecent, new ClaudeExecutionTraceEntry(
                    sequence, "TOOL_STARTED", "Tool started", label, false
            ));
        }
    }

    private void emitVisibleText(
            String visibleText,
            long sequence,
            long afterSequence,
            List<ClaudeExecutionTraceEntry> entries,
            int limit,
            TraceState state,
            boolean retainMostRecent
    ) {
        String detail = sanitize(visibleText);
        if (!detail.isBlank()) {
            emit(entries, limit, afterSequence, state, retainMostRecent, new ClaudeExecutionTraceEntry(
                    sequence, "ASSISTANT_TEXT", "Claude Code", detail, false
            ));
        }
    }

    private void emitToolResults(
            JsonNode content,
            long sequence,
            long afterSequence,
            List<ClaudeExecutionTraceEntry> entries,
            int limit,
            Map<String, String> toolsById,
            TraceState state,
            boolean retainMostRecent
    ) {
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            if (!"tool_result".equals(text(block, "type"))) {
                continue;
            }
            String label = toolsById.getOrDefault(text(block, "tool_use_id"), "Tool");
            boolean error = block.path("is_error").asBoolean(false);
            emit(entries, limit, afterSequence, state, retainMostRecent, new ClaudeExecutionTraceEntry(
                    sequence,
                    "TOOL_COMPLETED",
                    error ? "Tool reported an error" : "Tool completed",
                    label + (error ? " reported an error" : " completed"),
                    error
            ));
        }
    }

    private static void emit(
            List<ClaudeExecutionTraceEntry> entries,
            int limit,
            long afterSequence,
            TraceState state,
            boolean retainMostRecent,
            ClaudeExecutionTraceEntry entry
    ) {
        state.available = true;
        long sequence = state.nextEventSequence(entry.sequence());
        ClaudeExecutionTraceEntry sequencedEntry = new ClaudeExecutionTraceEntry(
                sequence,
                entry.kind(),
                entry.label(),
                entry.detail(),
                entry.error()
        );
        if (sequence <= Math.max(0L, afterSequence)) {
            return;
        }
        if (entries.size() >= limit) {
            state.hasMore = true;
            if (!retainMostRecent) {
                return;
            }
            entries.removeFirst();
        }
        entries.add(sequencedEntry);
    }

    private static byte[] readRange(Path path, long offset, long length) throws IOException {
        int expectedLength = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, length));
        ByteBuffer buffer = ByteBuffer.allocate(expectedLength);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Continue until the bounded tail is consumed or the file stops growing.
            }
        }
        int actualLength = buffer.position();
        if (actualLength == expectedLength) {
            return buffer.array();
        }
        byte[] copy = new byte[actualLength];
        System.arraycopy(buffer.array(), 0, copy, 0, actualLength);
        return copy;
    }

    private static JsonNode parseLine(String line) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(line);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean resultIsError(JsonNode event) {
        String subtype = text(event, "subtype").toLowerCase(Locale.ROOT);
        return event.path("is_error").asBoolean(false)
                || subtype.contains("error")
                || subtype.contains("failure")
                || subtype.contains("failed");
    }

    private static boolean looksLikeRuntimeError(String line) {
        String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
        return normalized.contains("fatal")
                || normalized.contains("error")
                || normalized.contains("permission denied")
                || normalized.contains("timed out")
                || normalized.contains("timeout")
                || normalized.contains("eacces")
                || normalized.contains("enoent")
                || normalized.contains("failed");
    }

    private static String runtimeErrorSummary(String line) {
        String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
        if (normalized.contains("permission denied") || normalized.contains("eacces")) {
            return "Permission was denied inside the execution container.";
        }
        if (normalized.contains("timed out") || normalized.contains("timeout")) {
            return "A command timed out inside the execution container.";
        }
        if (normalized.contains("enoent") || normalized.contains("not found")) {
            return "A required command or file was not found in the execution container.";
        }
        return "The execution container emitted an error. Inspect the stage diagnostics for details.";
    }

    private static String toolDisplay(String tool, String command) {
        if (!"Bash".equals(tool)) {
            return tool;
        }
        String verb = command == null ? "" : command.strip().split("\\s+", 2)[0];
        if (verb.isBlank() || verb.contains("=")) {
            return "Bash";
        }
        String safeVerb = verb.replaceAll("[^A-Za-z0-9_.-]", "");
        return SAFE_SHELL_VERBS.contains(safeVerb) ? "Bash: " + safeVerb : "Bash";
    }

    private static String safeToolName(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_. -]", "").strip();
        return normalized.isBlank() ? "Tool" : truncate(normalized, 80);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        normalized = SecretRedactor.redactFreeform(normalized)
                .replaceAll(
                        "(?i)((?:\\\"?(?:token|api[_-]?key|password|secret|authorization)\\\"?)\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|[^\\s,;]+)",
                        "$1" + SecretRedactor.REDACTED
                );
        return truncate(normalized, MAX_DETAIL_CHARS);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() ? value.textValue().strip() : "";
    }

    private static final class TraceState {
        private boolean available;
        private boolean finalized;
        private boolean hasMore;
        private long lineSequence = -1L;
        private int eventIndex;

        private long nextEventSequence(long nextLineSequence) {
            if (lineSequence != nextLineSequence) {
                lineSequence = nextLineSequence;
                eventIndex = 0;
            }
            eventIndex++;
            if (nextLineSequence > Long.MAX_VALUE / 1_000L) {
                return Long.MAX_VALUE - (1_000L - eventIndex);
            }
            return nextLineSequence * 1_000L + eventIndex;
        }
    }
}
