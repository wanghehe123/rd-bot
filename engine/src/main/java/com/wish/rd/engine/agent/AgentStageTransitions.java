package com.wish.rd.engine.agent;

import com.wish.rd.engine.agent.model.AgentStageStatus;


/**
 * Agent 阶段状态流转策略。
 *
 * <p>供内存和 PostgreSQL store 复用，避免不同持久化实现出现状态机分叉。
 */
public final class AgentStageTransitions {

    private AgentStageTransitions() {
    }

    /**
     * 校验阶段状态流转是否合法。
     *
     * @param source 源状态
     * @param target 目标状态
     */
    public static void ensureTransition(AgentStageStatus source, AgentStageStatus target) {
        if (source == target) {
            return;
        }
        if (source == null || target == null) {
            throw new IllegalStateException("agent stage status must not be null");
        }
        if (!source.isTerminal() && target == AgentStageStatus.CANCELLED) {
            return;
        }
        boolean legal = switch (source) {
            case PENDING -> target == AgentStageStatus.CONTEXT_READY
                    || target == AgentStageStatus.SKIPPED
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case CONTEXT_READY -> target == AgentStageStatus.DISPATCHING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case DISPATCHING -> target == AgentStageStatus.RUNNING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case RUNNING -> target == AgentStageStatus.RESULT_COLLECTING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case RESULT_COLLECTING -> target == AgentStageStatus.VERIFYING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case VERIFYING -> target == AgentStageStatus.SUCCEEDED
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case FAILED_RETRYABLE -> false;
            case RECOVERING -> target == AgentStageStatus.CONTEXT_READY
                    || target == AgentStageStatus.DISPATCHING
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case SUCCEEDED, FAILED_NEEDS_HUMAN, SKIPPED, CANCELLED -> false;
        };
        if (!legal) {
            throw new IllegalStateException("illegal agent stage status transition: " + source + " -> " + target);
        }
    }

    /**
     * Returns whether a persisted attempt has advanced far enough that a new
     * recovery dispatch must close it and create a fresh attempt instead of
     * replaying a backwards state transition.
     */
    public static boolean requiresFreshAttemptOnRecovery(AgentStageStatus status) {
        return status == AgentStageStatus.CONTEXT_READY
                || status == AgentStageStatus.DISPATCHING
                || status == AgentStageStatus.RUNNING
                || status == AgentStageStatus.RESULT_COLLECTING
                || status == AgentStageStatus.VERIFYING;
    }
}
