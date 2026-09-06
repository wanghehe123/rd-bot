package com.wish.rd.engine.requirement.answer;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.ManagerDecideStages;

/**
 * Result of consuming {@code USER_ANSWER_RESUME}.
 *
 * @param completedResumeCommand completed resume command
 * @param nextCommand Manager continuation
 * @param boundTaskVersion post-CAS version
 * @param boundFencingToken post-CAS fence
 */
public record RequirementUserAnswerResumeResult(
        RequirementStageCommand completedResumeCommand,
        RequirementStageCommand nextCommand,
        long boundTaskVersion,
        long boundFencingToken
) {
    public RequirementUserAnswerResumeResult {
        if (completedResumeCommand == null || !UserAnswerResumeStages.isResume(completedResumeCommand.stage())) {
            throw new IllegalArgumentException("completed USER_ANSWER_RESUME is required");
        }
        if (nextCommand == null || !ManagerDecideStages.isManagerDecide(nextCommand.stage())) {
            throw new IllegalArgumentException("Manager continuation is required after USER_ANSWER_RESUME");
        }
    }
}
