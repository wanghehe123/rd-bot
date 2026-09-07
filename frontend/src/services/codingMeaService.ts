import { api } from "./api.ts";
import type { AuditedTaskAuditRun } from "./rdTaskService.ts";

export interface CodingMeaQuery {
  stageRunId?: string;
  cursor?: string;
  limit?: number;
}

export interface StateSlice {
  stateVersion: number;
  stateHash: string;
  recordsTruncated: boolean;
  records: Array<{
    recordId: string;
    kind: string;
    title: string;
    status: string;
    blocking: boolean;
    evidenceIds: string[];
  }>;
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
): Promise<DecisionReference> => {
  return api.get<DecisionReference, DecisionReference>(
    `/admin/rd-tasks/${taskId}/manager-decisions/${decisionHash}`
  );
};
