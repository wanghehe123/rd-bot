import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import {
  auditedCoverage,
  auditedGapIds,
  auditedRecordStatusClass,
  auditedRecordStatusLabel,
  evidenceLink
} from "@/pages/admin/rdtask/auditedTaskStatePresentation";
import type { AuditedTaskState, AuditRunList } from "@/services/rdTaskService";
import { ShieldCheck } from "lucide-react";

interface AuditedTaskStateCardProps {
  state: AuditedTaskState | null;
  runs: AuditRunList | null;
  loading: boolean;
  error: string;
}

export function AuditedTaskStateCard({ state, runs, loading, error }: AuditedTaskStateCardProps) {
  const coverage = auditedCoverage(state?.records);
  const gaps = auditedGapIds(state?.records);

  if (error) {
    return (
      <Card className="border-slate-200 shadow-sm" aria-label="已审计状态面板">
        <CardHeader className="px-4 py-4 sm:px-5">
          <CardTitle className="text-base font-semibold text-slate-950 flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-teal-700" />
            已审计状态
          </CardTitle>
        </CardHeader>
        <CardContent className="px-4 pb-4 sm:px-5">
          <div className="rounded-md border border-amber-200 bg-amber-50/80 px-3 py-2 text-xs text-amber-900">
            {error.includes("404") || error.toLowerCase().includes("not found")
              ? "此环境尚未启用已审计状态"
              : `已审计状态读取提示：${error}`}
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="border-slate-200 shadow-sm" aria-label="已审计状态面板">
      <CardHeader className="border-b border-slate-100 px-4 py-4 sm:px-5">
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle className="text-base font-semibold text-slate-950 flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-teal-700" />
            已审计状态
          </CardTitle>
          {state?.present ? (
            <Badge variant="outline" className="border-teal-200 bg-teal-50 font-medium text-teal-800">
              {coverage.label}
            </Badge>
          ) : null}
        </div>
        <CardDescription className="mt-1 text-xs text-slate-600">
          只展示 Host 审计记录与证据引用。Agent 自报结果不会出现在这里。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4 px-4 py-4 sm:px-5">
        {loading ? (
          <div className="text-xs text-slate-500">正在加载已审计状态…</div>
        ) : !state?.present ? (
          <div className="rounded-md border border-dashed border-slate-200 bg-slate-50/50 py-8 text-center text-xs text-slate-500">
            尚未初始化已审计状态（策略放行进入执行后创建）。
          </div>
        ) : (
          <>
            {gaps.length > 0 ? (
              <div className="rounded-md border border-rose-200 bg-rose-50/80 px-3 py-2 text-xs text-rose-900">
                缺口：{gaps.join("、")}
              </div>
            ) : (
              <div className="rounded-md border border-emerald-200 bg-emerald-50/80 px-3 py-2 text-xs text-emerald-900">
                阻断记录均已完成
              </div>
            )}
            <div className="overflow-x-auto rounded-md border border-slate-200">
              <table className="min-w-full text-left text-xs">
                <thead className="bg-slate-50 text-slate-500">
                  <tr>
                    <th className="px-3 py-2 font-medium">记录</th>
                    <th className="px-3 py-2 font-medium">状态</th>
                    <th className="px-3 py-2 font-medium">证据</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {(state.records || []).map((record) => (
                    <tr key={record.id}>
                      <td className="px-3 py-2">
                        <code className="font-mono text-slate-800">{record.id}</code>
                        {record.text ? (
                          <div className="mt-0.5 text-slate-500">{record.text}</div>
                        ) : null}
                      </td>
                      <td className="px-3 py-2">
                        <Badge
                          variant="outline"
                          className={cn("font-medium", auditedRecordStatusClass(record.status))}
                        >
                          {auditedRecordStatusLabel(record.status)}
                        </Badge>
                      </td>
                      <td className="px-3 py-2">
                        {(record.evidenceRefs || []).length === 0 ? (
                          <span className="text-slate-400">—</span>
                        ) : (
                          <ul className="space-y-1">
                            {record.evidenceRefs.map((ref, index) => {
                              const link = evidenceLink(ref.uri);
                              return (
                                <li key={`${record.id}-${index}`}>
                                  {link ? (
                                    <a
                                      href={link.href}
                                      className="text-teal-700 underline underline-offset-2"
                                      target="_blank"
                                      rel="noreferrer"
                                    >
                                      {link.label}
                                    </a>
                                  ) : (
                                    <code className="break-all font-mono text-slate-700">{ref.uri}</code>
                                  )}
                                </li>
                              );
                            })}
                          </ul>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="space-y-2">
              <div className="text-xs font-semibold text-slate-700">审计轮次</div>
              {(runs?.runs || []).length === 0 ? (
                <div className="text-xs text-slate-500">尚无 AuditRun。</div>
              ) : (
                <ul className="space-y-2">
                  {(runs?.runs || []).map((run) => (
                    <li
                      key={run.auditRunId}
                      className="rounded-md border border-slate-200 bg-slate-50/70 px-3 py-2 text-xs text-slate-700"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <code className="font-mono text-slate-900">{run.auditRunId}</code>
                        <span>{run.completion}</span>
                        <span>{run.integrity}</span>
                        <span>{run.contractAudit}</span>
                      </div>
                      {run.blockers.length > 0 ? (
                        <div className="mt-1 text-rose-800">blockers: {run.blockers.join("、")}</div>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
