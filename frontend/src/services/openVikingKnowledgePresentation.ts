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
