/** 项目记忆治理面纯展示逻辑。不依赖 React / axios，供 Node 合同测试直接导入。 */

export type RevisionBadgeTone = "green" | "blue" | "amber" | "red" | "orange" | "slate";

export type ProjectMemorySummary = {
  memoryId: string;
  projectId: string;
  scopeRole: string;
  memoryType: string;
  logicalKey: string;
  memoryRowVersion: number;
  headRevisionId: string;
  headVersion: number;
  headStatus: string;
  deleted: boolean;
};

export type ProjectMemoryRevision = {
  revisionId: string;
  version: number;
  status: string;
  title: string;
  summary: string;
  contentHash: string;
  rowVersion: number;
  head: boolean;
};

export type ProjectMemorySource = {
  sourceId: string;
  revisionId: string;
  projectId: string;
  taskId: string;
  stageRunId: string;
  artifactId: string;
  sourceUri: string;
  sourceContentHash: string;
  repositoryRevision: string;
  extractorVersion: string;
  schemaVersion: string;
  redactedSummary: string;
  originReferencesAvailable: boolean;
};

export type ProjectMemoryRetrievalAudit = {
  memoryId: string;
  revisionId: string;
  revisionVersion: number;
  querySummary: string;
  examinedRowCount: number;
  observedAtEpochMillis: number;
};

export type ProjectMemoryDetail = {
  memoryId: string;
  projectId: string;
  scopeRole: string;
  memoryType: string;
  logicalKey: string;
  memoryRowVersion: number;
  headRevisionId: string;
  headVersion: number;
  deleted: boolean;
  revisions: ProjectMemoryRevision[];
  sources: ProjectMemorySource[];
  retrievalAudits: ProjectMemoryRetrievalAudit[];
};

export type ProjectMemoryListPage = {
  records: ProjectMemorySummary[];
  total: number;
  page: number;
  size: number;
  mutationsEnabled: boolean;
  projectReadOnly: boolean;
};

export type ProjectMemoryMutation = {
  memoryId: string;
  revisionId: string;
  memoryRowVersion: number;
  revisionRowVersion: number;
  revisionStatus: string;
  memoryDeleted: boolean;
  operatorId: string;
  requestId: string;
};

export type GovernanceAvailability = {
  mutationsEnabled: boolean;
  projectReadOnly: boolean;
};

const BADGE_TONE_BY_STATUS: Record<string, RevisionBadgeTone> = {
  ACTIVE: "green",
  CANDIDATE: "blue",
  QUARANTINED: "orange",
  SUPERSEDED: "slate",
  EXPIRED: "slate",
  REJECTED: "red",
  DELETED: "slate"
};

const BADGE_CLASS_BY_TONE: Record<RevisionBadgeTone, string> = {
  green: "border-green-200 bg-green-50 text-green-700",
  blue: "border-blue-200 bg-blue-50 text-blue-700",
  amber: "border-amber-200 bg-amber-50 text-amber-700",
  red: "border-red-200 bg-red-50 text-red-700",
  orange: "border-orange-200 bg-orange-50 text-orange-700",
  slate: "border-slate-200 bg-slate-50 text-slate-700"
};

const REVISION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "已激活",
  CANDIDATE: "候选",
  SUPERSEDED: "已取代",
  EXPIRED: "已过期",
  REJECTED: "已拒绝",
  QUARANTINED: "已隔离",
  DELETED: "已删除"
};

const MEMORY_TYPE_LABELS: Record<string, string> = {
  PROCEDURAL: "程序记忆",
  SEMANTIC: "语义记忆",
  EPISODIC: "情景记忆"
};

const STALE_GOVERNANCE_MESSAGE = "版本已过期，请刷新后重试";

type MaybeAxiosError = {
  response?: {
    status?: number;
    data?: { message?: string };
  };
};

function extractHttpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== "object") {
    return undefined;
  }
  const status = (error as MaybeAxiosError).response?.status;
  return typeof status === "number" ? status : undefined;
}

function extractServerMessage(error: unknown): string {
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

function shortenChecksum(value?: string | null, length = 8): string {
  const raw = (value ?? "").trim();
  if (!raw) {
    return "—";
  }
  return raw.length <= length ? raw : raw.slice(0, length);
}

export function revisionStatusBadgeTone(status?: string | null): RevisionBadgeTone {
  const normalized = (status ?? "").trim().toUpperCase();
  return BADGE_TONE_BY_STATUS[normalized] ?? "slate";
}

export function revisionStatusBadgeClass(status?: string | null): string {
  return BADGE_CLASS_BY_TONE[revisionStatusBadgeTone(status)];
}

export function revisionStatusLabel(status?: string | null): string {
  const normalized = (status ?? "").trim().toUpperCase();
  if (!normalized) {
    return "未知";
  }
  return REVISION_STATUS_LABELS[normalized] ?? normalized;
}

export function memoryTypeLabel(memoryType?: string | null): string {
  const normalized = (memoryType ?? "").trim().toUpperCase();
  if (!normalized) {
    return "未知";
  }
  return MEMORY_TYPE_LABELS[normalized] ?? normalized;
}

export function sortRevisionsForDisplay(revisions: readonly ProjectMemoryRevision[]): ProjectMemoryRevision[] {
  return [...revisions].sort((left, right) => {
    if (left.head !== right.head) {
      return left.head ? -1 : 1;
    }
    return right.version - left.version;
  });
}

export function formatSourceReference(source: ProjectMemorySource): string {
  const summary = (source.redactedSummary || "").trim() || "—";
  if (!source.originReferencesAvailable) {
    return `${summary} · 来源实体已不可用`;
  }
  const taskRef = (source.taskId || "").trim();
  const artifactRef = (source.artifactId || "").trim();
  const refs = [taskRef && `task ${taskRef}`, artifactRef && `artifact ${artifactRef}`].filter(Boolean).join(" · ");
  return refs ? `${summary} · ${refs}` : summary;
}

export function mapGovernanceConflictMessage(error: unknown): string {
  const status = extractHttpStatus(error);
  const message = extractServerMessage(error);
  if (status === 409 && /row version/i.test(message)) {
    return STALE_GOVERNANCE_MESSAGE;
  }
  if (message) {
    return message;
  }
  return "操作失败";
}

export function canPerformGovernanceActions(availability: GovernanceAvailability): boolean {
  return availability.mutationsEnabled && !availability.projectReadOnly;
}

export function governancePanelHint(availability: GovernanceAvailability): string | null {
  if (!availability.mutationsEnabled) {
    return "治理变更当前不可用，请联系平台管理员启用可信操作者。";
  }
  if (availability.projectReadOnly) {
    return "项目已禁用，仅可查看记忆与来源。";
  }
  return null;
}

export function formatContentHash(value?: string | null): string {
  return shortenChecksum(value, 12);
}

export type ConfirmPayload = {
  revisionId: string;
  expectedMemoryRowVersion: number;
  expectedRevisionRowVersion: number;
  requestId: string;
};

export type InvalidatePayload = {
  revisionId: string;
  expectedMemoryRowVersion: number;
  expectedRevisionRowVersion: number;
  requestId: string;
};

export type SoftDeletePayload = {
  expectedMemoryRowVersion: number;
  requestId: string;
};

export function buildConfirmPayload(input: ConfirmPayload): ConfirmPayload {
  return {
    revisionId: input.revisionId,
    expectedMemoryRowVersion: input.expectedMemoryRowVersion,
    expectedRevisionRowVersion: input.expectedRevisionRowVersion,
    requestId: input.requestId
  };
}

export function buildInvalidatePayload(input: InvalidatePayload): InvalidatePayload {
  return {
    revisionId: input.revisionId,
    expectedMemoryRowVersion: input.expectedMemoryRowVersion,
    expectedRevisionRowVersion: input.expectedRevisionRowVersion,
    requestId: input.requestId
  };
}

export function buildSoftDeletePayload(input: SoftDeletePayload): SoftDeletePayload {
  return {
    expectedMemoryRowVersion: input.expectedMemoryRowVersion,
    requestId: input.requestId
  };
}
