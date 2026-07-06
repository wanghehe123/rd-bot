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
        boolean legal = switch (source) {
            case PENDING -> target == AgentStageStatus.CONTEXT_READY
                    || target == AgentStageStatus.SKIPPED
                    || target == AgentStageStatus.CANCELLED;
            case CONTEXT_READY -> target == AgentStageStatus.DISPATCHING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case DISPATCHING -> target == AgentStageStatus.RUNNING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case RUNNING -> target == AgentStageStatus.RESULT_COLLECTING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN
                    || target == AgentStageStatus.CANCELLED;
            case RESULT_COLLECTING -> target == AgentStageStatus.VERIFYING
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case VERIFYING -> target == AgentStageStatus.SUCCEEDED
                    || target == AgentStageStatus.FAILED_RETRYABLE
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case FAILED_RETRYABLE -> target == AgentStageStatus.RECOVERING;
            case RECOVERING -> target == AgentStageStatus.CONTEXT_READY
                    || target == AgentStageStatus.DISPATCHING
                    || target == AgentStageStatus.FAILED_NEEDS_HUMAN;
            case SUCCEEDED, FAILED_NEEDS_HUMAN, SKIPPED, CANCELLED -> false;
        };
        if (!legal) {
            throw new IllegalStateException("illegal agent stage status transition: " + source + " -> " + target);
        }
    }
}
