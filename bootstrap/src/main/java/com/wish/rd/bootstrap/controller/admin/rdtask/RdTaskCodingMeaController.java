package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.engine.requirement.query.CodingMeaBadRequestException;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.CodingMeaQueryEngine;
import com.wish.rd.engine.requirement.query.CodingMeaReadPort;
import com.wish.rd.engine.requirement.query.CodingMeaResponse;
import com.wish.rd.engine.requirement.query.CodingMeaSnapshot;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Read-only Coding MEA snapshot and Manager contract APIs.
 */
@RestController
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class RdTaskCodingMeaController {

    private final RagStreamTaskRegistry registry;
    private final CodingMeaReadPort readPort;
    private final CodingMeaQueryEngine queryEngine;
    private final ManagerDecisionStore managerDecisionStore;

    /**
     * Creates the HTTP adapter.
     */
    public RdTaskCodingMeaController(
            RagStreamTaskRegistry registry,
            CodingMeaReadPort readPort,
            CodingMeaQueryEngine queryEngine,
            ManagerDecisionStore managerDecisionStore
    ) {
        this.registry = registry;
        this.readPort = readPort;
        this.queryEngine = queryEngine;
        this.managerDecisionStore = managerDecisionStore;
    }

    /**
     * Returns one Coding MEA neighborhood snapshot.
     */
    @GetMapping("/admin/rd-tasks/{taskId}/coding-mea")
    public CodingMeaResponse codingMea(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "codingStageRunId", required = false) String codingStageRunId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor
    ) {
        requireRequirementTask(taskId);
        try {
            CodingMeaSnapshot snapshot = readPort.readSnapshot(taskId, codingStageRunId, limit, cursor);
            return queryEngine.query(snapshot);
        } catch (CodingMeaNotFoundException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        } catch (CodingMeaBadRequestException badRequest) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, badRequest.getMessage(), badRequest);
        }
    }

    /**
     * Returns one Manager decision including the full bounded contract.
     */
    @GetMapping("/admin/rd-tasks/{taskId}/manager-decisions/{decisionHash}")
    public ManagerDecisionView managerDecision(
            @PathVariable("taskId") String taskId,
            @PathVariable("decisionHash") String decisionHash
    ) {
        RdRequirementTask task = requireRequirementTask(taskId);
        String expected = decisionHash == null ? "" : decisionHash.strip().toLowerCase(Locale.ROOT);
        ManagerDecision decision = managerDecisionStore.listByTask(task.taskId()).stream()
                .filter(candidate -> expected.equals(candidate.decisionHash()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "manager decision not found"));
        return new ManagerDecisionView(
                decision.taskId(),
                decision.roundNo(),
                decision.sourceCommandId(),
                decision.decisionHash(),
                decision.route().name(),
                decision.executorRoute(),
                decision.targetRecordIds(),
                decision.boundedContract(),
                decision.rationale(),
                decision.stateVersion(),
                decision.stateHash()
        );
    }

    private RdRequirementTask requireRequirementTask(String taskId) {
        RdTask task;
        try {
            task = registry.getTask(taskId);
        } catch (NoSuchElementException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage(), missing);
        }
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "coding MEA is requirement-only");
        }
        return requirementTask;
    }

    /**
     * Full Manager decision body. Time is omitted so the domain hash stays unchanged.
     */
    public record ManagerDecisionView(
            String taskId,
            int roundNo,
            String sourceCommandId,
            String decisionHash,
            String route,
            String executorRoute,
            java.util.List<String> targetRecordIds,
            String boundedContract,
            String rationale,
            long stateVersion,
            String stateHash
    ) {
    }
}
