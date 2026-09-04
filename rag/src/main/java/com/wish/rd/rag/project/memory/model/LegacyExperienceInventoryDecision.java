package com.wish.rd.rag.project.memory.model;

/** Classification outcome for one legacy workflow experience row during inventory. */
public enum LegacyExperienceInventoryDecision {
    ELIGIBLE,
    DUPLICATE,
    AMBIGUOUS,
    REJECTED
}
