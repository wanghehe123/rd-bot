package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.admin.ingestion.IngestionAdminEngine;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTask;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTaskCommand;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

/**
 * Executes document ingestion work on the isolated ingestion pool.
 *
 * <p>The controller still waits for the current task result for API
 * compatibility, but parsing/chunking/indexing no longer consumes request
 * handling capacity and is bounded by ingestion-pool back-pressure.
 */
@Service
public class IngestionTaskExecutionService {

    private final IngestionAdminEngine adminEngine;
    private final AsyncTaskExecutor executor;

    /**
     * Creates the isolated ingestion execution service.
     *
     * @param adminEngine ingestion management engine
     * @param executor    dedicated ingestion executor
     */
    public IngestionTaskExecutionService(
            IngestionAdminEngine adminEngine,
            @Qualifier(RdBotThreadPoolConfiguration.INGESTION_EXECUTOR_BEAN)
            AsyncTaskExecutor executor
    ) {
        this.adminEngine = adminEngine;
        this.executor = executor;
    }

    /**
     * Executes one ingestion task and waits for the result.
     *
     * @param command ingestion task command
     * @return persisted ingestion task result
     */
    public ManagedIngestionTask execute(ManagedIngestionTaskCommand command) {
        try {
            return executor.submit(() -> adminEngine.executeTask(command)).get();
        } catch (TaskRejectedException exception) {
            throw new IllegalStateException("ingestion thread pool rejected task", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingestion task interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("ingestion task failed", cause);
        }
    }
}
