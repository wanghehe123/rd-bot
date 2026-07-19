package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/** Read-only HTTP adapter for durable requirement delivery job state. */
@RestController
public class RequirementDeliveryJobController {

    private final RequirementDeliveryJobStore jobStore;

    public RequirementDeliveryJobController(RequirementDeliveryJobStore jobStore) {
        this.jobStore = jobStore;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/delivery-job")
    public RequirementDeliveryJob get(@PathVariable("taskId") String taskId) {
        return jobStore.findByTask(taskId)
                .orElseThrow(() -> new NoSuchElementException("requirement delivery job not found: " + taskId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NoSuchElementException exception) {
        return Map.of("message", exception.getMessage() == null
                ? "requirement delivery job not found"
                : exception.getMessage());
    }
}
