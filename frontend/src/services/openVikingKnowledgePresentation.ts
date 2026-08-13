/** OpenViking 管理面纯展示逻辑。不依赖 React / axios，供 Node 合同测试直接导入。 */

export type ProjectionBadgeTone = "green" | "blue" | "amber" | "red" | "orange" | "slate";

export type PollingSummary = {
  operations?: Array<{ status?: string | null }>;
  documents?: Array<{ projectionStatus?: string | null }>;
  outboxCounts?: Record<string, number>;
  bindingCounts?: Record<string, number>;
  unconvergedCount?: number;
};

export type PollingDecision = {
  keepPolling: boolean;
  consecutiveStableRounds: number;
};

export type TreeEntryTone = "owned" | "foreign" | "orphan";

const TERMINAL_OPERATION_STATUSES = new Set(["SUCCEEDED", "SUPERSEDED", "DEAD_LETTER"]);

const TRANSIENT_PROJECTION_STATUSES = new Set(["PENDING", "PROCESSING", "DELETING"]);

const BADGE_TONE_BY_STATUS: Record<string, ProjectionBadgeTone> = {
  IN_SYNC: "green",
  PENDING: "blue",
  PROCESSING: "blue",
  DRIFTED: "amber",
  FAILED: "red",
  DEAD_LETTER: "red",
  NEEDS_HUMAN: "orange",
  DELETING: "slate",
  DELETED: "slate"
};

const BADGE_CLASS_BY_TONE: Record<ProjectionBadgeTone, string> = {
  green: "border-green-200 bg-green-50 text-green-700",
  blue: "border-blue-200 bg-blue-50 text-blue-700",
  amber: "border-amber-200 bg-amber-50 text-amber-700",
  red: "border-red-200 bg-red-50 text-red-700",
  orange: "border-orange-200 bg-orange-50 text-orange-700",
  slate: "border-slate-200 bg-slate-50 text-slate-700"
};

const PROJECTION_STATUS_LABELS: Record<string, string> = {
  IN_SYNC: "已同步",
  PENDING: "待处理",
  PROCESSING: "处理中",
  DRIFTED: "已漂移",
  FAILED: "失败",
  DEAD_LETTER: "死信",
  NEEDS_HUMAN: "需人工",
  DELETING: "删除中",
  DELETED: "已删除"
};

const OPERATION_STATUS_LABELS: Record<string, string> = {
  PENDING: "待处理",
  CLAIMED: "已领取",
  UPLOADING: "上传中",
  SUBMITTED: "已提交",
  WAITING_REMOTE: "等待远端",
  VERIFYING: "核验中",
  UNKNOWN_REMOTE_RESULT: "远端结果未知",
  RETRY_WAIT: "退避等待",
  SUCCEEDED: "已成功",
  SUPERSEDED: "已覆盖",
  DEAD_LETTER: "死信",
  NEEDS_HUMAN: "需人工"
};

const ACTION_OUTCOME_LABELS: Record<string, string> = {
  NOTHING_TO_RETRY: "无需重试",
  RETRIED: "已重试",
  VERIFIED: "已核验",
  DRIFTED: "已漂移",
  REBUILT: "已重建",
  ALREADY_QUEUED: "已在队列",
  REQUEUED: "已重新入队"
};

const STALE_REQUEUE_MESSAGE = "版本已过期，请刷新后重试";

type MaybeAxiosError = {
  response?: {
    status?: number;
    data?: { message?: string };
  };
  message?: string;
};

export function projectionBadgeTone(status?: string | null): ProjectionBadgeTone {
  const normalized = (status ?? "").trim().toUpperCase();
  return BADGE_TONE_BY_STATUS[normalized] ?? "slate";
}

export function projectionBadgeClass(status?: string | null): string {
  return BADGE_CLASS_BY_TONE[projectionBadgeTone(status)];
}

