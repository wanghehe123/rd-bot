import { api } from "@/services/api";

export type EvaluationSource = "FIXTURE" | "RAG_HTTP" | "TASK_RUN";
export type EvaluationDatasetKind = "SCORER_SMOKE" | "QUALITY_BENCHMARK" | "TASK_RUN";
export type EvaluationJudgeProvider = "NONE" | "RAGAS" | "OPENAI_COMPATIBLE";
export type EvaluationRunStatus =
  | "CREATED"
  | "QUEUED"
  | "RECORDING"
  | "SCORING"
  | "REPORTING"
  | "DIFFING"
  | "CANCEL_REQUESTED"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";

export type EvaluationMetric = {
  name: string;
  label?: string;
  purpose?: string;
  calculation?: string;
  value?: number | null;
  threshold?: number | null;
  direction?: string;
  status?: string;
  sampleCount?: number;
  reason?: string;
};

export type EvaluationFailedMetric = {
  name: string;
  label?: string;
  reason?: string;
  nextAction?: string;
  score?: number | null;
  threshold?: number | null;
  direction?: string;
};

export type EvaluationJudgeSummary = {
  provider?: string;
  status?: string;
  evaluatedSampleCount?: number;
  attemptedSampleCount?: number;
  headline?: string;
  strengths?: string[];
  risks?: string[];
  nextActions?: string[];
  promptMetadata?: {
    promptSchemaVersion?: string;
    promptHash?: string;
    promptBytes?: number;
    omittedSections?: string[];
    model?: string;
    baseUrlHost?: string;
  };
};

export type EvaluationSummary = {
  overallStatus?: string;
  overallPassed?: boolean;
  gateStatus?: "PASSED" | "NOT_PASSED" | "INCOMPLETE" | string;
  judgeStatus?: "NOT_REQUESTED" | "AVAILABLE" | "FAILED" | "PARTIAL" | "SKIPPED" | string;
  headline?: string;
  localMetricCount?: number;
  passedMetricCount?: number;
  failedMetricCount?: number;
  skippedMetricCount?: number;
  failedMetrics?: EvaluationFailedMetric[];
  judge?: EvaluationJudgeSummary;
};

export type EvaluationMetricsPayload = {
  metrics: EvaluationMetric[];
  summary?: EvaluationSummary;
};

export type EvaluationDataset = {
  id: string;
  sampleCount: number;
  sizeBytes: number;
  kind: EvaluationDatasetKind;
  version: string;
  sha256: string;
};

export type EvaluationCapabilities = {
  enabled: boolean;
  sources: EvaluationSource[];
  judgeProviders: EvaluationJudgeProvider[];
  datasets: EvaluationDataset[];
  defaultBaseUrl: string;
  maxSampleLimit: number;
  maxTimeoutSeconds: number;
};

export type EvaluationRunConfig = {
  name: string;
  datasetId: string;
  source: EvaluationSource;
  environmentId: string;
  sampleLimit: number;
  baseUrl: string;
  ragLogPath: string;
  timeoutSeconds: number;
  judgeProvider: EvaluationJudgeProvider;
  judgeLimit: number;
  strictMissingRecords: boolean;
  baselineRunId: string;
  taskId: string;
};

export type EvaluationRun = {
  runId: string;
  name: string;
  attemptNo: number;
  parentRunId: string;
  status: EvaluationRunStatus;
  phaseMessage: string;
  progressPercent: number;
  config: EvaluationRunConfig;
  sampleCount: number;
  passedSampleCount: number;
  failedSampleCount: number;
  overallPassed: boolean;
  metricsJson: string;
  errorCategory: string;
  errorMessage: string;
  version: number;
  createdAtEpochMillis: number;
  startedAtEpochMillis: number;
  finishedAtEpochMillis: number;
  updatedAtEpochMillis: number;
};

export type EvaluationRunOverview = {
  total: number;
  active: number;
  gatePassed: number;
  incomplete: number;
  failed: number;
};

export type EvaluationRunHistoryQuery = {
  keyword?: string;
  datasetKind?: EvaluationDatasetKind;
  status?: EvaluationRunStatus;
  gateStatus?: string;
  judgeStatus?: string;
  page: number;
  pageSize: number;
};

export type EvaluationRunPage = {
  records: EvaluationRun[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
  overview: EvaluationRunOverview;
};

export type EvaluationRunEvent = {
  eventId: string;
  runId: string;
  fromStatus: EvaluationRunStatus | null;
  toStatus: EvaluationRunStatus;
  message: string;
  errorCategory: string;
  errorMessage: string;
  occurredAtEpochMillis: number;
};

export type EvaluationArtifact = {
  artifactId: string;
  artifactType: string;
  artifactUri: string;
  contentPreview: string;
  contentHash: string;
  sizeBytes: number;
  createdAtEpochMillis: number;
};

export type EvaluationContent = {
  artifactType: string;
  content: string;
};

export const getEvaluationCapabilities = () =>
  api.get<EvaluationCapabilities, EvaluationCapabilities>("/admin/evaluations/capabilities");

export const getEvaluationRuns = (query: EvaluationRunHistoryQuery) =>
  api.get<EvaluationRunPage, EvaluationRunPage>("/admin/evaluations/runs", { params: query });

export const createEvaluationRun = (config: EvaluationRunConfig) =>
  api.post<EvaluationRun, EvaluationRun>("/admin/evaluations/runs", config);

export const createTaskRunEvaluation = (
  taskId: string,
  settings: Partial<Pick<EvaluationRunConfig, "judgeProvider" | "judgeLimit" | "timeoutSeconds" | "baselineRunId">> = {}
) => api.post<EvaluationRun, EvaluationRun>(`/admin/rd-tasks/${taskId}/evaluations`, {
  judgeProvider: settings.judgeProvider || "NONE",
  judgeLimit: settings.judgeLimit || 0,
  timeoutSeconds: settings.timeoutSeconds || 90,
  baselineRunId: settings.baselineRunId || ""
});

export const getEvaluationRun = (runId: string) =>
  api.get<EvaluationRun, EvaluationRun>(`/admin/evaluations/runs/${runId}`);

export const getEvaluationTimeline = (runId: string) =>
  api.get<EvaluationRunEvent[], EvaluationRunEvent[]>(`/admin/evaluations/runs/${runId}/timeline`);

export const getEvaluationArtifacts = (runId: string) =>
  api.get<EvaluationArtifact[], EvaluationArtifact[]>(`/admin/evaluations/runs/${runId}/artifacts`);

export const getEvaluationLogs = (runId: string) =>
  api.get<EvaluationContent, EvaluationContent>(`/admin/evaluations/runs/${runId}/logs`);

export const getEvaluationArtifactContent = (runId: string, artifactType: string) =>
  api.get<EvaluationContent, EvaluationContent>(
    `/admin/evaluations/runs/${runId}/artifacts/${encodeURIComponent(artifactType)}/content`
  );

export const cancelEvaluationRun = (runId: string) =>
  api.post<EvaluationRun, EvaluationRun>(`/admin/evaluations/runs/${runId}/cancel`, {});

export const retryEvaluationRun = (runId: string) =>
  api.post<EvaluationRun, EvaluationRun>(`/admin/evaluations/runs/${runId}/retry`, {});

export const isEvaluationRunActive = (status: EvaluationRunStatus) =>
  ["CREATED", "QUEUED", "RECORDING", "SCORING", "REPORTING", "DIFFING", "CANCEL_REQUESTED"].includes(status);
