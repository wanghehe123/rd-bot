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

/**
 * 对齐 InventoryView。categories 的键是 InventoryCategory.name()
 * （TOMBSTONE…），不是 InventoryCategoryCounts 的 camelCase 字段。
 */
export interface OpenVikingInventory {
  categories: Record<string, number>;
  documentTotal: number;
  sumMatchesTotal: boolean;
  pendingBackfillRemaining: number;
  inFlightOperations: number;
}

/** 对齐 KeysetPageView：候选用 after 游标，不是 offset 的 page/total。 */
export interface OpenVikingKeysetPage<T> {
  records: T[];
  size: number;
  after: string;
}

/** 对齐 ListView：重复组与漂移只有 records + size。 */
export interface OpenVikingListView<T> {
  records: T[];
  size: number;
}

/** 对齐 CandidateView。 */
export interface OpenVikingInventoryCandidate {
  documentId: string;
  sourceName: string;
  chunkCount: number;
  checksum: string;
  syncVersion: number;
  rowVersion: number;
  sourceIdentityKey: string;
}

/** 对齐 DuplicateMemberView / DuplicateIdentityMember。没有 sourceName / hasBinding。 */
export interface OpenVikingDuplicateMember {
  documentId: string;
  lastSyncedAtEpochMillis: number;
  createdAtEpochMillis: number;
  rowVersion: number;
}

/** 对齐 DuplicateGroupView。 */
export interface OpenVikingDuplicateGroup {
  identityKey: string;
  proposedSurvivorDocumentId: string;
  members: OpenVikingDuplicateMember[];
}

/** 对齐 DriftView。desiredState 是 ExternalKnowledgeDesiredState.name()。 */
export interface OpenVikingInventoryDrift {
  documentId: string;
  remoteUri: string;
  desiredState: string;
}

/** 对齐 BackfillOutcomeView。status 是 InventoryBackfillStatus.name()。 */
export interface OpenVikingBackfillOutcome {
  documentId: string;
  status: string;
  reason: string;
}

/** 对齐 BackfillReportView。stopReason 是 in-flight cap 文案；failed 计入 FAILED 状态。 */
export interface OpenVikingBackfillReport {
  attempted: number;
  applied: number;
  skippedAlreadyBound: number;
  skippedNotEligible: number;
  skippedConcurrentModification: number;
  failed: number;
  stopReason: string;
  outcomes: OpenVikingBackfillOutcome[];
}

/** 对齐 ResolveRequest / SupersedeTarget。 */
export interface OpenVikingDuplicateResolveRequest {
  identityKey: string;
  survivorDocumentId: string;
  losers: Array<{ documentId: string; expectedRowVersion: number }>;
}

/** 对齐 ResolveView。 */
export interface OpenVikingInventorySupersede {
  applied: boolean;
  status: string;
  message: string;
}

export function getOpenVikingInventory(kbId: string): Promise<OpenVikingInventory> {
  return getData<OpenVikingInventory>(`${openVikingBase(kbId)}/inventory`);
}

export function getOpenVikingInventoryCandidates(
  kbId: string,
  query: { after?: string; size?: number } = {}
): Promise<OpenVikingKeysetPage<OpenVikingInventoryCandidate>> {
  return getData<OpenVikingKeysetPage<OpenVikingInventoryCandidate>>(
    `${openVikingBase(kbId)}/inventory/candidates`,
    {
      after: query.after || undefined,
      size: query.size ?? 20
    }
  );
}

export function getOpenVikingInventoryDuplicates(
  kbId: string,
  query: { size?: number } = {}
): Promise<OpenVikingListView<OpenVikingDuplicateGroup>> {
  return getData<OpenVikingListView<OpenVikingDuplicateGroup>>(`${openVikingBase(kbId)}/inventory/duplicates`, {
    size: query.size ?? 50
  });
}

export function getOpenVikingInventoryDrift(
  kbId: string,
  query: { size?: number } = {}
): Promise<OpenVikingListView<OpenVikingInventoryDrift>> {
  return getData<OpenVikingListView<OpenVikingInventoryDrift>>(`${openVikingBase(kbId)}/inventory/drift`, {
    size: query.size ?? 50
  });
}

export function backfillOpenVikingInventory(
  kbId: string,
  body: { limit: number }
): Promise<OpenVikingBackfillReport> {
  return postData<OpenVikingBackfillReport>(`${openVikingBase(kbId)}/inventory/backfill`, {
    limit: body.limit
  });
}

export function resolveOpenVikingDuplicates(
  kbId: string,
  body: OpenVikingDuplicateResolveRequest
): Promise<OpenVikingInventorySupersede> {
  return postData<OpenVikingInventorySupersede>(`${openVikingBase(kbId)}/inventory/duplicates/resolve`, body);
}