export function operationBadgeTone(status?: string | null): ProjectionBadgeTone {
  const normalized = (status ?? "").trim().toUpperCase();
  if (normalized === "SUCCEEDED") {
    return "green";
  }
  if (normalized === "DEAD_LETTER") {
    return "red";
  }
  if (normalized === "NEEDS_HUMAN") {
    return "orange";
  }
  if (!normalized || normalized === "SUPERSEDED") {
    return "slate";
  }
  return "blue";
}

export function operationBadgeClass(status?: string | null): string {
  return BADGE_CLASS_BY_TONE[operationBadgeTone(status)];
}

export function projectionStatusLabel(status?: string | null): string {
  const normalized = (status ?? "").trim().toUpperCase();
  if (!normalized) {
    return "未知";
  }
  return PROJECTION_STATUS_LABELS[normalized] ?? normalized;
}

export function operationStatusLabel(status?: string | null): string {
  const normalized = (status ?? "").trim().toUpperCase();
  if (!normalized) {
    return "未知";
  }
  return OPERATION_STATUS_LABELS[normalized] ?? normalized;
}

export function actionOutcomeLabel(outcome?: string | null): string {
  const normalized = (outcome ?? "").trim().toUpperCase();
  if (!normalized) {
    return "已提交";
  }
  return ACTION_OUTCOME_LABELS[normalized] ?? normalized;
}

export function isTerminalOperationStatus(status?: string | null): boolean {
  return TERMINAL_OPERATION_STATUSES.has((status ?? "").trim().toUpperCase());
}

export function isTransientProjectionStatus(status?: string | null): boolean {
  return TRANSIENT_PROJECTION_STATUSES.has((status ?? "").trim().toUpperCase());
}

export function shouldKeepPolling(summary: PollingSummary): boolean {
  if ((summary.unconvergedCount ?? 0) > 0) {
    return true;
  }
  for (const operation of summary.operations ?? []) {
    const status = (operation.status ?? "").trim();
    if (status && !isTerminalOperationStatus(status)) {
      return true;
    }
  }
  for (const document of summary.documents ?? []) {
    if (isTransientProjectionStatus(document.projectionStatus)) {
      return true;
    }
  }
  for (const [status, count] of Object.entries(summary.outboxCounts ?? {})) {
    if ((count ?? 0) > 0 && !isTerminalOperationStatus(status)) {
      return true;
    }
  }
  for (const [status, count] of Object.entries(summary.bindingCounts ?? {})) {
    if ((count ?? 0) > 0 && isTransientProjectionStatus(status)) {
      return true;
    }
  }
  return false;
}

export function nextPollingState(previousStableRounds: number, summary: PollingSummary): PollingDecision {
  if (shouldKeepPolling(summary)) {
    return { keepPolling: true, consecutiveStableRounds: 0 };
  }
  const consecutiveStableRounds = previousStableRounds + 1;
  return {
    keepPolling: consecutiveStableRounds < 2,
    consecutiveStableRounds
  };
}

export function displaySafeError(code?: string | null, message?: string | null): string {
  const safeMessage = (message ?? "").trim();
  if (safeMessage) {
    return safeMessage;
  }
  const safeCode = (code ?? "").trim();
  if (safeCode) {
    return safeCode;
  }
  return "无错误详情";
}

export function extractHttpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== "object") {
    return undefined;
  }
  const status = (error as MaybeAxiosError).response?.status;
  return typeof status === "number" ? status : undefined;
}

export function extractServerMessage(error: unknown): string {
  if (typeof error === "string") {
    return error.trim();
  }
  if (!error || typeof error !== "object") {
    return "";
  }
  const dataMessage = (error as MaybeAxiosError).response?.data?.message;
  if (typeof dataMessage === "string" && dataMessage.trim()) {
    return dataMessage.trim();
  }
  return "";
}

export function isProjectionDisabledConflict(error: unknown): boolean {
  return extractHttpStatus(error) === 409 && extractServerMessage(error).includes("关闭");
}

