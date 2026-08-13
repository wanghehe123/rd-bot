package com.wish.rd.rag.retrieval.navigator.model;

import java.time.Duration;

/**
 * 三层导航的预算。本记录只保存已声明的上限，不执行循环；循环接入后必须按这些值停止。
 *
 * @param maxRounds       最大轮数
 * @param l0Candidates    每轮 L0 候选上限
 * @param l1Expansions    每轮 L1 展开上限
 * @param l2Expansions    每轮 L2 展开上限
 * @param maxRemoteCalls  单次检索远端请求总数
 * @param tokenBudget     单次检索 Token 上限
 * @param timeBudget      单次检索墙钟上限
 */
public record NavigatorSettings(
        int maxRounds,
        int l0Candidates,
        int l1Expansions,
        int l2Expansions,
        int maxRemoteCalls,
        int tokenBudget,
        Duration timeBudget
) {

    public NavigatorSettings {
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("rd.rag.navigator.max-rounds must be positive");
        }
        if (l0Candidates <= 0) {
            throw new IllegalArgumentException("rd.rag.navigator.l0-candidates must be positive");
        }
        if (l1Expansions <= 0) {
            throw new IllegalArgumentException("rd.rag.navigator.l1-expansions must be positive");
        }
        if (l2Expansions <= 0) {
            throw new IllegalArgumentException("rd.rag.navigator.l2-expansions must be positive");
        }
        if (maxRemoteCalls <= 0) {
            throw new IllegalArgumentException("rd.rag.navigator.max-remote-calls must be positive");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("rd.rag.navigator.token-budget must be positive");
        }
        if (timeBudget == null || timeBudget.isZero() || timeBudget.isNegative()) {
            throw new IllegalArgumentException("rd.rag.navigator.time-budget must be a positive duration");
        }
    }

    /**
     * 计划默认值：3 轮 / L0 20 / L1 6 / L2 3 / 15 次远端 / 8000 token / 20 秒。
     *
     * @return 默认预算
     */
    public static NavigatorSettings defaults() {
        return new NavigatorSettings(3, 20, 6, 3, 15, 8_000, Duration.ofSeconds(20));
    }
}
