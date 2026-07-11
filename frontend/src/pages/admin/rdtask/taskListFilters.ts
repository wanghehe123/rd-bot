export type TaskListUrlFilters = {
  projectId?: string;
  taskType?: string;
  status?: string;
  keyword?: string;
};

/** Reads only non-empty filters so a drill-down URL is replayable after navigation. */
export function taskListFiltersFromSearchParams(searchParams: URLSearchParams): TaskListUrlFilters {
  return {
    projectId: text(searchParams.get("projectId")),
    taskType: text(searchParams.get("taskType")),
    status: text(searchParams.get("status")),
    keyword: text(searchParams.get("keyword"))
  };
}

function text(value: string | null): string | undefined {
  const normalized = value?.trim();
  return normalized || undefined;
}