export function mapRequeueConflictMessage(error: unknown): string {
  const status = extractHttpStatus(error);
  const message = extractServerMessage(error);
  if (status === 409 && /row_version/i.test(message)) {
    return STALE_REQUEUE_MESSAGE;
  }
  if (message) {
    return message;
  }
  return "操作失败";
}

export function unwrapAdminData<T>(body: unknown): T {
  if (body == null || typeof body !== "object" || !("data" in body)) {
    throw new Error("响应缺少 data 字段");
  }
  return (body as { data: T }).data;
}

export function shortenChecksum(value?: string | null, length = 8): string {
  const raw = (value ?? "").trim();
  if (!raw) {
    return "—";
  }
  return raw.length <= length ? raw : raw.slice(0, length);
}

export function formatEpochMillis(value?: number | null): string {
  if (!value || value <= 0 || !Number.isFinite(value)) {
    return "—";
  }
  return new Date(value).toLocaleString("zh-CN");
}

export function formatAgeMillis(value?: number | null): string {
  if (!value || value <= 0 || !Number.isFinite(value)) {
    return "—";
  }
  if (value < 1000) {
    return `${Math.round(value)} ms`;
  }
  if (value < 60_000) {
    return `${Math.round(value / 1000)} 秒`;
  }
  if (value < 3_600_000) {
    return `${(value / 60_000).toFixed(1)} 分钟`;
  }
  return `${(value / 3_600_000).toFixed(1)} 小时`;
}

export function sortOperationsNewestFirst<T extends { updatedAtEpochMillis?: number }>(operations: T[]): T[] {
  return [...operations].sort((left, right) => (right.updatedAtEpochMillis ?? 0) - (left.updatedAtEpochMillis ?? 0));
}

function normalizeOwner(owner?: string | null): string {
  return (owner ?? "").trim().toLowerCase();
}

export function ownedByRdBot(owner?: string | null): boolean {
  const normalized = normalizeOwner(owner);
  return (
    normalized === ""
    || normalized === "rd-bot"
    || normalized.startsWith("rd-bot:")
    || normalized === "rd.owner=rd-bot"
  );
}

function normalizeUri(uri?: string | null): string {
  return (uri ?? "").replace(/\/+$/, "");
}

export function treeEntryTone(
  entry: { owner?: string | null; uri?: string | null },
  options: { findingTypes?: string[]; knownRemoteUris?: string[] } = {}
): TreeEntryTone {
  const findingTypes = new Set((options.findingTypes ?? []).map((value) => value.toUpperCase()));
  if (findingTypes.has("FOREIGN_OWNER") || !ownedByRdBot(entry.owner)) {
    return "foreign";
  }
  if (findingTypes.has("ORPHAN_REMOTE")) {
    return "orphan";
  }
  if (options.knownRemoteUris) {
    const known = new Set(options.knownRemoteUris.map(normalizeUri));
    if (!known.has(normalizeUri(entry.uri))) {
      return "orphan";
    }
  }
  return "owned";
}

export const SUMMARY_PROJECTION_STATUSES = [
  "IN_SYNC",
  "PENDING",
  "PROCESSING",
  "DRIFTED",
  "FAILED",
  "DEAD_LETTER",
  "NEEDS_HUMAN",
  "DELETING",
  "DELETED"
] as const;

/** 对齐 InventoryCategory.values() 声明顺序（计划 D5）。 */
export const INVENTORY_CATEGORIES = [
  "TOMBSTONE",
  "SUPERSEDED",
  "DUPLICATE_UNRESOLVED",
  "EXCLUDED_BASE_INACTIVE",
  "EXCLUDED_LOCAL_ONLY",
  "EXCLUDED_EMPTY",
  "FAILED",
  "IN_SYNC",
  "PROJECTING",
  "PENDING_BACKFILL"
] as const;

export type InventoryCategory = (typeof INVENTORY_CATEGORIES)[number];

export type InventoryCategorySeverity = "healthy" | "attention" | "blocked";

