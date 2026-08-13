import { OpenVikingStatusBadge } from "@/components/admin/knowledge/OpenVikingStatusBadge";
import type { OpenVikingOperation } from "@/services/openVikingKnowledgeService";
import {
  displaySafeError,
  formatEpochMillis,
  operationStatusLabel,
  sortOperationsNewestFirst
} from "@/services/openVikingKnowledgePresentation";

export function OpenVikingOperationTimeline({ operations }: { operations: OpenVikingOperation[] }) {
  const rows = sortOperationsNewestFirst(operations);
  if (rows.length === 0) {
    return <div className="py-6 text-center text-sm text-muted-foreground">暂无操作记录</div>;
  }
  return (
    <ol className="space-y-3">
      {rows.map((operation) => (
        <li key={operation.eventId} className="rounded-lg border border-slate-200 bg-slate-50/60 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2">
              <OpenVikingStatusBadge status={operation.status} kind="operation" />
              <span className="truncate text-sm font-medium text-slate-800">
                {operation.operationType || "UNKNOWN"}
              </span>
            </div>
            <span className="text-xs tabular-nums text-muted-foreground">
              {formatEpochMillis(operation.updatedAtEpochMillis)}
            </span>
          </div>
          <div className="mt-2 grid gap-1 text-xs text-muted-foreground sm:grid-cols-2">
            <div>版本 {operation.syncVersion || "—"}</div>
            <div>
              尝试 {operation.attemptCount}/{operation.maxAttempts || "—"}
            </div>
            <div className="truncate sm:col-span-2" title={operation.remoteTaskId || ""}>
              任务 {operation.remoteTaskId || "—"}
            </div>
            <div className="sm:col-span-2">
              {operationStatusLabel(operation.status)}
              {" · "}
              {displaySafeError(operation.lastErrorCode, operation.lastErrorMessage)}
            </div>
          </div>
        </li>
      ))}
    </ol>
  );
}
