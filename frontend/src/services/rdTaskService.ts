import { api } from "@/services/api";

/** RD 任务视图，对齐后端 RdTaskView。 */
export interface RdTask {
  taskId: string;
  taskType: string;
  ticketId: string;
  ticketTitle: string;
  priority: string;
  status: string;
  title: string;
  promptSnapshot: string;
  executionResultJson: string;
  pullRequestUrl: string;
  errorMessage: string;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
  paused: boolean;
}

/** 任务分页结果，对齐后端 RdTaskPageView。 */
export interface RdTaskPage {
  records: RdTask[];
  page: number;
  pageSize: number;
  total: number;
  pages: number;
}

/** 状态事件视图，对齐后端 RdTaskStatusEventView。 */
export interface RdTaskStatusEvent {
  id: string;
  taskId: string;
  status: string;
  title: string;
  message: string;
  enteredAtEpochMillis: number;
  durationMillis: number;
  trigger: string;
}

export interface RdTaskListQuery {
  status?: string;
  priority?: string;
  ticketId?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}

export interface CreateRdTaskPayload {
  ticketId?: string;
  ticketTitle?: string;
  title: string;
  priority?: string;
  promptSnapshot?: string;
}

export interface UpdateRdTaskPayload {
  title?: string;
  priority?: string;
  ticketTitle?: string;
}

export const getRdTasksPage = (query: RdTaskListQuery = {}): Promise<RdTaskPage> =>
  api.get<RdTaskPage, RdTaskPage>("/admin/rd-tasks", {
    params: {
      status: query.status || undefined,
      priority: query.priority || undefined,
      ticketId: query.ticketId || undefined,
      keyword: query.keyword || undefined,
      page: query.page ?? 1,
      pageSize: query.pageSize ?? 20
    }
  });

export const getRdTask = (taskId: string): Promise<RdTask> =>
  api.get<RdTask, RdTask>(`/admin/rd-tasks/${taskId}`);

export const createRdTask = (payload: CreateRdTaskPayload): Promise<RdTask> =>
  api.post<RdTask, RdTask>("/admin/rd-tasks", payload);

export const updateRdTask = (taskId: string, payload: UpdateRdTaskPayload): Promise<RdTask> =>
  api.put<RdTask, RdTask>(`/admin/rd-tasks/${taskId}`, payload);

export const pauseRdTask = (taskId: string, message?: string): Promise<RdTask> =>
  api.post<RdTask, RdTask>(`/admin/rd-tasks/${taskId}/pause`, message ? { message } : {});

export const resumeRdTask = (taskId: string, message?: string): Promise<RdTask> =>
  api.post<RdTask, RdTask>(`/admin/rd-tasks/${taskId}/resume`, message ? { message } : {});

export const deleteRdTask = (taskId: string): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(`/admin/rd-tasks/${taskId}`);

export const getRdTaskTimeline = (taskId: string): Promise<RdTaskStatusEvent[]> =>
  api.get<RdTaskStatusEvent[], RdTaskStatusEvent[]>(`/admin/rd-tasks/${taskId}/timeline`);

/** 状态 → 徽标颜色类（Tailwind），用于 shadcn Badge 的 variant=outline + className。 */
export const STATUS_BADGE_CLASS: Record<string, string> = {
  CREATED: "border-blue-200 bg-blue-50 text-blue-700",
  SEARCHING: "border-amber-200 bg-amber-50 text-amber-700",
  EXECUTING: "border-indigo-200 bg-indigo-50 text-indigo-700",
  COMMITTED: "border-emerald-200 bg-emerald-50 text-emerald-700",
  MERGED: "border-green-300 bg-green-100 text-green-800",
  REJECTED: "border-red-200 bg-red-50 text-red-700",
  PAUSED: "border-slate-200 bg-slate-100 text-slate-600",
  RESUMED: "border-cyan-200 bg-cyan-50 text-cyan-700",
  DELETED: "border-slate-200 bg-slate-100 text-slate-500"
};