/** InventoryCategoryCounts 的 camelCase 字段；HTTP InventoryView 则用枚举 name() 做 key。 */
export type InventoryCategoryCountKey =
  | "tombstone"
  | "superseded"
  | "duplicateUnresolved"
  | "excludedBaseInactive"
  | "excludedLocalOnly"
  | "excludedEmpty"
  | "failed"
  | "inSync"
  | "projecting"
  | "pendingBackfill";

export type InventoryCategoryCounts = Record<InventoryCategoryCountKey, number>;

const INVENTORY_CATEGORY_LABELS: Record<InventoryCategory, string> = {
  TOMBSTONE: "墓碑",
  SUPERSEDED: "已被取代",
  DUPLICATE_UNRESOLVED: "未解决重复身份",
  EXCLUDED_BASE_INACTIVE: "知识库非活动",
  EXCLUDED_LOCAL_ONLY: "手工本地覆盖",
  EXCLUDED_EMPTY: "内容为空",
  FAILED: "投影失败",
  IN_SYNC: "已同步",
  PROJECTING: "投影中",
  PENDING_BACKFILL: "待回填"
};

const INVENTORY_CATEGORY_SEVERITY: Record<InventoryCategory, InventoryCategorySeverity> = {
  TOMBSTONE: "healthy",
  SUPERSEDED: "healthy",
  DUPLICATE_UNRESOLVED: "blocked",
  EXCLUDED_BASE_INACTIVE: "attention",
  EXCLUDED_LOCAL_ONLY: "attention",
  EXCLUDED_EMPTY: "attention",
  FAILED: "blocked",
  IN_SYNC: "healthy",
  PROJECTING: "attention",
  PENDING_BACKFILL: "attention"
};

const INVENTORY_COUNT_KEY_BY_CATEGORY: Record<InventoryCategory, InventoryCategoryCountKey> = {
  TOMBSTONE: "tombstone",
  SUPERSEDED: "superseded",
  DUPLICATE_UNRESOLVED: "duplicateUnresolved",
  EXCLUDED_BASE_INACTIVE: "excludedBaseInactive",
  EXCLUDED_LOCAL_ONLY: "excludedLocalOnly",
  EXCLUDED_EMPTY: "excludedEmpty",
  FAILED: "failed",
  IN_SYNC: "inSync",
  PROJECTING: "projecting",
  PENDING_BACKFILL: "pendingBackfill"
};

const INVENTORY_SEVERITY_CLASS: Record<InventoryCategorySeverity, string> = {
  healthy: "border-green-200 bg-green-50 text-green-700",
  attention: "border-amber-200 bg-amber-50 text-amber-800",
  blocked: "border-red-200 bg-red-50 text-red-800"
};

export const SUM_MISMATCH_WARNING =
  "分类计数之和与文档总数不一致。存在未被归入任何分类的文档，当前分类表不能当作完整账本。";

export const DUPLICATE_RESOLVE_CONFLICT_MESSAGE = "数据已变化，请刷新后重试";

export const DEFAULT_INVENTORY_BACKFILL_LIMIT = 20;

const BACKFILL_STATUS_LABELS: Record<string, string> = {
  APPLIED: "已回填",
  SKIPPED_ALREADY_BOUND: "已有绑定，已跳过",
  SKIPPED_NOT_ELIGIBLE: "不符合条件，已跳过",
  SKIPPED_CONCURRENT_MODIFICATION: "并发修改，已跳过",
  FAILED: "失败"
};

export type DuplicateResolveLoserInput = {
  documentId?: string | null;
  rowVersion?: number | null;
};

export type DuplicateResolveGroupInput = {
  identityKey?: string | null;
  proposedSurvivorDocumentId?: string | null;
  members?: DuplicateResolveLoserInput[] | null;
};

export type DuplicateResolveRequest = {
  identityKey: string;
  survivorDocumentId: string;
  losers: Array<{ documentId: string; expectedRowVersion: number }>;
};

