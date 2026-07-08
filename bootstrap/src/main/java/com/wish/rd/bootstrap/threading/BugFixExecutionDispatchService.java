package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.model.RdBotFixCommand;
import com.wish.rd.engine.bugfix.model.RdBotFixResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Dispatches long-running Bug 修复 workflows away from HTTP request threads.
 */
@Service
public class BugFixExecutionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(BugFixExecutionDispatchService.class);

    private final RdBotFixEngine fixEngine;
    private final AsyncTaskExecutor executor;

    public BugFixExecutionDispatchService(
            RdBotFixEngine fixEngine,
            @Qualifier(RdBotThreadPoolConfiguration.REPAIR_QUEUE_EXECUTOR_BEAN)
            AsyncTaskExecutor executor
    ) {
        this.fixEngine = fixEngine;
        this.executor = executor;
    }

    /**
     * Enqueues one Bug 修复 run.
     *
     * @param command Bug 修复启动命令
     * @return future completed with the run result or execution failure
     */
    public CompletableFuture<RdBotFixResult> submit(RdBotFixCommand command) {
        CompletableFuture<RdBotFixResult> future = new CompletableFuture<>();
        try {
            executor.execute(() -> run(command, future));
        } catch (TaskRejectedException exception) {
            future.completeExceptionally(exception);
            throw new IllegalStateException(
                    "bug-fix execution thread pool rejected task: " + command.ticket().ticketId(),
                    exception
            );
        }
        return future;
    }

    private void run(RdBotFixCommand command, CompletableFuture<RdBotFixResult> future) {
        try {
            RdBotFixResult result = fixEngine.runBugFix(command);
            future.complete(result);
        } catch (RuntimeException exception) {
            log.error("bug-fix execution failed in isolated thread pool, ticketId={}",
                    command.ticket().ticketId(), exception);
            future.completeExceptionally(exception);
        }
    }
}
