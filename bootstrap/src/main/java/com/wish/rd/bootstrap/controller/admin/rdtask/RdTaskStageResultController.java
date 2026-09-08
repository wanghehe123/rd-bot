package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.StageResultQueryEngine;
import com.wish.rd.engine.requirement.query.StageResultReadPort;
import com.wish.rd.engine.requirement.query.StageResultView;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

/**
 * Read-only stage result APIs.
 */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdTaskStageResultController {

    private final RagStreamTaskRegistry registry;
    private final StageResultReadPort readPort;
    private final StageResultQueryEngine queryEngine;

    /**
     * Creates the HTTP adapter.
     */
    public RdTaskStageResultController(
            RagStreamTaskRegistry registry,
            StageResultReadPort readPort,
            StageResultQueryEngine queryEngine
    ) {
        this.registry = registry;
        this.readPort = readPort;
        this.queryEngine = queryEngine;
    }

    /**
     * Returns the truncated or preview stage result.
     */
    @GetMapping("/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result")
    public StageResultView result(
            @PathVariable("taskId") String taskId,
            @PathVariable("stageRunId") String stageRunId
    ) {
        requireRequirementTask(taskId);
        try {
            return queryEngine.query(readPort.read(taskId, stageRunId), false);
        } catch (CodingMeaNotFoundException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    /**
     * Returns the full readable role JSON when a finalized result exists.
     */
    @GetMapping("/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result/content")
    public ResponseEntity<byte[]> resultContent(
            @PathVariable("taskId") String taskId,
            @PathVariable("stageRunId") String stageRunId
    ) {
        requireRequirementTask(taskId);
        StageResultView view;
        try {
            view = queryEngine.query(readPort.read(taskId, stageRunId), true);
        } catch (CodingMeaNotFoundException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
        if (!StageResultView.SOURCE_FINALIZATION.equals(view.source()) || view.content() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, StageResultView.FULL_RESULT_MISSING);
        }
        byte[] body = view.content().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"stage-" + stageRunId + ".json\"")
                .body(body);
    }

    private RdRequirementTask requireRequirementTask(String taskId) {
        RdTask task;
        try {
            task = registry.getTask(taskId);
        } catch (NoSuchElementException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage(), missing);
        }
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "stage result is requirement-only");
        }
        return requirementTask;
    }
}
