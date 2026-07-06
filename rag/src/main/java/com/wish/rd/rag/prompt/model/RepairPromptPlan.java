package com.wish.rd.rag.prompt.model;

import com.wish.rd.rag.rewrite.model.RewriteResult;

import java.util.List;

public record RepairPromptPlan(
        PromptScene scene,
        RewriteResult rewrite,
        String systemPrompt,
        String userPrompt,
        List<String> promptSections,
        List<String> evidenceChunkIds
) {

    public RepairPromptPlan {
        scene = scene == null ? PromptScene.EMPTY : scene;
        rewrite = rewrite == null ? new RewriteResult("", List.of()) : rewrite;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userPrompt = userPrompt == null ? "" : userPrompt;
        promptSections = promptSections == null ? List.of() : List.copyOf(promptSections);
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
    }
}
