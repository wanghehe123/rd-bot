package com.wish.rd.rag.project.agent.model;

/** Frozen runtime context loading mode for audit and enforcement phases. */
public enum RuntimeContextPolicyMode {
  LEGACY_OBSERVE_ONLY,
  ROOT_ONLY,
  ROOT_AND_ALLOWLISTED_NESTED
}
