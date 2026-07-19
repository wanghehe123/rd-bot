import { api } from "@/services/api";

export type RetrievalRun = {
  runId: string;
  taskId: string;
  consumerType: string;
  role: string;
  stageRunId: string;
  attemptNo: number;
  parentRunId: string;
  status: string;
  knowledgeBaseIds: string[];
  queryHash: string;
  queryPreview: string;
  currentIteration: number;
  maxIterations: number;
  contextBudgetChars: number;
  candidateCount: number;
  selectedEvidenceCount: number;
  qualityDecision: string;
  stopReason: string;
  errorCategory: string;
  errorMessage: string;
  version: number;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
};

export type RetrievalRunEvent = {
  eventId: string;
  runId: string;
  fromStatus: string;
  toStatus: string;
  trigger: string;
  message: string;
  errorCategory: string;
  occurredAtEpochMillis: number;
};

export type RetrievalRunArtifact = {
  artifactId: string;
  runId: string;
  stepId: string;
  artifactType: string;
  artifactUri: string;
  contentPreview: string;
  contentHash: string;
  redacted: boolean;
  createdAtEpochMillis: number;
};

export const getRetrievalRuns = (taskId: string): Promise<RetrievalRun[]> =>
  api.get<RetrievalRun[], RetrievalRun[]>(`/admin/rd-tasks/${taskId}/retrieval-runs`);

export const getRetrievalRun = (runId: string): Promise<RetrievalRun> =>
  api.get<RetrievalRun, RetrievalRun>(`/admin/rag-retrieval-runs/${runId}`);

export const getRetrievalRunTimeline = (runId: string): Promise<RetrievalRunEvent[]> =>
  api.get<RetrievalRunEvent[], RetrievalRunEvent[]>(`/admin/rag-retrieval-runs/${runId}/timeline`);

export const getRetrievalRunArtifacts = (runId: string): Promise<RetrievalRunArtifact[]> =>
  api.get<RetrievalRunArtifact[], RetrievalRunArtifact[]>(`/admin/rag-retrieval-runs/${runId}/artifacts`);

export const retryRetrievalRun = (runId: string): Promise<RetrievalRun> =>
  api.post<RetrievalRun, RetrievalRun>(`/admin/rag-retrieval-runs/${runId}/retry`, {});

export const cancelRetrievalRun = (runId: string): Promise<RetrievalRun> =>
  api.post<RetrievalRun, RetrievalRun>(`/admin/rag-retrieval-runs/${runId}/cancel`, {});

export const isRetrievalRunActive = (status: string) =>
  ["CREATED", "PLANNING", "RETRIEVING", "EVALUATING", "PACKAGING", "RECOVERING"].includes(status);

export const retrievalRunStatusLabel = (status: string) => ({
  CREATED: "已创建", PLANNING: "规划中", RETRIEVING: "检索中", EVALUATING: "证据评估",
  PACKAGING: "打包中", RECOVERING: "恢复中", WAITING_INPUT: "等待材料", SUCCEEDED: "证据就绪",
  SUCCEEDED_DEGRADED: "降级完成", FAILED_RETRYABLE: "可重试失败", FAILED_NEEDS_HUMAN: "需人工处理",
  CANCELLED: "已取消", DEAD_LETTERED: "已死信"
}[status] || status);

export const retrievalRunStatusClass = (status: string) => ({
  CREATED: "border-slate-200 bg-slate-50 text-slate-600", PLANNING: "border-blue-200 bg-blue-50 text-blue-700",
  RETRIEVING: "border-cyan-200 bg-cyan-50 text-cyan-700", EVALUATING: "border-violet-200 bg-violet-50 text-violet-700",
  PACKAGING: "border-teal-200 bg-teal-50 text-teal-700", RECOVERING: "border-amber-200 bg-amber-50 text-amber-700",
  WAITING_INPUT: "border-orange-200 bg-orange-50 text-orange-700", SUCCEEDED: "border-green-200 bg-green-50 text-green-700",
  SUCCEEDED_DEGRADED: "border-amber-200 bg-amber-50 text-amber-700", FAILED_RETRYABLE: "border-rose-200 bg-rose-50 text-rose-700",
  FAILED_NEEDS_HUMAN: "border-red-200 bg-red-50 text-red-700", CANCELLED: "border-slate-200 bg-slate-100 text-slate-600",
  DEAD_LETTERED: "border-red-300 bg-red-50 text-red-800"
}[status] || "");
