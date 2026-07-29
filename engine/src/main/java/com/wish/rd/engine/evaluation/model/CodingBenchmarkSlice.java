package com.wish.rd.engine.evaluation.model;

/**
 * Pre-registered source slice for coding benchmark cases.
 *
 * <p>Only {@link #FRESH_PRIMARY} contributes to the low-exposure primary conclusion. Public
 * anchor cases remain in the campaign for reproducible ecosystem comparison.</p>
 */
public enum CodingBenchmarkSlice {
    FRESH_PRIMARY,
    PUBLIC_ANCHOR
}
