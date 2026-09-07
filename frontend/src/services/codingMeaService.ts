import { api } from "./api.ts";
import type { AuditedTaskAuditRun } from "./rdTaskService.ts";

export interface CodingMeaQuery {
  codingStageRunId?: string;
  cursor?: string;
  limit?: number;
}

/**
 * 后端 EvidenceRefView：URI 引用而非 artifactId。
 * 见 engine/src/main/java/com/wish/rd/engine/requirement/query/CodingMeaResponse.java
 */
export interface MeaEvidenceRef {
  auditRunId: string;
  sourceKind: string;
  uri: string;
  sha256: string | null;
}

/** 后端 AuditedRecordView wire 字段：id/text/evidenceRefs（不是 recordId/title/evidenceIds）。 */
export interface MeaStateRecord {
  id: string;
  kind: string;
  text: string;
  status: string;
  blocking: boolean;
  evidenceRefs: MeaEvidenceRef[];
  sourceStageRunId: string | null;
  blockedReason: string | null;
}

export interface StateSlice {
  available: boolean;
  unavailableReason: string | null;
  stateVersion: number | null;
  stateHash: string | null;
  recordsTruncated: boolean;
  records: MeaStateRecord[];
}

export interface StageReference {
  stageRunId: string;
  role: string;
  attemptNo: number;
  status: string;
  resultArtifactId: string | null;
  startedAtEpochMillis: number | null;
  finishedAtEpochMillis: number | null;
}

export interface CommandReference {
  commandId: string;
  stage: string;
  role: string;
  status: string;
  stageRunId: string | null;
  stageLinkReason: string | null;
  commandAttemptNo: number;
  remediationRoundId: string | null;
  remediationKind: string | null;
  remediationNo: number | null;
  remediationSourceStageRunId: string | null;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
}

/** coding-mea 列表内嵌的决策预览（boundedContractPreview 截断 2000 字）。 */
export interface DecisionReference {
  managerCommandId: string | null;
  roundNo: number;
  sourceCommandId: string;
  decisionHash: string;
  route: string;
  executorRoute: string | null;
  targetRecordIds: string[];
  boundedContractPreview: string;
  boundedContractTruncated: boolean;
  rationale: string;
  stateVersion: number;
  stateHash: string;
  stateAtDecision: StateSlice;
  commandCreatedAtEpochMillis: number | null;
}

/**
 * Manager 决策全文端点（/manager-decisions/{decisionHash}）的独立 wire type：
 * 全文字段是 boundedContract，与列表预览不是同一个 DTO。
 */
export interface ManagerDecisionDetail {
  taskId: string;
  roundNo: number;
  sourceCommandId: string;
  decisionHash: string;
  route: string;
  executorRoute: string | null;
  targetRecordIds: string[];
  boundedContract: string;
  rationale: string;
  stateVersion: number;
  stateHash: string;
}

export interface RemediationReference {
  roundId: string;
  kind: string;
  remediationNo: number;
  sourceStageRunId: string | null;
  targetCodingStageRunId: string | null;
  targetQaStageRunId: string | null;
  firstCommandId: string | null;
  status: string;
}

export interface HostVerificationReference {
  runId: string;
  codingStageRunId: string;
  parentRunId: string | null;
  status: string;
  docsOnly: boolean;
  failureCategory: string | null;
}

export interface MeaLink {
  fromType: "COMMAND" | "STAGE" | "DECISION" | "HOST_VERIFY" | "AUDIT";
  fromId: string;
  toType: "COMMAND" | "STAGE" | "DECISION" | "HOST_VERIFY" | "AUDIT";
  toId: string | null;
  relation: "DECIDED_AFTER" | "EXECUTES" | "VERIFIES" | "AUDITS" | "CONTINUES_AS";
  available: boolean;
  unavailableReason: string | null;
}

export interface CodingMeaResponse {
  schemaVersion: 1;
  taskId: string;
  codingStageRunId: string | null;
  available: boolean;
  unavailableReason: string | null;
  snapshotReadAtEpochMillis: number;
  taskVersion: number;
  taskStatus: string;
  paused: boolean;
  head: StateSlice;
  codingStages: StageReference[];
  qaStages: StageReference[];
  commands: CommandReference[];
  decisions: DecisionReference[];
  remediations: RemediationReference[];
  hostVerifications: HostVerificationReference[];
  auditRuns: AuditedTaskAuditRun[];
  links: MeaLink[];
  page: {
    hasMore: boolean;
    nextCursor: string | null;
  };
}

export const getCodingMea = (
  taskId: string,
  query?: CodingMeaQuery
): Promise<CodingMeaResponse> => {
  return api.get<CodingMeaResponse, CodingMeaResponse>(
    `/admin/rd-tasks/${taskId}/coding-mea`,
    { params: query }
  );
};

export const getManagerDecision = (
  taskId: string,
  decisionHash: string
): Promise<ManagerDecisionDetail> => {
  return api.get<ManagerDecisionDetail, ManagerDecisionDetail>(
    `/admin/rd-tasks/${taskId}/manager-decisions/${decisionHash}`
  );
};
