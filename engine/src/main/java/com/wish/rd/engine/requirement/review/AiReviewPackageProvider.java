package com.wish.rd.engine.requirement.review;

import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

/** Supplies the immutable evidence package consumed by one AI review attempt. */
@FunctionalInterface
public interface AiReviewPackageProvider {

    AiReviewPackage build(RdRequirementTask task, String deterministicReviewJson);
}
