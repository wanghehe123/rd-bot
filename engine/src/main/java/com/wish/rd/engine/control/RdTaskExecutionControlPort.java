package com.wish.rd.engine.control;

/** Infrastructure port for stopping the external executor associated with an RD task. */
@FunctionalInterface
public interface RdTaskExecutionControlPort {

    ExternalStopResult stop(String taskId, String reason);

    record ExternalStopResult(boolean stopped, String containerName, String message) {
        public ExternalStopResult {
            containerName = containerName == null ? "" : containerName.strip();
            message = message == null ? "" : message.strip();
        }
    }
}
