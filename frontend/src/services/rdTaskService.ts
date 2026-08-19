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
  projectId: string;
  projectKey: string;
  projectName: string;
  repositoryUrl: string;
  repoOwner: string;
  repoName: string;
  baseBranch: string;
  workBranch: string;
  expectedResult: string;
  acceptanceCriteriaJson: string;
  executionEvidence: RdTaskExecutionEvidence;
  tokenBudgetOverride: number;
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

export interface RdTaskExecutionOverview {
  taskId: string;
  taskType: string;
  status: string;
  title: string;
  elapsedMillis: number;
  progressCompleted: number;
  progressTotal: number;
  currentRole: string;
  currentStageStatus: string;
  budget: RdTaskExecutionBudget;
  tokenBudget: RdTaskTokenBudget;
  stageRuns: RdTaskStageRun[];
  runningExecutions: RdTaskRunningExecution[];
}

export interface RdTaskExecutionBudget {
  contextUsedChars: number;
  contextMaxChars: number;
  contextUsageRatio: number;
  estimatedSpendCny: number;
  budgetAlertCny: number;
  costAvailable: boolean;
}

export interface RdTaskTokenBudget {
  effectiveTokenBudget: number;
  initialTokens: number;
  retryReserveTokens: number;
  estimatedTotalTokens: number;
  confidence: string;
  basis: string;
  historicalSamples: Array<{ scope: string; actualTotalTokens: number; completedAtEpochMillis?: number }>;
  runningTokens: number;
  actualAccumulatedTokens: number;
  finalActualTokens: number;
  estimateAvailable: boolean;
  actualAvailable: boolean;
  overBudget: boolean;
}

export interface RdTaskStageRun {
  stageRunId: string;
  taskId: string;
  role: string;
  status: string;
  attemptNo: number;
  idempotencyKey: string;
  contextPackageId: string;
  promptArtifactId: string;
  resultArtifactId: string;
  providerName: string;
  providerAttemptsJson: string;
  providerAttempts: Record<string, unknown>[];
  reviewResultJson: string;
  resultAvailable: boolean;
  resultSummary: string;
  resultPreview: string;
  errorCategory: string;
  errorMessage: string;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
  startedAtEpochMillis: number;
  finishedAtEpochMillis: number;
  elapsedMillis: number;
  running: boolean;
  runtimeType?: string;
  agentStateAvailable?: boolean;
  agentStateSequence?: number;
  agentStateSchemaVersion?: number | string;
  agentStateTodoInProgressCount?: number;
  agentStateTodoBlockedCount?: number;
  agentStateTodoPendingCount?: number;
  agentStateTodoDoneCount?: number;
  agentStatePreviewTruncated?: boolean;
  agentStateContentHash?: string;
  agentLastInjectionSequence?: number;
  agentLastInjectedStateSequence?: number;
  agentLastInjectedBlockHash?: string;
  agentLastInjectedPromptHash?: string;
}

export interface RdTaskRunningExecution {
  repairRecordId: string;
  taskId: string;
  executionTaskId: string;
  stageRunId: string;
  ticketId: string;
  provider: string;
  containerName: string;
  startedAtEpochMillis: number;
  lastHeartbeatEpochMillis: number;
  elapsedMillis: number;
  outputDirectory: string;
  tokenUsage: {
    inputTokens: number;
    outputTokens: number;
    cacheCreationInputTokens: number;
    cacheReadInputTokens: number;
    totalTokens: number;
    estimatedSpendCny: number;
    available: boolean;
    finalized: boolean;
  };
}

export interface RdTaskExecutionTraceEntry {
  sequence: number;
  kind: "SESSION_STARTED" | "ASSISTANT_TEXT" | "TOOL_STARTED" | "TOOL_COMPLETED" | "RESULT" | "RUNTIME_ERROR";
  label: string;
  detail: string;
  error: boolean;
}

export interface RdTaskExecutionTrace {
  version: number;
  source: "LIVE" | "ARCHIVED";
  available: boolean;
  finalized: boolean;
  truncated: boolean;
  hasMore: boolean;
  nextSequence: number;
  entries: RdTaskExecutionTraceEntry[];
}

/** 已归档的角色实际 Prompt 与其绑定的 RAG 上下文。 */
export interface RdTaskRolePromptResponse {
  taskId: string;
  taskType: string;
  stagePrompts: RdTaskRolePromptStage[];
}

export interface RdTaskRolePromptStage {
  stageRunId: string;
  taskId: string;
  role: string;
  status: string;
  attemptNo: number;
  providerName: string;
  runtimeType?: string;
  prompt: RdTaskRolePrompt;
  context: RdTaskRolePromptContext;
  effectiveContext?: RdTaskEffectiveContext;
  latestState?: RdTaskLatestAgentState;
}

export interface RdTaskEffectiveContext {
  available: boolean;
  unavailableReason: string;
  source: "LIVE_PROJECTION" | "ARCHIVED_ARTIFACT" | "";
  finalized: boolean;
  stale: boolean;
  staleReason: string;
  lastProjectionAtEpochMillis: number;
  staleAfterMillis: number;
  protocol: string;
  compositionOrder: string[];
  injectionSequence: number;
  promptArtifactId: string;
  promptContentHash: string;
  stateArtifactId: string;
  stateSequence: number;
  stateContentHash: string;
  injectedBlockHash: string;
  contentPreview: string;
  contentHash: string;
  contentLength: number;
  previewLength: number;
  truncated: boolean;
  generatedAtEpochMillis: number;
}

export interface RdTaskLatestAgentState {
  available: boolean;
  unavailableReason: string;
  source: "LIVE_PROJECTION" | "ARCHIVED_ARTIFACT" | "";
  finalized: boolean;
  stale: boolean;
  staleReason: string;
  lastProjectionAtEpochMillis: number;
  staleAfterMillis: number;
  artifactId: string;
  protocol: string;
  sequence: number;
  generatedAtEpochMillis: number;
  currentGoal: string;
  phase: string;
  taskStartedAt: string;
  stageStartedAt: string;
  resultStatus: string;
  blocker: string;
  budget: RdTaskAgentStateBudget;
  todos: RdTaskAgentTodo[];
  recentErrors: RdTaskAgentRecentError[];
  contentHash: string;
  previewTruncated: boolean;
}

export interface RdTaskAgentStateBudget {
  tokenUsed?: number;
  tokenMax?: number;
  tokenUsageRatio?: number;
  contextUsedChars?: number;
  contextMaxChars?: number;
  contextUsageRatio?: number;
  deadlineEpochMillis?: number;
  available?: boolean;
}

export interface RdTaskAgentTodo {
  id?: string;
  title: string;
  status: "IN_PROGRESS" | "BLOCKED" | "PENDING" | "DONE" | "CANCELLED" | string;
  source?: string;
  required?: boolean;
  blockerReason?: string;
  acceptanceReferences?: string[];
  evidenceCount?: number;
}

export interface RdTaskAgentRecentError {
  summary: string;
  toolName?: string;
  timestamp?: string | number;
}

export interface RdTaskRolePrompt {
  available: boolean;
  unavailableReason: string;
  artifactId: string;
  summary: string;
  contentPreview: string;
  contentHash: string;
  contentLength: number;
  previewLength: number;
  truncated: boolean;
  createdAtEpochMillis: number;
}

export interface RdTaskRolePromptContext {
  available: boolean;
  unavailableReason: string;
  packageId: string;
  packageVersion: number;
  retrievalRunId: string;
  maxChars: number;
  usedChars: number;
  acceptanceCriteria: string[];
  riskHints: string[];
  evidence: RdTaskRolePromptEvidence[];
  omittedEvidenceIds: string[];
  createdAtEpochMillis: number;
}

export interface RdTaskRolePromptEvidence {
  evidenceId: string;
  sourceType: string;
  sourceUri: string;
  title: string;
  contentHash: string;
  summary: string;
  collectedAtEpochMillis: number;
  selectionReason: string;
  relevanceScore: number;
  requiredEvidenceType: string;
  sharedRoot: boolean;
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

export interface RdTaskQaEvidence {
  artifactId: string;
  stageRunId: string;
  type: string;
  name: string;
  summary: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  createdAtEpochMillis: number;
  previewable: boolean;
  contentUrl: string;
}

export type HostVerificationStatus =
  | "CREATED"
  | "PREPARING"
  | "BUILDING"
  | "STATIC_CHECKING"
  | "SUCCEEDED"
  | "FAILED_RETRYABLE"
  | "FAILED_NEEDS_HUMAN"
  | "SKIPPED_DOCS_ONLY"
  | "CANCELLED";

export type HostVerificationStepName = "BUILD" | "STATIC";

export type HostVerificationStepStatus =
  | "PENDING"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "SKIPPED";

export type HostVerificationFailureCategory =
  | ""
  | "NONE"
  | "PRODUCT_DEFECT"
  | "ENVIRONMENT"
  | "AUTHENTICATION"
  | "QA_INFRASTRUCTURE"
  | "REQUIREMENT_AMBIGUITY"
  | "FLAKY";

export interface HostVerificationArtifact {
  artifactId: string;
  type: string; // VERIFY_BUILD_LOG | VERIFY_STATIC_LOG | …
  name: string;
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  previewable: boolean;
  contentUrl: string;
}

export interface HostVerificationStep {
  step: HostVerificationStepName;
  status: HostVerificationStepStatus;
  commands: string[];
  exitCode: number | null;
  durationMillis: number;
  logArtifactId: string;
  errorMessage: string;
}

export interface HostVerificationRun {
  runId: string;
  codingStageRunId: string;
  parentRunId: string;
  attemptNo: number;
  status: HostVerificationStatus;
  docsOnly: boolean;
  failureCategory: HostVerificationFailureCategory;
  errorMessage: string;
  remediationCount: number;
  createdAtEpochMillis: number;
  startedAtEpochMillis: number;
  finishedAtEpochMillis: number;
  steps: HostVerificationStep[];
  artifacts: HostVerificationArtifact[];
}

export interface HostVerificationList {
  taskId: string;
  cheapRemediationsUsed?: number;
  runs: HostVerificationRun[];
}

export interface RdTaskListQuery {
  taskType?: string;
  status?: string;
  priority?: string;
  projectId?: string;
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
  projectId?: string;
  autoExecute?: boolean;
}

export interface RequirementMaterialPayload {
  materialType?: string;
  sourceType: "MANUAL_TEXT" | "FEISHU_DOC" | "LOCAL_UPLOAD";
  title?: string;
  sourceUri?: string;
  content?: string;
  mimeType?: string;
  revisionId?: string;
  recoveryStageRunId?: string;
}

export interface CreateRequirementTaskPayload {
  title: string;
  priority?: string;
  projectId?: string;
  repositoryUrl?: string;
  repoOwner?: string;
  repoName?: string;
  baseBranch: string;
  expectedResult: string;
  acceptanceCriteria?: string[];
  materials: RequirementMaterialPayload[];
  autoExecute?: boolean;
  tokenBudgetOverride?: number;
}

export interface UpdateRdTaskPayload {
  title?: string;
  priority?: string;
  ticketTitle?: string;
}

export interface TaskDraftResult {
  available: boolean;
  reason: string;
  actualBehavior: string;
  expectedBehavior: string;
  reproductionSteps: string;
  affectedScope: string;
  requirementBody: string;
  expectedResult: string;
  acceptanceCriteria: string[];
  missingFields: string[];
  evidence: string[];
  confidence: number;
  aiGenerated: boolean;
}

export const getRdTasksPage = (query: RdTaskListQuery = {}): Promise<RdTaskPage> =>
  api.get<RdTaskPage, RdTaskPage>("/admin/rd-tasks", {
    params: {
      taskType: query.taskType || undefined,
      status: query.status || undefined,
      priority: query.priority || undefined,
      projectId: query.projectId || undefined,
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

export const approveRdTask = (taskId: string, message?: string): Promise<RdTask> =>
  api.post<RdTask, RdTask>(`/admin/rd-tasks/${taskId}/approve`, message ? { message } : {});

export const deleteRdTask = (taskId: string): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(`/admin/rd-tasks/${taskId}`);

export const getRdTaskTimeline = (taskId: string): Promise<RdTaskStatusEvent[]> =>
  api.get<RdTaskStatusEvent[], RdTaskStatusEvent[]>(`/admin/rd-tasks/${taskId}/timeline`);

export const getRdTaskExecutionOverview = (taskId: string): Promise<RdTaskExecutionOverview> =>
  api.get<RdTaskExecutionOverview, RdTaskExecutionOverview>(`/admin/rd-tasks/${taskId}/execution-overview`);

export const getRdTaskExecutionTrace = (
  taskId: string,
  stageRunId: string,
  query: { after?: number; limit?: number } = {}
): Promise<RdTaskExecutionTrace> => {
  const params = new URLSearchParams();
  if (query.after && query.after > 0) params.set("after", String(query.after));
  if (query.limit && query.limit > 0) params.set("limit", String(query.limit));
  const suffix = params.toString();
  return api.get<RdTaskExecutionTrace, RdTaskExecutionTrace>(
    `/admin/rd-tasks/${taskId}/stage-runs/${stageRunId}/execution-trace${suffix ? `?${suffix}` : ""}`
  );
};

export const getRdTaskRolePrompts = (taskId: string): Promise<RdTaskRolePromptResponse> =>
  api.get<RdTaskRolePromptResponse, RdTaskRolePromptResponse>(`/admin/rd-tasks/${taskId}/role-prompts`);

export const getRdTaskMaterials = (taskId: string): Promise<TaskMaterial[]> =>
  api.get<TaskMaterial[], TaskMaterial[]>(`/admin/rd-tasks/${taskId}/materials`);

export const getRdTaskQaEvidence = (taskId: string): Promise<RdTaskQaEvidence[]> =>
  api.get<RdTaskQaEvidence[], RdTaskQaEvidence[]>(`/admin/rd-tasks/${taskId}/qa-evidence`);

export const getRdTaskHostVerifications = (taskId: string): Promise<HostVerificationList> =>
  api.get<HostVerificationList, HostVerificationList>(`/admin/rd-tasks/${taskId}/host-verifications`);

export const getRdTaskHostVerification = (taskId: string, runId: string): Promise<HostVerificationRun> =>
  api.get<HostVerificationRun, HostVerificationRun>(`/admin/rd-tasks/${taskId}/host-verifications/${runId}`);

export const hostVerificationContentUrl = (taskId: string, runId: string, artifactId: string) =>
  `/admin/rd-tasks/${taskId}/host-verifications/${runId}/evidence/${artifactId}/content`;

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
  options: { title?: string; materialType?: string; recoveryStageRunId?: string } = {}
): Promise<TaskMaterial> => {
  const data = new FormData();
  data.append("file", file);
  if (options.title) {
    data.append("title", options.title);
  }
  if (options.materialType) {
    data.append("materialType", options.materialType);
  }
  if (options.recoveryStageRunId) {
    data.append("recoveryStageRunId", options.recoveryStageRunId);
  }
  return api.post<TaskMaterial, TaskMaterial>(`/admin/rd-tasks/${taskId}/materials/upload`, data);
};

export const getTaskMaterialPreview = (
  taskId: string,
  materialId: string
): Promise<TaskMaterialPreview> =>
  api.get<TaskMaterialPreview, TaskMaterialPreview>(`/admin/rd-tasks/${taskId}/materials/${materialId}/preview`);

export const taskMaterialContentUrl = (taskId: string, materialId: string) =>
  `/admin/rd-tasks/${taskId}/materials/${materialId}/content`;

export const completeTaskDraft = (payload: {
  taskType: "BUG_FIX" | "REQUIREMENT";
  projectId?: string;
  currentValues: Record<string, string>;
  materialSummaries?: string[];
}): Promise<TaskDraftResult> =>
  api.post<TaskDraftResult, TaskDraftResult>("/admin/rd-task-drafts/complete", payload);

/** 状态 → 徽标颜色类（Tailwind），用于 shadcn Badge 的 variant=outline + className。 */
export const STATUS_BADGE_CLASS: Record<string, string> = {
  CREATED: "border-blue-200 bg-blue-50 text-blue-700",
  MATERIAL_COLLECTING: "border-sky-200 bg-sky-50 text-sky-700",
  MATERIAL_READY: "border-teal-200 bg-teal-50 text-teal-700",
  CONTEXT_BUILDING: "border-cyan-200 bg-cyan-50 text-cyan-700",
  CONTEXT_READY: "border-teal-200 bg-teal-50 text-teal-700",
  PLAN_GENERATING: "border-blue-200 bg-blue-50 text-blue-700",
  PLAN_GENERATED: "border-cyan-200 bg-cyan-50 text-cyan-700",
  WAITING_POLICY: "border-amber-200 bg-amber-50 text-amber-700",
  WAITING_APPROVAL: "border-orange-200 bg-orange-50 text-orange-700",
  SEARCHING: "border-amber-200 bg-amber-50 text-amber-700",
  EXECUTING: "border-teal-200 bg-teal-50 text-teal-700",
  VALIDATING: "border-sky-200 bg-sky-50 text-sky-700",
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
