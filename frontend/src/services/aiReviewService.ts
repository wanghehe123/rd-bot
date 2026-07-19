import { api } from "@/services/api";

export type AiReviewRun = {
  runId: string;
  taskId: string;
  attemptNo: number;
  parentRunId: string;
  status: string;
  modelName: string;
  packageHash: string;
  decision: string;
  score: number;
  retryFromRole: string;
  summary: string;
  errorCategory: string;
  errorMessage: string;
  version: number;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
};

export type AiReviewEvent = {
  eventId: string;
  runId: string;
  fromStatus: string;
  toStatus: string;
  trigger: string;
  message: string;
  errorCategory: string;
  occurredAtEpochMillis: number;
};

export type AiReviewArtifact = {
  artifactId: string;
  runId: string;
  artifactType: string;
  artifactUri: string;
  contentPreview: string;
  contentHash: string;
  metadataJson: string;
  redacted: boolean;
  createdAtEpochMillis: number;
};

export type AiReviewRetry = {
  checkpointId: string;
  taskId: string;
  attemptNo: number;
  status: string;
  failurePhase: string;
  retryFromRole: string;
  failedAiReviewRunId: string;
  reason: string;
};

export const getAiReviews = (taskId: string): Promise<AiReviewRun[]> =>
  api.get<AiReviewRun[], AiReviewRun[]>(`/admin/rd-tasks/${taskId}/ai-reviews`);

export const startAiReview = (taskId: string): Promise<AiReviewRun> =>
  api.post<AiReviewRun, AiReviewRun>(`/admin/rd-tasks/${taskId}/ai-reviews`, {});

export const getAiReview = (runId: string): Promise<AiReviewRun> =>
  api.get<AiReviewRun, AiReviewRun>(`/admin/ai-reviews/${runId}`);

export const getAiReviewTimeline = (runId: string): Promise<AiReviewEvent[]> =>
  api.get<AiReviewEvent[], AiReviewEvent[]>(`/admin/ai-reviews/${runId}/timeline`);

export const getAiReviewArtifacts = (runId: string): Promise<AiReviewArtifact[]> =>
  api.get<AiReviewArtifact[], AiReviewArtifact[]>(`/admin/ai-reviews/${runId}/artifacts`);

export const retryAiReview = (runId: string): Promise<AiReviewRetry> =>
  api.post<AiReviewRetry, AiReviewRetry>(`/admin/ai-reviews/${runId}/retry`, {});

export const cancelAiReview = (runId: string): Promise<AiReviewRun> =>
  api.post<AiReviewRun, AiReviewRun>(`/admin/ai-reviews/${runId}/cancel`, {});

export const isAiReviewActive = (status: string) =>
  ["CREATED", "PACKAGING", "REVIEWING", "VALIDATING"].includes(status);

export const aiReviewStatusLabel = (status: string) => ({
  CREATED: "已创建", PACKAGING: "打包上下文", REVIEWING: "模型复核中", VALIDATING: "校验结果",
  SUCCEEDED_OK: "复核通过", SUCCEEDED_NOT_OK: "复核未通过", SUCCEEDED_NEEDS_HUMAN: "需人工判断",
  FAILED_RETRYABLE: "可重试失败", CANCELLED: "已取消"
}[status] || status);
