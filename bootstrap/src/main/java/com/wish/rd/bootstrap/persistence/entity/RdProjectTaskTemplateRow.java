package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for {@code rd_project_task_templates}. */
public class RdProjectTaskTemplateRow {
    public Long projectId;
    public String taskType;
    public String name;
    public String actualBehavior;
    public String expectedBehavior;
    public String reproductionSteps;
    public String affectedScope;
    public String acceptanceCriteriaJson;
    public String requirementBody;
    public String expectedResult;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
