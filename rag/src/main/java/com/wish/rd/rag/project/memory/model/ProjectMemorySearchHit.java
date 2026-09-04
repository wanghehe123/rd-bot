package com.wish.rd.rag.project.memory.model;

/** Pre-filtered candidate plus auditable ranking inputs. */
public record ProjectMemorySearchHit(String memoryId, long revisionVersion, String projectId, String role,
                                     boolean activeHead, boolean valid, boolean redacted, double quality,
                                     String summary, String contentHash) {
}
