package com.wish.rd.engine.requirement.query;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.PiQaRemediationPlanner;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Projects a stage-result snapshot onto the frozen HTTP view.
 */
@Service
public class StageResultQueryEngine {

    /**
     * Projects a truncated preview view.
     *
     * @param snapshot raw snapshot
     * @return view
     */
    public StageResultView query(StageResultSnapshot snapshot) {
        return query(snapshot, false);
    }

    /**
     * Projects a stage result.
     *
     * @param snapshot raw snapshot
     * @param full whether to return the full readable body when persisted
     * @return view
     */
    public StageResultView query(StageResultSnapshot snapshot, boolean full) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        String roleBody = roleBody(snapshot);
        if (!snapshot.finalizedResultJson().isBlank() && !snapshot.commandId().isBlank()) {
            boolean truncated = !full && roleBody.length() > StageResultView.PREVIEW_CHARS;
            String content = truncated ? roleBody.substring(0, StageResultView.PREVIEW_CHARS) : roleBody;
            String path = "/admin/rd-tasks/" + snapshot.stage().taskId()
                    + "/stage-runs/" + snapshot.stage().stageRunId() + "/result/content";
            return view(snapshot, StageResultView.SOURCE_FINALIZATION, true, null,
                    content, truncated, path);
        }
        if (!snapshot.artifactPreview().isBlank()) {
            String content = snapshot.artifactPreview();
            boolean truncated = snapshot.previewTruncated() || content.length() > StageResultView.PREVIEW_CHARS;
            if (content.length() > StageResultView.PREVIEW_CHARS) {
                content = content.substring(0, StageResultView.PREVIEW_CHARS);
            }
            return view(snapshot, StageResultView.SOURCE_PREVIEW, false, StageResultView.FULL_RESULT_MISSING,
                    content, truncated, null);
        }
        return view(snapshot, StageResultView.SOURCE_UNAVAILABLE, false, StageResultView.FULL_RESULT_MISSING,
                "", false, null);
    }

    private String roleBody(StageResultSnapshot snapshot) {
        String raw = snapshot.finalizedResultJson();
        if (raw.isBlank()) {
            return "";
        }
        if (snapshot.stage().role() == AgentRole.QA_AGENT) {
            return PiQaRemediationPlanner.authoritativeQaResultJson(raw);
        }
        return raw;
    }

    private StageResultView view(
            StageResultSnapshot snapshot,
            String source,
            boolean available,
            String unavailableReason,
            String content,
            boolean truncated,
            String downloadPath
    ) {
        return new StageResultView(
                snapshot.stage().taskId(),
                snapshot.stage().stageRunId(),
                snapshot.stage().role().name(),
                snapshot.stage().attemptNo(),
                blankToNull(snapshot.artifactId()),
                blankToNull(snapshot.commandId()),
                blankToNull(snapshot.finalizationId()),
                source,
                available,
                unavailableReason,
                "application/json",
                content,
                truncated,
                hashReadable(content),
                downloadPath
        );
    }

    private static String hashReadable(String content) {
        try {
            if (content != null && looksLikeJsonObject(content)) {
                return CanonicalJsonSha256.digest(content);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required", missing);
        } catch (RuntimeException ignored) {
            byte[] digest;
            try {
                digest = MessageDigest.getInstance("SHA-256")
                        .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException missing) {
                throw new IllegalStateException("SHA-256 is required", missing);
            }
            return "sha256:" + HexFormat.of().formatHex(digest);
        }
    }

    private static boolean looksLikeJsonObject(String content) {
        String trimmed = content == null ? "" : content.strip();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
