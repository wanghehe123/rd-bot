package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.util.Locale;

/** Shared container naming for one coding-benchmark trial across adapter and executor. */
final class CodingBenchmarkTrialNaming {

    private CodingBenchmarkTrialNaming() {}

    static String trialSuffix(CodingBenchmarkTrial trial) {
        return trial.caseId() + "-" + trial.arm().name().toLowerCase(Locale.ROOT);
    }

    static String relayContainerName(CodingBenchmarkTrial trial) {
        return "rd-eval-relay-" + trialSuffix(trial);
    }

    static String relayHostname(CodingBenchmarkTrial trial) {
        return relayContainerName(trial);
    }
}
