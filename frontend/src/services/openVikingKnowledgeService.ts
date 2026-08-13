import { api } from "@/services/api";

import { unwrapAdminData } from "@/services/openVikingKnowledgePresentation";

/** 对齐 KnowledgeProjectionAdminController.OverviewView */
export interface OpenVikingOverview {
  ready: boolean;
  bindingCounts: Record<string, number>;
  outboxCounts: Record<string, number>;
  unconvergedCount: number;
  oldestUnconvergedAgeMillis: number;
}

/** 对齐 KnowledgeProjectionAdminController.PageView */
export interface OpenVikingPage<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

/** 对齐 KnowledgeProjectionAdminController.DocumentRowView */
export interface OpenVikingDocumentRow {
  documentId: string;
  sourceName: string;
  tombstone: boolean;
  desiredState: string;
  desiredVersion: number;
  desiredChecksum: string;
  observedState: string;
  observedVersion: number;
  observedChecksum: string;
  projectionStatus: string;
  remoteUri: string;
  remoteTaskId: string;
  activeOperationId: string;
  lastVerifiedAtEpochMillis: number;
  lastErrorCode: string;
  lastErrorMessage: string;
}

/** 对齐 KnowledgeProjectionAdminController.BindingView */
export interface OpenVikingBinding {
  documentId: string;
  knowledgeBaseId: string;
  remoteUri: string;
  desiredState: string;
  desiredVersion: number;
  desiredChecksum: string;
  observedState: string;
  observedVersion: number;
  observedChecksum: string;
  projectionStatus: string;
  remoteTaskId: string;
  activeOperationId: string;
  semanticConfigFingerprint: string;
  lastVerifiedAtEpochMillis: number;
  lastErrorCode: string;
  lastErrorMessage: string;
  rowVersion: number;
}

/** 对齐 KnowledgeProjectionAdminController.OperationView */
export interface OpenVikingOperation {
  eventId: string;
  operationType: string;
  status: string;
  syncVersion: number;
  remoteUri: string;
  remoteTaskId: string;
  remoteOperationId: string;
  attemptCount: number;
  maxAttempts: number;
  rowVersion: number;
  lastErrorCode: string;
  lastErrorMessage: string;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
}

/** 对齐 KnowledgeProjectionAdminController.DocumentDetailView */
export interface OpenVikingDocumentDetail {
  binding: OpenVikingBinding;
  operations: OpenVikingOperation[];
  lastErrorCode: string;
  lastErrorMessage: string;
}

/** 对齐 KnowledgeProjectionAdminController.TombstoneView */
export interface OpenVikingTombstone {
  documentId: string;
  sourceName: string;
  deletedAtEpochMillis: number;
  projectionStatus: string;
  remoteUri: string;
}

/** 对齐 KnowledgeProjectionAdminController.TreeEntryView */
export interface OpenVikingTreeEntry {
  uri: string;
  name: string;
  directory: boolean;
  owner: string;
}

/** 对齐 KnowledgeProjectionAdminController.TreeView */
export interface OpenVikingTree {
  entries: OpenVikingTreeEntry[];
  errorCode: string;
  errorMessage: string;
}

/** 对齐 KnowledgeProjectionAdminController.HealthView */
export interface OpenVikingHealth {
  ready: boolean;
  workerBatchSize: number;
  workerLeaseMillis: number;
  unknownOutcomeTimeoutMillis: number;
  semanticConfigFingerprint: string;
  openFindingCount: number;
  findingCounts: Record<string, number>;
}

/** 对齐 KnowledgeProjectionAdminController.ActionView */
export interface OpenVikingAction {
  applied: boolean;
  outcome: string;
  eventId: string;
  operationStatus: string;
  failedChecks: string[];
  message: string;
}

/** 对齐 KnowledgeProjectionAdminController.FindingView */
export interface OpenVikingFinding {
  id: string;
  findingType: string;
  remoteUri: string;
  documentId: string;
  detail: string;
  status: string;
  firstSeenAtEpochMillis: number;
  lastSeenAtEpochMillis: number;
}

