import { useCallback, useEffect, useMemo } from "react";
import { useSearchParams } from "react-router-dom";

export const ALL_PROJECTS_SCOPE = "all";
export const PROJECT_SCOPE_PARAM = "projectId";
export const LAST_PROJECT_STORAGE_KEY = "rd-bot:last-project-id";

export type ProjectScopeProject = {
  projectId: string;
  enabled: boolean;
};

export type UseProjectScopeOptions = {
  allowAll?: boolean;
};

export type ProjectScope = {
  projectId: string;
  isAllProjects: boolean;
  setProjectId: (projectId: string) => void;
};

export function resolveProjectScope(
  urlProjectId: string | null,
  rememberedProjectId: string | null,
  enabledProjectIds: readonly string[],
  allowAll: boolean
): string {
  const normalizedUrlProjectId = normalizeProjectId(urlProjectId);
  const normalizedRememberedProjectId = normalizeProjectId(rememberedProjectId);
  const enabledProjectIdSet = new Set(enabledProjectIds.map((projectId) => projectId.trim()).filter(Boolean));

  if (allowAll && normalizedUrlProjectId === ALL_PROJECTS_SCOPE) {
    return ALL_PROJECTS_SCOPE;
  }
  if (normalizedUrlProjectId && enabledProjectIdSet.has(normalizedUrlProjectId)) {
    return normalizedUrlProjectId;
  }
  if (normalizedRememberedProjectId && enabledProjectIdSet.has(normalizedRememberedProjectId)) {
    return normalizedRememberedProjectId;
  }
  return enabledProjectIds.find((projectId) => projectId.trim())?.trim() || "";
}

/**
 * Keeps the shared delivery-project scope in the URL while remembering only a concrete project choice.
 */
export function useProjectScope(
  projects: readonly ProjectScopeProject[],
  options: UseProjectScopeOptions = {}
): ProjectScope {
  const allowAll = options.allowAll ?? false;
  const [searchParams, setSearchParams] = useSearchParams();
  const enabledProjectIds = useMemo(
    () => projects.filter((project) => project.enabled).map((project) => project.projectId),
    [projects]
  );
  const rawProjectId = searchParams.get(PROJECT_SCOPE_PARAM);
  const rememberedProjectId = readRememberedProjectId();
  const projectId = resolveProjectScope(rawProjectId, rememberedProjectId, enabledProjectIds, allowAll);

  const replaceProjectId = useCallback((nextProjectId: string, replace: boolean) => {
    const nextSearchParams = new URLSearchParams(searchParams);
    if (nextProjectId) {
      nextSearchParams.set(PROJECT_SCOPE_PARAM, nextProjectId);
    } else {
      nextSearchParams.delete(PROJECT_SCOPE_PARAM);
    }
    setSearchParams(nextSearchParams, { replace });
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    if (rawProjectId !== projectId) {
      replaceProjectId(projectId, true);
    }
    if (projectId && projectId !== ALL_PROJECTS_SCOPE) {
      persistProjectId(projectId);
    } else {
      clearRememberedProjectId();
    }
  }, [projectId, rawProjectId, replaceProjectId]);

  const setProjectId = useCallback((nextProjectId: string) => {
    const resolvedProjectId = resolveProjectScope(nextProjectId, null, enabledProjectIds, allowAll);
    replaceProjectId(resolvedProjectId, false);
  }, [allowAll, enabledProjectIds, replaceProjectId]);

  return {
    projectId,
    isAllProjects: projectId === ALL_PROJECTS_SCOPE,
    setProjectId
  };
}

function normalizeProjectId(projectId: string | null): string {
  return projectId?.trim() || "";
}

function readRememberedProjectId(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.localStorage.getItem(LAST_PROJECT_STORAGE_KEY);
  } catch {
    return null;
  }
}

function persistProjectId(projectId: string): void {
  try {
    window.localStorage.setItem(LAST_PROJECT_STORAGE_KEY, projectId);
  } catch {
    // Storage can be unavailable in private or embedded browser contexts.
  }
}

function clearRememberedProjectId(): void {
  try {
    window.localStorage.removeItem(LAST_PROJECT_STORAGE_KEY);
  } catch {
    // Storage can be unavailable in private or embedded browser contexts.
  }
}
