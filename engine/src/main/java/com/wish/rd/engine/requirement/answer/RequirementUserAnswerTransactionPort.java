package com.wish.rd.engine.requirement.answer;

/** Host transaction boundary for operator answers to {@code WAITING_USER_INPUT}. */
public interface RequirementUserAnswerTransactionPort {

    /**
     * Records an operator answer and produces the durable {@code USER_ANSWER_RESUME} command.
     *
     * @param command digest-bound answer
     * @param actor operator identity
     * @param nowEpochMillis host clock
     * @return persisted resume command and post-CAS task identity
     */
    RequirementUserAnswerResult answer(AnswerRequirementCommand command, String actor, long nowEpochMillis);

    /**
     * Consumes one leased {@code USER_ANSWER_RESUME} command and enqueues the next Manager hop.
     *
     * @param command leased resume command
     * @param leaseOwner current owner
     * @param nowEpochMillis host clock
     * @return completed resume command and Manager continuation
     */
    RequirementUserAnswerResumeResult consumeAnswer(
            com.wish.rd.engine.requirement.job.model.RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis
    );
}