export function inventoryCategoryLabel(category?: string | null): string {
  const normalized = (category ?? "").trim().toUpperCase();
  if (!normalized) {
    return "未知分类";
  }
  return INVENTORY_CATEGORY_LABELS[normalized as InventoryCategory] ?? normalized;
}

export function inventoryCategorySeverity(category?: string | null): InventoryCategorySeverity {
  const normalized = (category ?? "").trim().toUpperCase();
  return INVENTORY_CATEGORY_SEVERITY[normalized as InventoryCategory] ?? "attention";
}

export function inventorySeverityClass(severity?: InventoryCategorySeverity | null): string {
  return INVENTORY_SEVERITY_CLASS[severity ?? "attention"] ?? INVENTORY_SEVERITY_CLASS.attention;
}

export function inventorySumMismatchWarning(sumMatchesTotal: boolean): string {
  return sumMatchesTotal ? "" : SUM_MISMATCH_WARNING;
}

export function inventoryCategoryCountKey(category: InventoryCategory): InventoryCategoryCountKey {
  return INVENTORY_COUNT_KEY_BY_CATEGORY[category];
}

export function countForInventoryCategory(
  categories: Partial<InventoryCategoryCounts> | Record<string, number> | null | undefined,
  category: InventoryCategory
): number {
  if (!categories) {
    return 0;
  }
  const enumValue = (categories as Record<string, number>)[category];
  if (typeof enumValue === "number" && Number.isFinite(enumValue)) {
    return Math.max(0, enumValue);
  }
  const camelValue = (categories as Partial<InventoryCategoryCounts>)[INVENTORY_COUNT_KEY_BY_CATEGORY[category]];
  if (typeof camelValue === "number" && Number.isFinite(camelValue)) {
    return Math.max(0, camelValue);
  }
  return 0;
}

export function inventoryCategoryRows(
  categories: Partial<InventoryCategoryCounts> | Record<string, number> | null | undefined
): Array<{ category: InventoryCategory; label: string; count: number; severity: InventoryCategorySeverity }> {
  return INVENTORY_CATEGORIES.map((category) => ({
    category,
    label: inventoryCategoryLabel(category),
    count: countForInventoryCategory(categories, category),
    severity: inventoryCategorySeverity(category)
  }));
}

export function backfillStatusLabel(status?: string | null): string {
  const normalized = (status ?? "").trim().toUpperCase();
  if (!normalized) {
    return "未知结果";
  }
  return BACKFILL_STATUS_LABELS[normalized] ?? normalized;
}

export function mapDuplicateResolveConflictMessage(error: unknown): string {
  if (extractHttpStatus(error) === 409) {
    return DUPLICATE_RESOLVE_CONFLICT_MESSAGE;
  }
  const message = extractServerMessage(error);
  return message || "解决重复身份失败";
}

export function buildDuplicateResolveRequest(group: DuplicateResolveGroupInput): DuplicateResolveRequest {
  const identityKey = (group.identityKey ?? "").trim();
  const survivorDocumentId = (group.proposedSurvivorDocumentId ?? "").trim();
  if (!identityKey) {
    throw new Error("缺少来源身份键，无法构造解决请求");
  }
  if (!survivorDocumentId) {
    throw new Error("缺少提出的存活文档，无法构造解决请求");
  }
  const members = group.members ?? [];
  const losers = members.filter((member) => (member.documentId ?? "").trim() !== survivorDocumentId);
  if (losers.length === 0) {
    throw new Error("没有可取代的成员，无法构造解决请求");
  }
  const resolved: DuplicateResolveRequest["losers"] = [];
  for (const loser of losers) {
    const documentId = (loser.documentId ?? "").trim();
    const rowVersion = loser.rowVersion;
    if (!documentId || rowVersion == null || !Number.isFinite(rowVersion)) {
      throw new Error("被取代文档缺少 expectedRowVersion，无法构造解决请求");
    }
    resolved.push({ documentId, expectedRowVersion: rowVersion });
  }
  return { identityKey, survivorDocumentId, losers: resolved };
}
