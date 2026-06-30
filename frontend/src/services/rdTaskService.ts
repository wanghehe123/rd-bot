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
  sourceType: string;
  sourceId: string;
  sourceUrl: string;
  repositoryUrl: string;
  repoOwner: string;
  repoName: string;
  baseBranch: string;
  workBranch: string;
  expectedResult: string;
  acceptanceCriteriaJson: string;
  executionEvidence: RdTaskExecutionEvidence;
}

export interface RdTaskExecutionEvidence {
  summary: string;
  prBody: string;
  changedFiles: string[];
  testStatus: string;
  riskLevel: string;
  testCommands: string[];
  pullRequestUrl: string;
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

export interface TaskMaterial {
  materialId: string;
  taskId: string;
  materialType: string;
  sourceType: string;
  title: string;
  sourceUri: string;
  mimeType: string;
  contentHash: string;
  contentPreview: string;
  artifactUri: string;
  knowledgeDocumentId: string;
  revisionId: string;
  metadataJson: string;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
}

export interface TaskMaterialPreview {
  materialId: string;
  taskId: string;
  title: string;
  sourceType: string;
  sourceUri: string;
  mimeType: string;
  contentHash: string;
  contentPreview: string;
}

export interface RdTaskListQuery {
  taskType?: string;
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

export interface RequirementMaterialPayload {
  materialType?: string;
  sourceType: "MANUAL_TEXT" | "FEISHU_DOC" | "LOCAL_UPLOAD";
  title?: string;
  sourceUri?: string;
  content?: string;
  mimeType?: string;
  revisionId?: string;
}

export interface CreateRequirementTaskPayload {
  title: string;
  priority?: string;
  repositoryUrl?: string;
  repoOwner?: string;
  repoName?: string;
  baseBranch: string;
  expectedResult: string;
  acceptanceCriteria?: string[];
  materials: RequirementMaterialPayload[];
  autoExecute?: boolean;
}

export interface UpdateRdTaskPayload {
  title?: string;
  priority?: string;
  ticketTitle?: string;
}

export const getRdTasksPage = (query: RdTaskListQuery = {}): Promise<RdTaskPage> =>
  api.get<RdTaskPage, RdTaskPage>("/admin/rd-tasks", {
    params: {
      taskType: query.taskType || undefined,
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

export const createRequirementTask = (payload: CreateRequirementTaskPayload): Promise<RdTask> =>
  api.post<RdTask, RdTask>("/admin/rd-tasks/requirements", payload);

export const updateRdTask = (taskId: string, payload: UpdateRdTaskPayload): Promise<RdTask> =>
  api.put<RdTask, RdTask>(`/admin/rd-tasks/${taskId}`, payload);

export const pauseRdTask = (taskId: string, message?: string): Promise<RdTask> =>
  api.post<RdTask, RdTask>(`/admin/rd-tasks/${taskId}/pause`, message ? { message } : {});

export const resumeRdTask = (taskId: string, message?: string): Promise<RdTask> =>
  api.post<RdTask, RdTask>(`/admin/rd-tasks/${taskId}/resume`, message ? { message } : {});

export const submitRdTask = (taskId: string): Promise<RdTask> =>
  api.post<RdTask, RdTask>(`/admin/rd-tasks/${taskId}/submit`, {});

export const deleteRdTask = (taskId: string): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(`/admin/rd-tasks/${taskId}`);

export const getRdTaskTimeline = (taskId: string): Promise<RdTaskStatusEvent[]> =>
  api.get<RdTaskStatusEvent[], RdTaskStatusEvent[]>(`/admin/rd-tasks/${taskId}/timeline`);

export const getRdTaskMaterials = (taskId: string): Promise<TaskMaterial[]> =>
  api.get<TaskMaterial[], TaskMaterial[]>(`/admin/rd-tasks/${taskId}/materials`);

export const addTextTaskMaterial = (
  taskId: string,
  payload: Omit<RequirementMaterialPayload, "sourceType" | "sourceUri"> & { content: string }
): Promise<TaskMaterial> =>
  api.post<TaskMaterial, TaskMaterial>(`/admin/rd-tasks/${taskId}/materials/text`, payload);

export const addFeishuTaskMaterial = (
  taskId: string,
  payload: Omit<RequirementMaterialPayload, "sourceType" | "content"> & { sourceUri: string }
): Promise<TaskMaterial> =>
  api.post<TaskMaterial, TaskMaterial>(`/admin/rd-tasks/${taskId}/materials/feishu`, payload);

export const uploadTaskMaterial = (
  taskId: string,
  file: File,
  options: { title?: string; materialType?: string } = {}
): Promise<TaskMaterial> => {
  const data = new FormData();
  data.append("file", file);
  if (options.title) {
    data.append("title", options.title);
  }
  if (options.materialType) {
    data.append("materialType", options.materialType);
  }
  return api.post<TaskMaterial, TaskMaterial>(`/admin/rd-tasks/${taskId}/materials/upload`, data);
};

export const getTaskMaterialPreview = (
  taskId: string,
  materialId: string
): Promise<TaskMaterialPreview> =>
  api.get<TaskMaterialPreview, TaskMaterialPreview>(`/admin/rd-tasks/${taskId}/materials/${materialId}/preview`);

/** 状态 → 徽标颜色类（Tailwind），用于 shadcn Badge 的 variant=outline + className。 */
export const STATUS_BADGE_CLASS: Record<string, string> = {
  CREATED: "border-blue-200 bg-blue-50 text-blue-700",
  MATERIAL_COLLECTING: "border-sky-200 bg-sky-50 text-sky-700",
  MATERIAL_READY: "border-teal-200 bg-teal-50 text-teal-700",
  CONTEXT_BUILDING: "border-cyan-200 bg-cyan-50 text-cyan-700",
  CONTEXT_READY: "border-teal-200 bg-teal-50 text-teal-700",
  PLAN_GENERATING: "border-violet-200 bg-violet-50 text-violet-700",
  PLAN_GENERATED: "border-purple-200 bg-purple-50 text-purple-700",
  WAITING_POLICY: "border-amber-200 bg-amber-50 text-amber-700",
  WAITING_APPROVAL: "border-orange-200 bg-orange-50 text-orange-700",
  SEARCHING: "border-amber-200 bg-amber-50 text-amber-700",
  EXECUTING: "border-indigo-200 bg-indigo-50 text-indigo-700",
  VALIDATING: "border-fuchsia-200 bg-fuchsia-50 text-fuchsia-700",
  PR_CREATING: "border-emerald-200 bg-emerald-50 text-emerald-700",
  COMMITTED: "border-emerald-200 bg-emerald-50 text-emerald-700",
  MERGED: "border-green-300 bg-green-100 text-green-800",
  REPORTING: "border-slate-200 bg-slate-50 text-slate-700",
  COMPLETED: "border-green-300 bg-green-100 text-green-800",
  REJECTED: "border-red-200 bg-red-50 text-red-700",
  FAILED_RETRYABLE: "border-rose-200 bg-rose-50 text-rose-700",
  FAILED_NEEDS_HUMAN: "border-orange-200 bg-orange-50 text-orange-700",
  CANCELLED: "border-slate-200 bg-slate-100 text-slate-600",
  DEAD_LETTERED: "border-red-300 bg-red-100 text-red-800",
  RECOVERING: "border-cyan-200 bg-cyan-50 text-cyan-700",
  PAUSED: "border-slate-200 bg-slate-100 text-slate-600",
  RESUMED: "border-cyan-200 bg-cyan-50 text-cyan-700",
  DELETED: "border-slate-200 bg-slate-100 text-slate-500"
};
