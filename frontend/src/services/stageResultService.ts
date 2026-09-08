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

export interface StageResultSelection {
  taskId: string;
  stageRunId: string;
  status: string;
  resultPreview?: string | null;
}

/** Stable identity for the selected stage's result availability. */
export const stageResultSelectionSignature = (selection: StageResultSelection): string => (
  `${selection.taskId}:${selection.stageRunId}:${selection.status}:${selection.resultPreview || ""}`
);

/** Avoid probing unfinished Attempts; a terminal success or an existing preview is readable. */
export const shouldAutoLoadStageResult = (selection: Pick<StageResultSelection, "status" | "resultPreview">): boolean => (
  Boolean(selection.resultPreview?.trim()) || selection.status === "SUCCEEDED"
);

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

/**
 * Reads the full finalized role JSON. The content endpoint is JSON, so axios
 * may already have decoded it; normalize both decoded objects and text to the
 * string consumed by the role presentation model.
 */
export const getStageResultContent = async (
  taskId: string,
  stageRunId: string
): Promise<string> => {
  const payload = await api.get<unknown, unknown>(getStageResultContentUrl(taskId, stageRunId));
  if (typeof payload === "string") return payload;
  if (payload === null || payload === undefined) {
    throw new Error("完整角色结果正文为空");
  }
  return JSON.stringify(payload);
};

/**
 * Fetches the selected Attempt's result and, when the metadata says the
 * FINALIZATION_RESULT is truncated, follows the same task/stage identity to
 * the full content endpoint. The guard is checked between both requests so a
 * late response cannot be applied after an Attempt switch.
 */
export const getCompleteStageResult = async (
  taskId: string,
  stageRunId: string,
  isCurrent: () => boolean = () => true
): Promise<StageResultResponse | null> => {
  const preview = await getStageResult(taskId, stageRunId);
  if (!isCurrent()) return null;

  const needsFullContent = preview.source === "FINALIZATION_RESULT"
    && preview.available
    && preview.truncated;
  if (!needsFullContent) return preview;

  const content = await getStageResultContent(taskId, stageRunId);
  if (!isCurrent()) return null;
  return {
    ...preview,
    content,
    truncated: false
  };
};
