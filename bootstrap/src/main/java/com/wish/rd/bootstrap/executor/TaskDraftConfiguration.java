package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.draft.TaskDraftEngine;
import com.wish.rd.engine.draft.TaskDraftModelPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the optional task draft model without fabricating an AI fallback. */
@Configuration(proxyBeanMethods = false)
public class TaskDraftConfiguration {
    @Bean
    TaskDraftEngine taskDraftEngine(ObjectProvider<TaskDraftModelPort> modelPortProvider) {
        return new TaskDraftEngine(modelPortProvider.getIfAvailable(
                () -> TaskDraftModelPort.unavailable("task draft model credential is not configured")));
    }
}
