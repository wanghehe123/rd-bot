type PanelLoaders = Record<string, () => Promise<unknown>>;

export type TaskRequestToken = Readonly<{
  taskId: string;
  generation: number;
}>;

export type TaskRequestGuard = {
  beginTask: (taskId: string) => TaskRequestToken;
  capture: (expectedTaskId?: string) => TaskRequestToken;
  isCurrent: (token: TaskRequestToken) => boolean;
};

/** Invalidates all task-owned async work whenever the route switches to another task. */
export function createTaskRequestGuard(): TaskRequestGuard {
  let current: TaskRequestToken = { taskId: "", generation: 0 };
  return {
    beginTask(taskId) {
      current = { taskId, generation: current.generation + 1 };
      return current;
    },
    capture(expectedTaskId) {
      return expectedTaskId === undefined
        ? current
        : { taskId: expectedTaskId, generation: current.generation };
    },
    isCurrent(token) {
      return token.taskId === current.taskId && token.generation === current.generation;
    }
  };
}

type SettledPanelValues<TPanels extends PanelLoaders> = {
  [TKey in keyof TPanels]: Awaited<ReturnType<TPanels[TKey]>> | undefined;
};

export interface TaskDetailShellResult<TTask, TPanels extends PanelLoaders> {
  task: TTask;
  panels: SettledPanelValues<TPanels>;
  errors: Partial<Record<keyof TPanels, string>>;
}

/**
 * Loads the task shell as the only critical request, then settles every supporting panel independently.
 */
export async function loadTaskDetailShell<TTask, TPanels extends PanelLoaders>({
  loadTask,
  panels,
  onTask
}: {
  loadTask: () => Promise<TTask>;
  panels: TPanels;
  onTask?: (task: TTask) => void;
}): Promise<TaskDetailShellResult<TTask, TPanels>> {
  const task = await loadTask();
  onTask?.(task);

  const entries = Object.entries(panels) as Array<[keyof TPanels, TPanels[keyof TPanels]]>;
  const settled = await Promise.allSettled(entries.map(([, loader]) => loader()));
  const values = {} as SettledPanelValues<TPanels>;
  const errors: Partial<Record<keyof TPanels, string>> = {};

  settled.forEach((result, index) => {
    const key = entries[index][0];
    if (result.status === "fulfilled") {
      values[key] = result.value as SettledPanelValues<TPanels>[typeof key];
      return;
    }
    values[key] = undefined;
    errors[key] = errorMessage(result.reason);
  });

  return { task, panels: values, errors };
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  return String(error || "加载失败");
}
