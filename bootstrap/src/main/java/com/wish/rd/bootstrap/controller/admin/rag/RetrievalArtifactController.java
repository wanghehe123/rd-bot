package com.wish.rd.bootstrap.controller.admin.rag;

import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Serves only redacted artifact metadata and previews, never raw prompts or document bodies. */
@RestController
public class RetrievalArtifactController {

    private final RetrievalRunStore runStore;

    public RetrievalArtifactController(RetrievalRunStore runStore) {
        this.runStore = runStore;
    }

    @GetMapping("/admin/rag-retrieval-runs/{runId}/artifacts")
    public List<RetrievalArtifactView> artifacts(@PathVariable("runId") String runId) {
        runStore.find(runId).orElseThrow(RetrievalArtifactNotFoundException::new);
        return runStore.listArtifacts(runId).stream().map(RetrievalArtifactView::from).toList();
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class RetrievalArtifactNotFoundException extends RuntimeException {
    }

    public record RetrievalArtifactView(
            String artifactId, String runId, String stepId, String artifactType, String artifactUri,
            String contentPreview, String contentHash, boolean redacted, long createdAtEpochMillis
    ) {
        static RetrievalArtifactView from(RetrievalRunArtifact artifact) {
            return new RetrievalArtifactView(
                    artifact.artifactId(), artifact.runId(), "", artifact.artifactType(), artifact.artifactUri(),
                    artifact.contentPreview(), artifact.contentHash(), artifact.redacted(), artifact.createdAtEpochMillis()
            );
        }
    }
}
