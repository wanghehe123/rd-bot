package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.draft.TaskDraftEngine;
import com.wish.rd.engine.draft.model.TaskDraftRequest;
import com.wish.rd.engine.draft.model.TaskDraftResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AI draft endpoint. It does not call task creation or submission services. */
@RestController
public class TaskDraftController {
    private final TaskDraftEngine engine;

    public TaskDraftController(TaskDraftEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/admin/rd-task-drafts/complete")
    public TaskDraftResult complete(@RequestBody TaskDraftRequest request) {
        return engine.complete(request);
    }
}
