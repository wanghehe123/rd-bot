import { api } from "@/services/api";

export interface RdProject {
  projectId: string;
  projectKey: string;
  name: string;
  description: string;
  repositoryUrl: string;
  repoOwner: string;
  repoName: string;
  defaultBranch: string;
  knowledgeBaseId: string;
  enabled: boolean;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
}

export interface RdProjectPage {
  records: RdProject[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
}

export interface RdProjectListQuery {
  keyword?: string;
  enabled?: boolean;
  page?: number;
  pageSize?: number;
}

export interface RdProjectPayload {
  projectKey: string;
  name: string;
  description?: string;
  repositoryUrl: string;
  repoOwner?: string;
  repoName?: string;
  defaultBranch: string;
  knowledgeBaseId?: string;
  enabled: boolean;
}

export type AlertRecipientType = "CHAT_ID" | "OPEN_ID";
export type ProjectAlertEventType =
  | "TASK_COMPLETED" | "TASK_BLOCKED" | "TASK_FAILED"
  | "RETRY_EXHAUSTED" | "BUDGET_EXCEEDED" | "QA_FAILED";

export interface ProjectAlertConfig {
  projectId: string;
  enabled: boolean;
  recipients: Array<{ type: AlertRecipientType; value: string }>;
  eventTypes: ProjectAlertEventType[];
  budgetThresholdCny: number;
  failureThreshold: number;
}

export interface ProjectTokenBudget {
  projectId: string;
  defaultTokenBudget: number;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
}

export interface ProjectTaskTemplate {
  projectId: string;
  taskType: "BUG_FIX" | "REQUIREMENT";
  name: string;
  actualBehavior: string;
  expectedBehavior: string;
  reproductionSteps: string;
  affectedScope: string;
  acceptanceCriteria: string[];
  requirementBody: string;
  expectedResult: string;
}

export type ProjectQaMode = "AUTO" | "REQUIRED" | "DISABLED";

export interface ProjectQaProfile {
  scopeType: "PROJECT" | "TASK";
  scopeId: string;
  mode: ProjectQaMode;
  baseUrl: string;
  startCommand: string;
  healthPath: string;
  allowedHosts: string[];
  regressionCommands: string[];
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
}

export interface ProjectQaProfilePayload {
  mode: ProjectQaMode;
  baseUrl: string;
  startCommand: string;
  healthPath: string;
  allowedHosts: string[];
  regressionCommands: string[];
}

export const getProjectsPage = (query: RdProjectListQuery = {}): Promise<RdProjectPage> =>
  api.get<RdProjectPage, RdProjectPage>("/admin/projects", {
    params: {
      keyword: query.keyword || undefined,
      enabled: query.enabled,
      page: query.page ?? 1,
      pageSize: query.pageSize ?? 20
    }
  });

export const createProject = (payload: RdProjectPayload): Promise<RdProject> =>
  api.post<RdProject, RdProject>("/admin/projects", payload);

export const updateProject = (projectId: string, payload: RdProjectPayload): Promise<RdProject> =>
  api.put<RdProject, RdProject>(`/admin/projects/${projectId}`, payload);

export const deleteProject = (projectId: string): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(`/admin/projects/${projectId}`);

export const getProjectAlertConfig = (projectId: string): Promise<ProjectAlertConfig> =>
  api.get<ProjectAlertConfig, ProjectAlertConfig>(`/admin/projects/${projectId}/alert-config`);

export const updateProjectAlertConfig = (
  projectId: string,
  payload: Omit<ProjectAlertConfig, "projectId">
): Promise<ProjectAlertConfig> =>
  api.put<ProjectAlertConfig, ProjectAlertConfig>(`/admin/projects/${projectId}/alert-config`, payload);

export const getProjectTokenBudget = (projectId: string): Promise<ProjectTokenBudget> =>
  api.get<ProjectTokenBudget, ProjectTokenBudget>(`/admin/projects/${projectId}/token-budget`);

export const updateProjectTokenBudget = (
  projectId: string,
  defaultTokenBudget: number
): Promise<ProjectTokenBudget> =>
  api.put<ProjectTokenBudget, ProjectTokenBudget>(`/admin/projects/${projectId}/token-budget`, { defaultTokenBudget });

export const getProjectTaskTemplate = (
  projectId: string,
  taskType: "BUG_FIX" | "REQUIREMENT"
): Promise<ProjectTaskTemplate> =>
  api.get<ProjectTaskTemplate, ProjectTaskTemplate>(`/admin/projects/${projectId}/task-templates/${taskType}`);

export const updateProjectTaskTemplate = (
  projectId: string,
  taskType: "BUG_FIX" | "REQUIREMENT",
  payload: Omit<ProjectTaskTemplate, "projectId" | "taskType">
): Promise<ProjectTaskTemplate> =>
  api.put<ProjectTaskTemplate, ProjectTaskTemplate>(`/admin/projects/${projectId}/task-templates/${taskType}`, payload);

export const getProjectQaProfile = (projectId: string): Promise<ProjectQaProfile> =>
  api.get<ProjectQaProfile, ProjectQaProfile>(`/admin/projects/${projectId}/qa-profile`);

export const updateProjectQaProfile = (
  projectId: string,
  payload: ProjectQaProfilePayload
): Promise<ProjectQaProfile> =>
  api.put<ProjectQaProfile, ProjectQaProfile>(`/admin/projects/${projectId}/qa-profile`, payload);
