package com.wish.rd.engine.requirement.answer;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;

/**
 * Result of producing {@code USER_ANSWER_RESUME}.
 *
 * @param resumeCommand durable resume command
 * @param boundTaskVersion version captured on the resume command
 * @param boundFencingToken fence captured on the resume command
 */
public record RequirementUserAnswerResult(
        RequirementStageCommand resumeCommand,
        long boundTaskVersion,
        long boundFencingToken
) {
    public RequirementUserAnswerResult {
        if (resumeCommand == null || !UserAnswerResumeStages.isResume(resumeCommand.stage())) {
            throw new IllegalArgumentException("USER_ANSWER_RESUME command is required");
        }
    }
}
