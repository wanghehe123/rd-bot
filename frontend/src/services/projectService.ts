import { api } from "@/services/api";
import { asArray } from "@/services/jsonArray";

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
  buildCommands?: string[] | null;
  staticCommands?: string[] | null;
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
  buildCommands?: string[] | null;
  staticCommands?: string[] | null;
}

export type ProjectRuntimeRole =
  | "REQUIREMENT_REVIEWER"
  | "SOLUTION_ARCHITECT"
  | "CODING_AGENT"
  | "QA_AGENT";

export interface ProjectRuntimeProfile {
  projectId: string;
  role: ProjectRuntimeRole;
  agentType: "CLAUDE_CODE";
  image: string;
  dockerfileArtifactUri: string;
  dockerfileSha256: string;
  dockerfileName: string;
  validationStatus: "VERIFIED";
  validationSummary: string;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
}

export type AgentRuntimeType = "PI" | "CLAUDE_CODE" | "MODEL_ONLY";
export type AgentExecutionRole = ProjectRuntimeRole;
export type ModelProviderProtocol =
  | "ANTHROPIC_COMPATIBLE"
  | "ANTHROPIC_MESSAGES"
  | "OPENAI_CHAT_COMPLETIONS"
  | "OPENAI_COMPLETIONS"
  | "OPENAI_RESPONSES"
  | "GOOGLE_GENERATIVE_AI";

export interface ModelProviderProfile {
  providerId: string;
  displayName: string;
  protocol: ModelProviderProtocol;
  baseUrl: string;
  modelId: string;
  credentialEnvironmentVariable: string;
  authHeader?: boolean;
  enabled: boolean;
  version: number;
  credentialConfigured?: boolean;
  credentialUpdatedAt?: number | null;
}

export interface AgentExecutionProfile {
  profileId: string;
  projectId: string;
  role: AgentExecutionRole;
  name: string;
  runtimeType: AgentRuntimeType;
  providerProfileId: string;
  modelOverride: string;
  extensionSetId: string;
  extensionSetVersion: number;
  toolPolicyId: string;
  toolPolicyVersion: number;
  enabled: boolean;
  version: number;
}

export interface AgentExecutionProfilePayload {
  profileId: string;
  role: AgentExecutionRole;
  name: string;
  runtimeType: AgentRuntimeType;
  providerProfileId: string;
  modelOverride: string;
  extensionSetId: string;
  extensionSetVersion: number;
  toolPolicyId: string;
  toolPolicyVersion: number;
  enabled: boolean;
  version: number;
}

export interface AgentExecutionProfileSnapshot {
  snapshotId: string;
  stageRunId: string;
  taskId: string;
  role: string;
  attemptNo: number;
  runtimeType: AgentRuntimeType;
  snapshotJson: string;
  snapshotHash: string;
  resolvedAtEpochMillis: number;
}

export const getProject = (projectId: string): Promise<RdProject> =>
  api.get<RdProject, RdProject>(`/admin/projects/${projectId}`);

export type AgentStrategyImageMode = "LOCAL_DEFAULT" | "CUSTOM";

export interface AgentStrategyRoleSlot {
  role: AgentExecutionRole;
  runtimeType: AgentRuntimeType;
  providerProfileId: string;
  modelOverride: string;
  extensionSetId: string;
  extensionSetVersion: number;
  toolPolicyId: string;
  toolPolicyVersion: number;
  imageMode: AgentStrategyImageMode;
  image: string;
  dockerfileName: string;
  dockerfileSha256: string;
  dockerfileArtifactUri: string;
  dockerfileText: string;
}

export interface AgentStrategyProfile {
  strategyId: string;
  projectId: string;
  name: string;
  enabled: boolean;
  version: number;
  roles: AgentStrategyRoleSlot[];
}

export interface AgentStrategyConsole {
  projectId: string;
  defaultStrategyId: string;
  synthesizedFromLegacy: boolean;
  agentRuntimeEnabled: boolean;
  defaultPiImage: string;
  defaultPiQaImage: string;
  defaultClaudeImage: string;
  strategies: AgentStrategyProfile[];
}

