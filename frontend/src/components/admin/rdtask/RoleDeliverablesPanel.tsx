import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Download,
  ExternalLink,
  FileCode2,
  FileText,
  HelpCircle,
  Layers,
  LoaderCircle,
  ShieldAlert,
  ShieldCheck,
  TerminalSquare
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  TaskFailureRecoveryWorkbench,
  type CaptureTaskActionGuard
} from "@/components/admin/rdtask/TaskFailureRecoveryWorkbench";
import { CodingMeaPanel } from "@/components/admin/rdtask/CodingMeaPanel";
import {
  buildRoleDeliverables,
  type RoleDeliverableView
} from "@/pages/admin/rdtask/roleDeliverableModel";
import type { CodingMeaView } from "@/pages/admin/rdtask/codingMeaModel";
import {
  getStageResult,
  type StageResultResponse
} from "@/services/stageResultService";
import type {
  HostVerificationList,
  RdTask,
  RdTaskQaEvidence,
  RdTaskRolePromptStage,
  RdTaskStageRun,
  TaskMaterial
} from "@/services/rdTaskService";
import type { TaskFailureRecoverySnapshot } from "@/services/taskRetryService";

export interface RoleDeliverablesPanelProps {
  taskId: string;
  stage: RdTaskStageRun;
  promptStage?: RdTaskRolePromptStage;
  qaEvidence: RdTaskQaEvidence[];
  materials: TaskMaterial[];
  hostVerifications?: HostVerificationList | null;
  recovery: TaskFailureRecoverySnapshot | null;
  recoveryLoading: boolean;
  recoveryError: string;
  codingMeaView?: CodingMeaView | null;
  codingMeaLoading?: boolean;
  codingMeaError?: string;
  onNavigateToQaAttempt?: (qaAttemptNo: number) => void;
  onRefresh: () => Promise<void>;
  captureTaskActionGuard: CaptureTaskActionGuard;
}

