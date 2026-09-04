export type AuditedRecordStatus = "PENDING" | "COMPLETED" | "BLOCKED" | "UNTRUSTED";

export interface AuditedEvidenceRefView {
  auditRunId: string;
  sourceKind: string;
  uri: string;
  sha256: string;
}

export interface AuditedRecordView {
  id: string;
  kind: string;
  blocking: boolean;
  text: string;
  status: string;
  evidenceRefs: AuditedEvidenceRefView[];
  sourceStageRunId: string;
  blockedReason: string;
}

const STATUS_LABELS: Record<string, string> = {
  PENDING: "待审计",
  COMPLETED: "已完成",
  BLOCKED: "已阻断",
  UNTRUSTED: "未核验"
};

const STATUS_CLASSES: Record<string, string> = {
  PENDING: "border-slate-200 bg-slate-50 text-slate-700",
  COMPLETED: "border-green-300 bg-green-100 text-green-800",
  BLOCKED: "border-rose-200 bg-rose-50 text-rose-800",
  UNTRUSTED: "border-amber-200 bg-amber-50 text-amber-800"
};

/** Maps an audited-record status to the Chinese badge label. */
export function auditedRecordStatusLabel(status: string): string {
  const key = (status || "").trim().toUpperCase();
  return STATUS_LABELS[key] || status || "未知";
}

/** Tailwind classes for an audited-record status badge. */
export function auditedRecordStatusClass(status: string): string {
  const key = (status || "").trim().toUpperCase();
  return STATUS_CLASSES[key] || "border-slate-200 bg-slate-50 text-slate-600";
}

/** Completed-record count over the full record set, labeled 「已审计 n/m」. */
export function auditedCoverage(records: AuditedRecordView[] | undefined): {
  completed: number;
  total: number;
  label: string;
} {
  const list = records || [];
  const completed = list.filter((record) => record.status === "COMPLETED").length;
  return {
    completed,
    total: list.length,
    label: `已审计 ${completed}/${list.length}`
  };
}

/** Blocking records that are not yet COMPLETED. */
export function auditedGapIds(records: AuditedRecordView[] | undefined): string[] {
  return (records || [])
    .filter((record) => record.blocking && record.status !== "COMPLETED")
    .map((record) => record.id);
}

/**
 * Returns a safe http(s) evidence link, or null for non-http / loopback URIs.
 * Non-http durable URIs are rendered as code, not anchors.
 */
export function evidenceLink(uri: string): { href: string; label: string } | null {
  const value = (uri || "").trim();
  if (!value) return null;
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
    const host = parsed.hostname.toLowerCase();
    if (host === "localhost" || host === "127.0.0.1" || host === "::1" || host.endsWith(".localhost")) {
      return null;
    }
    return { href: parsed.toString(), label: value };
  } catch {
    return null;
  }
}