/** 对齐 KnowledgeProjectionAdminController.ReconcileView */
export interface OpenVikingReconcile {
  counts: Record<string, number>;
  findings: OpenVikingFinding[];
}

type DataEnvelope<T> = { data: T };

function openVikingBase(kbId: string): string {
  return `/admin/knowledge-base/${kbId}/openviking`;
}

async function getData<T>(url: string, params?: Record<string, string | number | undefined>): Promise<T> {
  const body = await api.get<DataEnvelope<T>, DataEnvelope<T>>(url, { params });
  return unwrapAdminData<T>(body);
}

async function postData<T>(url: string, payload?: unknown): Promise<T> {
  const body = await api.post<DataEnvelope<T>, DataEnvelope<T>>(url, payload);
  return unwrapAdminData<T>(body);
}

export function getOpenVikingOverview(kbId: string): Promise<OpenVikingOverview> {
  return getData<OpenVikingOverview>(`${openVikingBase(kbId)}/overview`);
}

export function getOpenVikingDocuments(
  kbId: string,
  query: { status?: string; page?: number; size?: number } = {}
): Promise<OpenVikingPage<OpenVikingDocumentRow>> {
  return getData<OpenVikingPage<OpenVikingDocumentRow>>(`${openVikingBase(kbId)}/documents`, {
    status: query.status || undefined,
    page: query.page ?? 1,
    size: query.size ?? 20
  });
}

export function getOpenVikingDocumentDetail(kbId: string, docId: string): Promise<OpenVikingDocumentDetail> {
  return getData<OpenVikingDocumentDetail>(`${openVikingBase(kbId)}/documents/${docId}`);
}

export function getOpenVikingTree(kbId: string, uri?: string): Promise<OpenVikingTree> {
  return getData<OpenVikingTree>(`${openVikingBase(kbId)}/tree`, { uri: uri || undefined });
}

export function getOpenVikingHealth(kbId: string): Promise<OpenVikingHealth> {
  return getData<OpenVikingHealth>(`${openVikingBase(kbId)}/health`);
}

export function getOpenVikingDeadLetters(
  kbId: string,
  query: { page?: number; size?: number } = {}
): Promise<OpenVikingPage<OpenVikingOperation>> {
  return getData<OpenVikingPage<OpenVikingOperation>>(`${openVikingBase(kbId)}/dead-letters`, {
    page: query.page ?? 1,
    size: query.size ?? 20
  });
}

export function getOpenVikingTombstones(
  kbId: string,
  query: { page?: number; size?: number } = {}
): Promise<OpenVikingPage<OpenVikingTombstone>> {
  return getData<OpenVikingPage<OpenVikingTombstone>>(`${openVikingBase(kbId)}/tombstones`, {
    page: query.page ?? 1,
    size: query.size ?? 20
  });
}

export function retryOpenVikingDocument(kbId: string, docId: string): Promise<OpenVikingAction> {
  return postData<OpenVikingAction>(`${openVikingBase(kbId)}/documents/${docId}/retry`, {});
}

export function verifyOpenVikingDocument(kbId: string, docId: string): Promise<OpenVikingAction> {
  return postData<OpenVikingAction>(`${openVikingBase(kbId)}/documents/${docId}/verify`, {});
}

export function rebuildOpenVikingDocument(kbId: string, docId: string): Promise<OpenVikingAction> {
  return postData<OpenVikingAction>(`${openVikingBase(kbId)}/documents/${docId}/rebuild`, {});
}

export function requeueOpenVikingDeadLetter(
  kbId: string,
  eventId: string,
  expectedRowVersion: number
): Promise<OpenVikingAction> {
  return postData<OpenVikingAction>(`${openVikingBase(kbId)}/dead-letters/${eventId}/requeue`, {
    expectedRowVersion
  });
}

export function reconcileOpenViking(kbId: string): Promise<OpenVikingReconcile> {
  return postData<OpenVikingReconcile>(`${openVikingBase(kbId)}/reconcile`, {});
}
