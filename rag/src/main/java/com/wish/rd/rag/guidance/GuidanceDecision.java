package com.wish.rd.rag.guidance;

import java.util.List;

public record GuidanceDecision(
        Action action,
        String prompt,
        List<String> candidates
) {

    public GuidanceDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        prompt = prompt == null ? "" : prompt;
    }

    public static GuidanceDecision none() {
        return new GuidanceDecision(Action.NONE, "", List.of());
    }

    public static GuidanceDecision prompt(String prompt, List<String> candidates) {
        return new GuidanceDecision(Action.PROMPT, prompt, candidates);
    }

    public enum Action {
        NONE,
        PROMPT
    }
}