export const getAgentStrategies = async (projectId: string): Promise<AgentStrategyConsole> => {
  const payload = await api.get<AgentStrategyConsole, AgentStrategyConsole>(
    `/admin/projects/${projectId}/agent-strategies`
  );
  return {
    projectId: payload?.projectId || projectId,
    defaultStrategyId: payload?.defaultStrategyId || "",
    synthesizedFromLegacy: Boolean(payload?.synthesizedFromLegacy),
    agentRuntimeEnabled: Boolean(payload?.agentRuntimeEnabled),
    defaultPiImage: payload?.defaultPiImage || "rd-bot/pi-agent:local",
    defaultPiQaImage: payload?.defaultPiQaImage || "rd-bot/pi-agent-qa:local",
    defaultClaudeImage: payload?.defaultClaudeImage || "rd-bot/claude-code:local",
    strategies: asArray(payload?.strategies)
  };
};

export const getAgentStrategy = (
  projectId: string,
  strategyId: string
): Promise<AgentStrategyProfile> =>
  api.get<AgentStrategyProfile, AgentStrategyProfile>(
    `/admin/projects/${projectId}/agent-strategies/${encodeURIComponent(strategyId)}`
  );

export const createAgentStrategy = (
  projectId: string,
  payload: AgentStrategyProfile,
  mutationToken: string
): Promise<AgentStrategyProfile> =>
  api.post<AgentStrategyProfile, AgentStrategyProfile>(
    `/admin/projects/${projectId}/agent-strategies`,
    payload,
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const updateAgentStrategy = (
  projectId: string,
  strategyId: string,
  payload: AgentStrategyProfile,
  mutationToken: string
): Promise<AgentStrategyProfile> =>
  api.put<AgentStrategyProfile, AgentStrategyProfile>(
    `/admin/projects/${projectId}/agent-strategies/${encodeURIComponent(strategyId)}`,
    payload,
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const bindProjectAgentStrategy = (
  projectId: string,
  strategyId: string,
  mutationToken: string
): Promise<{ projectId: string; strategyId: string }> =>
  api.put<{ projectId: string; strategyId: string }, { projectId: string; strategyId: string }>(
    `/admin/projects/${projectId}/agent-strategies/${encodeURIComponent(strategyId)}/default`,
    {},
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const uploadAgentStrategyRoleImage = (
  projectId: string,
  strategyId: string,
  role: AgentExecutionRole,
  dockerfile: File,
  mutationToken: string
): Promise<AgentStrategyProfile> => {
  const formData = new FormData();
  formData.append("dockerfile", dockerfile);
  return api.put<AgentStrategyProfile, AgentStrategyProfile>(
    `/admin/projects/${projectId}/agent-strategies/${encodeURIComponent(strategyId)}/roles/${role}/image`,
    formData,
    {
      headers: {
        "Content-Type": "multipart/form-data",
        "X-RD-Agent-Runtime-Token": mutationToken
      },
      timeout: 11 * 60 * 1000
    }
  );
};

export const clearAgentStrategyRoleImage = (
  projectId: string,
  strategyId: string,
  role: AgentExecutionRole,
  mutationToken: string
): Promise<AgentStrategyProfile> =>
  api.delete<AgentStrategyProfile, AgentStrategyProfile>(
    `/admin/projects/${projectId}/agent-strategies/${encodeURIComponent(strategyId)}/roles/${role}/image`,
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

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

export const getTaskQaProfile = (taskId: string): Promise<ProjectQaProfile> =>
  api.get<ProjectQaProfile, ProjectQaProfile>(`/admin/rd-tasks/${taskId}/qa-profile`);

export const updateTaskQaProfile = (
  taskId: string,
  payload: ProjectQaProfilePayload
): Promise<ProjectQaProfile> =>
  api.put<ProjectQaProfile, ProjectQaProfile>(`/admin/rd-tasks/${taskId}/qa-profile`, payload);

export const getProjectRuntimeProfiles = (projectId: string): Promise<ProjectRuntimeProfile[]> =>
  api.get<ProjectRuntimeProfile[], ProjectRuntimeProfile[]>(`/admin/projects/${projectId}/runtime-profiles`);

export const uploadProjectRuntimeProfile = (
  projectId: string,
  role: ProjectRuntimeRole,
  dockerfile: File,
  mutationToken: string
): Promise<ProjectRuntimeProfile> => {
  const formData = new FormData();
  formData.append("agentType", "CLAUDE_CODE");
  formData.append("dockerfile", dockerfile);
  return api.put<ProjectRuntimeProfile, ProjectRuntimeProfile>(
    `/admin/projects/${projectId}/runtime-profiles/${role}`,
    formData,
    {
      headers: {
        "Content-Type": "multipart/form-data",
        "X-RD-Runtime-Profile-Token": mutationToken
      },
      timeout: 11 * 60 * 1000
    }
  );
};

export const deleteProjectRuntimeProfile = (
  projectId: string,
  role: ProjectRuntimeRole,
  mutationToken: string
): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(
    `/admin/projects/${projectId}/runtime-profiles/${role}`,
    { headers: { "X-RD-Runtime-Profile-Token": mutationToken } }
  );

export const getModelProviderProfiles = async (): Promise<ModelProviderProfile[]> =>
  asArray(await api.get<unknown, unknown>("/admin/model-provider-profiles"));

export const getAgentExecutionProfiles = async (projectId: string): Promise<AgentExecutionProfile[]> =>
  asArray(await api.get<unknown, unknown>(
    `/admin/projects/${projectId}/agent-execution-profiles`
  ));

export const createAgentExecutionProfile = (
  projectId: string,
  payload: AgentExecutionProfilePayload,
  mutationToken: string
): Promise<AgentExecutionProfile> =>
  api.post<AgentExecutionProfile, AgentExecutionProfile>(
    `/admin/projects/${projectId}/agent-execution-profiles`,
    payload,
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const updateAgentExecutionProfile = (
  projectId: string,
  profileId: string,
  payload: AgentExecutionProfilePayload,
  mutationToken: string
): Promise<AgentExecutionProfile> =>
  api.put<AgentExecutionProfile, AgentExecutionProfile>(
    `/admin/projects/${projectId}/agent-execution-profiles/${encodeURIComponent(profileId)}`,
    payload,
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const bindProjectAgentExecutionProfile = (
  projectId: string,
  role: AgentExecutionRole,
  profileId: string,
  mutationToken: string
): Promise<{ projectId: string; role: string; profileId: string }> =>
  api.put<{ projectId: string; role: string; profileId: string }, { projectId: string; role: string; profileId: string }>(
    `/admin/projects/${projectId}/agent-execution-profiles/${role}/default`,
    { profileId },
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const setTaskAgentExecutionProfileOverride = (
  taskId: string,
  role: AgentExecutionRole,
  profileId: string,
  mutationToken: string
): Promise<{ taskId: string; projectId: string; role: string; profileId: string }> =>
  api.put<{ taskId: string; projectId: string; role: string; profileId: string }, { taskId: string; projectId: string; role: string; profileId: string }>(
    `/admin/rd-tasks/${encodeURIComponent(taskId)}/agent-execution-profile-overrides/${role}`,
    { profileId },
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );

export const getTaskAgentExecutionProfileOverride = (
  taskId: string,
  role: AgentExecutionRole
): Promise<AgentExecutionProfile> =>
  api.get<AgentExecutionProfile, AgentExecutionProfile>(
    `/admin/rd-tasks/${encodeURIComponent(taskId)}/agent-execution-profile-overrides/${role}`
  );

export const clearTaskAgentExecutionProfileOverride = (
  taskId: string,
  role: AgentExecutionRole,
  mutationToken: string
): Promise<{ taskId: string; role: string; deleted: boolean }> =>
  api.delete<{ taskId: string; role: string; deleted: boolean }, { taskId: string; role: string; deleted: boolean }>(
    `/admin/rd-tasks/${encodeURIComponent(taskId)}/agent-execution-profile-overrides/${role}`,
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );
