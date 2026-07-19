import { api } from "@/services/api";

export type TaskRetryPoint = {
  taskId: string;
  failurePhase: string;
  retryFromRole: string;
  failedStageRunId: string;
  failedRetrievalRunId: string;
  failedAiReviewRunId: string;
  reason: string;
  sourceTaskVersion: number;
};

export type TaskRetryCheckpoint = TaskRetryPoint & {
  checkpointId: string;
  attemptNo: number;
  operatorNote: string;
  evidenceMaterialIds: string[];
  status: string;
  reason: string;
  errorMessage: string;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
};

export type TaskFailureIssue = {
  kind: string;
  severity: string;
  title: string;
  detail: string;
  sourceField: string;
};

export type TaskFailureDiagnostic = {
  category: string;
  title: string;
  summary: string;
  suggestedAction: string;
  requiresSupplement: boolean;
  issues: TaskFailureIssue[];
  risks: TaskFailureIssue[];
  acceptanceGaps: TaskFailureIssue[];
};

export type TaskFailureRecoverySnapshot = {
  taskId: string;
  sourceTaskStatus: string;
  retryPoint: TaskRetryPoint;
  failedStageStatus: string;
  failedAttemptNo: number;
  providerName: string;
  errorCategory: string;
  errorMessage: string;
  diagnostic: TaskFailureDiagnostic;
  rawResultArtifactId: string;
  rawResultContentHash: string;
  rawResultPreview: string;
  history: TaskRetryCheckpoint[];
};

export type TaskRetryCommand = {
  expectedFailedStageRunId?: string;
  expectedFailedRetrievalRunId?: string;
  expectedFailedAiReviewRunId?: string;
  expectedSourceTaskVersion?: number;
  operatorNote?: string;
  evidenceMaterialIds?: string[];
};

export const getTaskRetryPreview = (taskId: string): Promise<TaskRetryPoint> =>
  api.get<TaskRetryPoint, TaskRetryPoint>(`/admin/rd-tasks/${taskId}/retry-preview`);

export const getTaskRetryHistory = (taskId: string): Promise<TaskRetryCheckpoint[]> =>
  api.get<TaskRetryCheckpoint[], TaskRetryCheckpoint[]>(`/admin/rd-tasks/${taskId}/retry-history`);

export const getTaskFailureRecovery = (taskId: string): Promise<TaskFailureRecoverySnapshot> =>
  api.get<TaskFailureRecoverySnapshot, TaskFailureRecoverySnapshot>(`/admin/rd-tasks/${taskId}/failure-recovery`);

export const retryTaskFromFailure = (
  taskId: string,
  command: TaskRetryCommand = {}
): Promise<TaskRetryCheckpoint> =>
  api.post<TaskRetryCheckpoint, TaskRetryCheckpoint>(`/admin/rd-tasks/${taskId}/retry`, command);
