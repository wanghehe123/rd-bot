import { api } from "./api.ts";

export interface StageResultResponse {
  taskId: string;
  stageRunId: string;
  role: string;
  attemptNo: number;
  artifactId: string | null;
  commandId: string | null;
  finalizationId: string | null;
  source: "FINALIZATION_RESULT" | "ARTIFACT_PREVIEW" | "UNAVAILABLE";
  available: boolean;
  unavailableReason: string | null;
  contentType: string;
  content: string | null;
  truncated: boolean;
  contentSha256: string | null;
  downloadPath: string | null;
}

export const getStageResult = (
  taskId: string,
  stageRunId: string
): Promise<StageResultResponse> => {
  return api.get<StageResultResponse, StageResultResponse>(
    `/admin/rd-tasks/${taskId}/stage-runs/${stageRunId}/result`
  );
};

export const getStageResultContentUrl = (
  taskId: string,
  stageRunId: string
): string => {
  return `/admin/rd-tasks/${taskId}/stage-runs/${stageRunId}/result/content`;
};
