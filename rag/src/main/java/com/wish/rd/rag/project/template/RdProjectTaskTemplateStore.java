package com.wish.rd.rag.project.template;

import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;

import java.util.Optional;

/** Persistence port for project task templates. */
public interface RdProjectTaskTemplateStore {
    RdProjectTaskTemplate save(RdProjectTaskTemplate template);

    Optional<RdProjectTaskTemplate> find(String projectId, String taskType);
}