export function RoleDeliverablesPanel({
  taskId,
  stage,
  promptStage,
  qaEvidence,
  materials,
  hostVerifications,
  recovery,
  recoveryLoading,
  recoveryError,
  codingMeaView = null,
  codingMeaLoading = false,
  codingMeaError = "",
  onNavigateToQaAttempt,
  onRefresh,
  captureTaskActionGuard
}: RoleDeliverablesPanelProps) {
  const [stageResult, setStageResult] = useState<StageResultResponse | null>(null);
  const [stageResultLoading, setStageResultLoading] = useState(false);
  const [stageResultError, setStageResultError] = useState("");
  const [fullResultOpen, setFullResultOpen] = useState(false);

  // 匹配本 stageRun 的 Host Verification
  const matchingHostVerify = useMemo(() => {
    if (!hostVerifications?.runs) return null;
    return hostVerifications.runs.find((run) => run.codingStageRunId === stage.stageRunId) || null;
  }, [hostVerifications?.runs, stage.stageRunId]);

  // 构建纯展示模型
  const view: RoleDeliverableView = useMemo(() => {
    return buildRoleDeliverables({
      taskId,
      role: stage.role,
      stage,
      promptStage,
      qaEvidence,
      hostVerification: matchingHostVerify
        ? {
            runId: matchingHostVerify.runId,
            codingStageRunId: matchingHostVerify.codingStageRunId,
            status: matchingHostVerify.status,
            docsOnly: matchingHostVerify.docsOnly,
            failureCategory: matchingHostVerify.failureCategory,
            errorMessage: matchingHostVerify.errorMessage
          }
        : null,
      taskPr: materials.find((m) => m.materialType === "TASK_PR")
        ? {
            prNumber: Number(materials.find((m) => m.materialType === "TASK_PR")?.materialId) || undefined,
            prUrl: materials.find((m) => m.materialType === "TASK_PR")?.sourceUri,
            workBranch: materials.find((m) => m.materialType === "TASK_PR")?.title
          }
        : null,
      stageResult
    });
  }, [taskId, stage, promptStage, qaEvidence, matchingHostVerify, materials, stageResult]);

  const loadFullResult = async () => {
    if (stageResult || stageResultLoading) return;
    setStageResultLoading(true);
    setStageResultError("");
    try {
      const resp = await getStageResult(taskId, stage.stageRunId);
      setStageResult(resp);
    } catch (err) {
      setStageResultError(err instanceof Error ? err.message : "获取角色完整结果失败");
    } finally {
      setStageResultLoading(false);
    }
  };

  const isCodingRole = stage.role === "CODING_AGENT" || stage.role === "BUG_CODING_AGENT";

  return (
    <div className="space-y-6 px-4 py-5 sm:px-5">
      {/* 1. 结构化产物亮点 (最多 3 项核心产物) */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-2">
          <div>
            <h4 className="text-sm font-semibold text-slate-950">核心产物摘要</h4>
            <p className="mt-0.5 text-xs text-slate-500">
              当前 Attempt 的结构化交付物及关键指标
            </p>
          </div>
          {view.artifactLinks.map((link) => (
            <a
              key={link.name}
              href={link.targetUrl || "#"}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 rounded border border-teal-200 bg-teal-50 px-2 py-1 text-xs font-medium text-teal-800 hover:bg-teal-100"
            >
              <span>{link.name}</span>
              <ExternalLink className="h-3 w-3" />
            </a>
          ))}
        </div>

        {view.unavailableReason ? (
          <div className="border-y border-dashed border-slate-200 py-3 text-xs text-slate-500">
            {view.unavailableReason}
          </div>
        ) : null}

        {view.deliverables.length > 0 ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {view.deliverables.map((item, idx) => (
              <div
                key={`${item.label}-${idx}`}
                className={cn(
                  "border rounded p-3 text-xs space-y-1",
                  item.tone === "success"
                    ? "border-emerald-200 bg-emerald-50/50"
                    : item.tone === "problem"
                    ? "border-rose-200 bg-rose-50/50"
                    : "border-slate-200 bg-slate-50/60"
                )}
              >
                <div className="flex items-center justify-between text-slate-500">
                  <span>{item.label}</span>
                  {item.badge ? (
                    <Badge variant="outline" className="border-emerald-300 bg-emerald-50 text-emerald-800 text-[10px]">
                      {item.badge}
                    </Badge>
                  ) : null}
                </div>
                <div className="text-slate-900 font-medium break-words leading-relaxed text-xs">
                  {item.value}
                </div>
              </div>
            ))}
          </div>
        ) : null}

        {view.hasMoreDeliverables ? (
          <p className="text-[11px] text-slate-500">
            已默认展示前 3 项核心交付项，更多内容请查阅原始角色结果。
          </p>
        ) : null}

        {view.executionSummary ? (
          <div className="rounded border border-slate-200 bg-white p-3 text-xs text-slate-700 leading-relaxed">
            <span className="font-semibold text-slate-900">执行概述：</span>
            {view.executionSummary}
          </div>
        ) : null}
      </section>

      {/* 2. 阻断项与验收缺口（如有） */}
      {view.keyGaps.length > 0 ? (
        <section className="rounded border border-rose-200 bg-rose-50/70 p-3.5 space-y-2">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-rose-900">
            <ShieldAlert className="h-4 w-4 shrink-0 text-rose-600" />
            <span>发现阻断项 / 验收缺口（{view.keyGaps.length}）</span>
          </div>
          <ul className="space-y-1 text-xs text-rose-800 list-disc list-inside">
            {view.keyGaps.map((gap, idx) => (
              <li key={idx} className="break-words leading-relaxed">
                {gap}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {/* 3. 执行检查对比：Agent 自报 vs Host 审计核验 */}
      {(view.reportedChecks.length > 0 || view.auditedChecks.length > 0) ? (
        <section className="space-y-3">
          <div className="flex items-center justify-between border-b border-slate-200 pb-2">
            <div>
              <h4 className="text-sm font-semibold text-slate-950">检查与验证对比</h4>
              <p className="mt-0.5 text-xs text-slate-500">
                Agent 自报执行情况与 Host 审计结论严格隔离
              </p>
            </div>
            <div className="flex items-center gap-2 text-xs">
              <span className="inline-flex items-center gap-1 text-slate-600">
                <span className="h-2 w-2 rounded-full bg-teal-500" />
                Agent 自报
              </span>
              <span className="inline-flex items-center gap-1 text-slate-600">
                <span className="h-2 w-2 rounded-full bg-emerald-500" />
                Host 审计通过
              </span>
            </div>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            {/* Agent 自报 */}
            <div className="border border-slate-200 rounded p-3 bg-white space-y-2.5">
              <div className="flex items-center justify-between text-xs font-medium text-slate-700 border-b border-slate-100 pb-1.5">
                <span>Agent 自报结果（{view.reportedChecks.length}）</span>
                <Badge variant="outline" className="border-slate-200 bg-slate-50 text-[10px] text-slate-600">
                  未经验证
                </Badge>
              </div>
              {view.reportedChecks.length === 0 ? (
                <div className="py-4 text-center text-xs text-slate-400">暂无自报执行项</div>
              ) : (
                <div className="divide-y divide-slate-100 max-h-60 overflow-y-auto">
                  {view.reportedChecks.map((check, idx) => (
                    <div key={idx} className="py-2 text-xs space-y-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium text-slate-800 truncate">{check.name}</span>
                        <Badge
                          variant="outline"
                          className={cn(
                            "text-[10px]",
                            check.status === "PASSED" || check.status === "PASS"
                              ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                              : "border-rose-200 bg-rose-50 text-rose-700"
                          )}
                        >
                          {check.status}
                        </Badge>
                      </div>
                      {check.command ? (
                        <code className="block overflow-x-auto text-[10px] text-slate-500 bg-slate-50 p-1 rounded">
                          {check.command}
                        </code>
                      ) : null}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Host 审计核验 */}
            <div className="border border-slate-200 rounded p-3 bg-white space-y-2.5">
              <div className="flex items-center justify-between text-xs font-medium text-slate-700 border-b border-slate-100 pb-1.5">
                <span>Host 审计核验（{view.auditedChecks.length}）</span>
                <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-[10px] text-emerald-800">
                  已审计生效
                </Badge>
              </div>
              {view.auditedChecks.length === 0 ? (
                <div className="py-4 text-center text-xs text-slate-400">
                  当前尚无通过 Host 审计归档的验收项
                </div>
              ) : (
                <div className="divide-y divide-slate-100 max-h-60 overflow-y-auto">
                  {view.auditedChecks.map((check, idx) => (
                    <div key={idx} className="py-2 text-xs space-y-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium text-slate-900 truncate">
                          {check.recordId} · {check.title}
                        </span>
                        <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700 text-[10px]">
                          {check.status}
                        </Badge>
                      </div>
                      {check.evidenceIds.length > 0 ? (
                        <div className="text-[10px] text-slate-500">
                          证据引用: {check.evidenceIds.join(", ")}
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </section>
      ) : null}

      {/* 4. 关键证据 (默认展示前 5 项) */}
      <section className="space-y-3">
        <div className="flex items-center justify-between border-b border-slate-200 pb-2">
          <div>
            <h4 className="text-sm font-semibold text-slate-950">关键证据</h4>
            <p className="mt-0.5 text-xs text-slate-500">
              精确绑定到当前 Attempt 的测试与运行证据（共 {view.totalEvidenceCount} 项）
            </p>
          </div>
        </div>

        {view.keyEvidence.length === 0 ? (
          <div className="border-y border-dashed border-slate-200 py-3 text-xs text-slate-500 text-center">
            当前 Attempt 暂无可展示的关键证据。
          </div>
        ) : (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {view.keyEvidence.map((ev) => (
              <div
                key={ev.id}
                className="flex items-center justify-between gap-2 border border-slate-200 rounded p-2.5 bg-white text-xs"
              >
                <div className="min-w-0 flex items-center gap-2">
                  <TerminalSquare className="h-4 w-4 shrink-0 text-slate-500" />
                  <div className="truncate">
                    <div className="font-medium text-slate-800 truncate" title={ev.name}>
                      {ev.name}
                    </div>
                    <div className="text-[10px] text-slate-400 font-mono truncate">{ev.id}</div>
                  </div>
                </div>
                {ev.contentUrl ? (
                  <Button asChild variant="ghost" size="sm" className="h-7 w-7 p-0 shrink-0">
                    <a href={ev.contentUrl} target="_blank" rel="noreferrer" title="查看证据">
                      <ExternalLink className="h-3.5 w-3.5 text-teal-700" />
                    </a>
                  </Button>
                ) : null}
              </div>
            ))}
          </div>
        )}

        {view.hasMoreEvidence ? (
          <p className="text-[11px] text-slate-500">
            已默认展示前 5 项关键证据，更多证据可在「Prompt」或「运行记录」中查看。
          </p>
        ) : null}
      </section>

      {/* 5. Coding 内 MEA 协作（仅挂载在 Coding 角色下） */}
      {isCodingRole ? (
        <CodingMeaPanel
          taskId={taskId}
          meaView={codingMeaView}
          loading={codingMeaLoading}
          error={codingMeaError}
          onNavigateToQaAttempt={onNavigateToQaAttempt}
        />
      ) : null}

      {/* 6. 原始角色结果 & 完整结果只读读取 */}
      <section className="border border-slate-200 rounded bg-white">
        <div className="flex flex-wrap items-center justify-between gap-2 p-3 bg-slate-50/70 border-b border-slate-200">
          <button
            type="button"
            onClick={() => setFullResultOpen((prev) => !prev)}
            className="flex items-center gap-1.5 text-xs font-semibold text-slate-800 hover:text-slate-950"
          >
            <FileCode2 className="h-4 w-4 text-slate-600" />
            <span>原始角色结果与持久化产物</span>
          </button>
          <div className="flex items-center gap-2">
            {!stageResult && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                disabled={stageResultLoading}
                onClick={() => {
                  setFullResultOpen(true);
                  void loadFullResult();
                }}
              >
                {stageResultLoading ? "正在读取..." : "查看完整产物"}
              </Button>
            )}
            {stageResult?.downloadPath && stageResult.source === "FINALIZATION_RESULT" ? (
              <Button asChild variant="outline" size="sm" className="h-7 text-xs">
                <a href={stageResult.downloadPath} download>
                  <Download className="h-3.5 w-3.5 mr-1" />
                  下载脱敏 JSON
                </a>
              </Button>
            ) : null}
          </div>
        </div>

        {fullResultOpen ? (
          <div className="p-3 space-y-3">
            {stageResultLoading ? (
              <div className="flex items-center gap-2 py-3 text-xs text-slate-500">
                <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" />
                正在加载角色持久化结果...
              </div>
            ) : null}

            {stageResultError ? (
              <div className="border-l-2 border-amber-500 bg-amber-50 p-2 text-xs text-amber-900">
                {stageResultError}
              </div>
            ) : null}

            {stageResult ? (
              <div className="text-xs space-y-2">
                <div className="flex flex-wrap gap-3 text-[11px] text-slate-500 border-b border-slate-100 pb-2">
                  <span>来源：{stageResult.source}</span>
                  {stageResult.commandId ? <span>Command ID：{stageResult.commandId}</span> : null}
                  {stageResult.finalizationId ? <span>Finalization ID：{stageResult.finalizationId}</span> : null}
                  {stageResult.contentSha256 ? <span>SHA256：{stageResult.contentSha256.slice(0, 12)}...</span> : null}
                </div>
                {stageResult.source === "ARTIFACT_PREVIEW" ? (
                  <p className="text-[11px] text-amber-700">
                    当前结果为预览截断，未生成完整下载文件。
                  </p>
                ) : null}
                {stageResult.content ? (
                  <pre className="max-h-80 overflow-auto rounded bg-slate-950 p-3 text-xs leading-5 text-slate-100 font-mono">
                    {formatJson(stageResult.content)}
                  </pre>
                ) : (
                  <p className="text-xs text-slate-400 py-2">无结果正文内容</p>
                )}
              </div>
            ) : view.rawResultPreview ? (
              <div className="text-xs space-y-2">
                <p className="text-[11px] text-slate-500">
                  当前仅显示阶段执行上报预览。点击上方「查看完整产物」可查询持久化记录。
                </p>
                <pre className="max-h-80 overflow-auto rounded bg-slate-950 p-3 text-xs leading-5 text-slate-100 font-mono">
                  {formatJson(view.rawResultPreview)}
                </pre>
              </div>
            ) : (
              <p className="text-xs text-slate-400 py-2">暂无原始角色结果数据</p>
            )}
          </div>
        ) : null}
      </section>

      {/* 7. 任务恢复工作台（如发生失败） */}
      {recovery || recoveryLoading || recoveryError ? (
        <TaskFailureRecoveryWorkbench
          key={`${taskId}-${stage.stageRunId}`}
          task={{ taskId } as RdTask}
          materials={materials}
          snapshot={recovery}
          loading={recoveryLoading}
          error={recoveryError}
          onRefresh={onRefresh}
          captureTaskActionGuard={captureTaskActionGuard}
        />
      ) : null}
    </div>
  );
}

function formatJson(value: string) {
  try {
    let parsed: unknown = JSON.parse(value);
    if (typeof parsed === "string") parsed = JSON.parse(parsed);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return value;
  }
}
