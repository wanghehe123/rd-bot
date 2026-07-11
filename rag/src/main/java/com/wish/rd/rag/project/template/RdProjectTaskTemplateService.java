package com.wish.rd.rag.project.template;

import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplateCommand;

/** Project task template use case. */
public class RdProjectTaskTemplateService {

    private final RdProjectTaskTemplateStore store;
    private final java.util.function.Consumer<String> projectValidator;

    public RdProjectTaskTemplateService(RdProjectTaskTemplateStore store) {
        this(store, ignored -> { });
    }

    public RdProjectTaskTemplateService(
            RdProjectTaskTemplateStore store,
            java.util.function.Consumer<String> projectValidator
    ) {
        this.store = store;
        this.projectValidator = projectValidator == null ? ignored -> { } : projectValidator;
    }

    public RdProjectTaskTemplate get(String projectId, String taskType) {
        projectValidator.accept(projectId);
        String normalized = RdProjectTaskTemplate.normalizeTaskType(taskType);
        return store.find(projectId, normalized).orElseGet(() -> new RdProjectTaskTemplate(
                projectId, normalized, "", "", "", "", "", java.util.List.of(), "", "", 0L, 0L));
    }

    public RdProjectTaskTemplate update(
            String projectId,
            String taskType,
            RdProjectTaskTemplateCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        RdProjectTaskTemplate current = get(projectId, taskType);
        long now = System.currentTimeMillis();
        return store.save(new RdProjectTaskTemplate(
                projectId,
                current.taskType(),
                command.name(),
                command.actualBehavior(),
                command.expectedBehavior(),
                command.reproductionSteps(),
                command.affectedScope(),
                command.acceptanceCriteria(),
                command.requirementBody(),
                command.expectedResult(),
                current.createTimeEpochMillis() > 0 ? current.createTimeEpochMillis() : now,
                now
        ));
    }
}
