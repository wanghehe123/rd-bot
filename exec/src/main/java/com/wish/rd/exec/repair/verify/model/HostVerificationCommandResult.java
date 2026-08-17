package com.wish.rd.exec.repair.verify.model;

/**
 * Outcome of one allowlisted host verification command.
 *
 * <p>Produced by {@code HostVerificationCommandRunner} and consumed by
 * {@code HostVerificationExecutorAdapter} to record steps and classify failures.
 *
 * @param exitCode process exit code, or {@code -1} when the process timed out
 * @param output   combined stdout and stderr, length-bounded
 * @param timedOut whether the wall-clock budget expired before the process exited
 */
public record HostVerificationCommandResult(int exitCode, String output, boolean timedOut) {

    public HostVerificationCommandResult {
        output = output == null ? "" : output;
    }
}
