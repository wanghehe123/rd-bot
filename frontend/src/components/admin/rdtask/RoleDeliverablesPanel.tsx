import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Download,
  ExternalLink,
  FileCode2,
  LoaderCircle,
  ShieldAlert,
  TerminalSquare
} from "lucide-react";
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
  type RoleDeliverableView,
  type TaskPrInput
} from "@/pages/admin/rdtask/roleDeliverableModel";
import type { CodingMeaView } from "@/pages/admin/rdtask/codingMeaModel";
import {
  getCompleteStageResult,
  shouldAutoLoadStageResult,
  stageResultSelectionSignature,
  type StageResultResponse
} from "@/services/stageResultService";
import type {
  AuditedTaskState,
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
  /** Host 审计状态（来自 /audited-state），驱动 QA 面板的 auditedChecks 与 keyGaps。 */
  auditedState?: AuditedTaskState | null;
  /** 任务级 PR 信息：以 RdTask.pullRequestUrl 为权威来源。 */
  taskPr?: TaskPrInput | null;
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
  auditedState = null,
  taskPr = null,
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
  const [summaryExpanded, setSummaryExpanded] = useState(false);
  const [allDeliverablesOpen, setAllDeliverablesOpen] = useState(false);
  const [allEvidenceOpen, setAllEvidenceOpen] = useState(false);
  const [checkDetailsOpen, setCheckDetailsOpen] = useState(false);

  // Attempt 切换（stageRunId 变化）时必须清空上一 Attempt 的完整产物与本阶段展开状态，
  // 并令在途的 getStageResult 请求过期，防止慢响应覆盖新 Attempt 的数据。
  const fullResultTokenRef = useRef(0);
  const autoLoadedIdentityRef = useRef<string | null>(null);
  const autoAttemptedIdentityRef = useRef<string | null>(null);
  const stageResultIdentity = stageResultSelectionSignature({
    taskId,
    stageRunId: stage.stageRunId,
    status: stage.status,
    resultPreview: stage.resultPreview
  });
  const shouldReadStageResult = shouldAutoLoadStageResult(stage);
  const [stageResultStateIdentity, setStageResultStateIdentity] = useState(stageResultIdentity);
  useEffect(() => {
    fullResultTokenRef.current += 1;
    setStageResult(null);
    setStageResultError("");
    setStageResultLoading(false);
    setFullResultOpen(false);
    setSummaryExpanded(false);
    setAllDeliverablesOpen(false);
    setAllEvidenceOpen(false);
    setCheckDetailsOpen(false);
    autoLoadedIdentityRef.current = null;
    autoAttemptedIdentityRef.current = null;
    setStageResultStateIdentity(stageResultIdentity);
    return () => {
      fullResultTokenRef.current += 1;
      autoLoadedIdentityRef.current = null;
      autoAttemptedIdentityRef.current = null;
    };
  }, [stageResultIdentity]);

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
      auditedState,
      taskPr,
      stageResult
    });
  }, [taskId, stage, promptStage, qaEvidence, matchingHostVerify, auditedState, taskPr, stageResult]);

  const loadFullResult = useCallback(async (): Promise<boolean> => {
    if (stageResult || stageResultLoading) return false;
    const token = ++fullResultTokenRef.current;
    setStageResultLoading(true);
    setStageResultError("");
    try {
      const resp = await getCompleteStageResult(
        taskId,
        stage.stageRunId,
        () => token === fullResultTokenRef.current
      );
      if (token !== fullResultTokenRef.current) return false; // 过期响应丢弃
      if (!resp) return false;
      setStageResult(resp);
      setStageResultStateIdentity(stageResultIdentity);
      return true;
    } catch (err) {
      if (token !== fullResultTokenRef.current) return false;
      setStageResultError(err instanceof Error ? err.message : "获取角色完整结果失败");
      return false;
    } finally {
      if (token === fullResultTokenRef.current) setStageResultLoading(false);
    }
  }, [stage.stageRunId, stageResult, stageResultIdentity, stageResultLoading, taskId]);

  // 面板只为当前选中的角色/Attempt读取一次结构化结果；阶段状态或结果预览变化后重新触发。
  useEffect(() => {
    // reset effect 尚未提交时，旧状态不能阻止新签名的首个读取。
    if (!shouldReadStageResult || stageResultStateIdentity !== stageResultIdentity) return;
    if (
      autoLoadedIdentityRef.current === stageResultIdentity
      || autoAttemptedIdentityRef.current === stageResultIdentity
    ) return;
    autoAttemptedIdentityRef.current = stageResultIdentity;
    void loadFullResult().then((loaded) => {
      if (loaded) autoLoadedIdentityRef.current = stageResultIdentity;
    });
  }, [loadFullResult, shouldReadStageResult, stageResultIdentity, stageResultStateIdentity]);

  const isCodingRole = stage.role === "CODING_AGENT" || stage.role === "BUG_CODING_AGENT";
  const reportedSummary = useMemo(() => {
    const passed = view.reportedChecks.filter(
      (check) => check.status === "PASSED" || check.status === "PASS"
    ).length;
    return { total: view.reportedChecks.length, passed, other: view.reportedChecks.length - passed };
  }, [view.reportedChecks]);

  // 首屏证据/产物快捷入口：真实跳转到本页对应区域并展开，不编造链接
  const jumpToEvidence = useCallback(() => {
    if (view.totalEvidenceCount > 0) setAllEvidenceOpen(true);
    document.getElementById(`stage-evidence-${stage.stageRunId}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [view.totalEvidenceCount, stage.stageRunId]);
  const jumpToFullResult = useCallback(() => {
    setFullResultOpen(true);
    document.getElementById(`stage-raw-result-${stage.stageRunId}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [stage.stageRunId]);

  return (
    <div className="space-y-4 px-4 py-3 sm:px-5">
      {/* 1. 当前阻断 / 验收缺口（仅有问题时渲染；任务级阻断与所选 Attempt 缺口分开标注） */}
      {view.keyGaps.length > 0 || view.taskHeadGaps.length > 0 ? (
        <section className="rounded border border-rose-200 bg-rose-50/70 p-3.5 space-y-2">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-rose-900">
            <ShieldAlert className="h-4 w-4 shrink-0 text-rose-600" />
            <span>当前阻断 / 验收缺口（{view.keyGaps.length + view.taskHeadGaps.length}）</span>
          </div>
          {view.keyGaps.length > 0 ? (
            <ul className="space-y-1 text-xs text-rose-800 list-disc list-inside">
              {view.keyGaps.map((gap, idx) => (
                <li key={idx} className="break-words leading-relaxed">
                  {gap}
                </li>
              ))}
            </ul>
          ) : null}
          {view.taskHeadGaps.length > 0 ? (
            <div className="rounded border border-amber-200 bg-amber-50/80 p-2 text-xs">
              <div className="font-medium text-amber-900">
                任务当前阻断，不计入所选 Attempt（{view.taskHeadGaps.length}）
              </div>
              {view.taskHeadGaps.map((record) => (
                <div key={record.id} className="mt-1.5 space-y-0.5 text-amber-900">
                  <div className="break-words">{record.id} · {record.text} · {record.status}</div>
                  <div className="text-[10px] text-amber-800">
                    来源: {String(record.sourceStageRunId || "").trim() || "未记录 sourceStageRunId"}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </section>
      ) : null}

      {/* 2. 结构化产物摘要（默认最多三项、每项两行；展开后查看全部） */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-2">
          <div>
            <h4 className="text-sm font-semibold text-slate-950">产物摘要</h4>
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
          {view.totalEvidenceCount > 0 ? (
            <button
              type="button"
              onClick={jumpToEvidence}
              className="inline-flex items-center gap-1 rounded border border-teal-200 bg-teal-50 px-2 py-1 text-xs font-medium text-teal-800 hover:bg-teal-100"
            >
              <TerminalSquare className="h-3 w-3" />
              <span>关键证据（{view.totalEvidenceCount}）</span>
            </button>
          ) : null}
          {!view.unavailableReason ? (
            <button
              type="button"
              onClick={jumpToFullResult}
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              <FileCode2 className="h-3 w-3" />
              <span>完整产物</span>
            </button>
          ) : null}
        </div>

        {view.unavailableReason ? (
          <div className="border-y border-dashed border-slate-200 py-3 text-xs text-slate-500">
            {view.unavailableReason}
          </div>
        ) : null}

        {stageResultError ? (
          <div className="border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-xs text-amber-900">
            结构化产物暂不可读：{stageResultError}
          </div>
        ) : null}

        {stageResultLoading ? (
          <div className="flex items-center gap-2 border-y border-dashed border-slate-200 py-3 text-xs text-slate-500">
            <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" />
            正在读取结构化产物...
          </div>
        ) : null}

        {view.deliverables.length > 0 ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {view.deliverables.map((item, idx) => (
              <div
                key={`${item.label}-${idx}`}
                className={cn(
                  "border rounded p-2.5 text-xs space-y-1",
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
                <div className="text-slate-900 font-medium break-words leading-relaxed text-xs line-clamp-2">
                  {item.value}
                </div>
              </div>
            ))}
          </div>
        ) : null}

        {view.allDeliverables.length > view.deliverables.length || allDeliverablesOpen ? (
          <button
            type="button"
            aria-expanded={allDeliverablesOpen}
            onClick={() => setAllDeliverablesOpen((prev) => !prev)}
            className="inline-flex items-center gap-1 text-xs font-medium text-teal-700 hover:text-teal-900"
          >
            {allDeliverablesOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            {allDeliverablesOpen ? "收起全部产物" : `查看全部产物（${view.allDeliverables.length} 项）`}
          </button>
        ) : null}

        {allDeliverablesOpen && view.allDeliverables.length > view.deliverables.length ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {view.allDeliverables.map((item, idx) => (
              <div
                key={`all-${item.label}-${idx}`}
                className={cn(
                  "border rounded p-2.5 text-xs space-y-1",
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

        {view.executionSummary ? (
          <div className="rounded border border-slate-200 bg-white p-3 text-xs text-slate-700 leading-relaxed">
            <span className="font-semibold text-slate-900">执行概述：</span>
            <span className={summaryExpanded ? "break-words" : "line-clamp-2 break-words"}>
              {view.executionSummary}
            </span>
            <button
              type="button"
              aria-expanded={summaryExpanded}
              onClick={() => setSummaryExpanded((value) => !value)}
              className="mt-1.5 block text-[11px] font-medium text-teal-700 hover:text-teal-900"
            >
              {summaryExpanded ? "收起执行概述" : "展开执行概述"}
            </button>
          </div>
        ) : null}
      </section>

      {/* 3. Coding 内 MEA 协作（仅挂载在 Coding 角色下；位于证据与折叠明细之前） */}
      {isCodingRole ? (
        <CodingMeaPanel
          taskId={taskId}
          meaView={codingMeaView}
          loading={codingMeaLoading}
          error={codingMeaError}
          onNavigateToQaAttempt={onNavigateToQaAttempt}
        />
      ) : null}

      {/* 4. 关键证据（默认前 5 项 + 当前页内查看全部） */}
      <section id={`stage-evidence-${stage.stageRunId}`} className="space-y-3 scroll-mt-4">
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

        {view.allEvidence.length > view.keyEvidence.length || allEvidenceOpen ? (
          <button
            type="button"
            aria-expanded={allEvidenceOpen}
            onClick={() => setAllEvidenceOpen((prev) => !prev)}
            className="inline-flex items-center gap-1 text-xs font-medium text-teal-700 hover:text-teal-900"
          >
            {allEvidenceOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            {allEvidenceOpen ? "收起全部证据" : `查看全部证据（${view.allEvidence.length} 项）`}
          </button>
        ) : null}

        {allEvidenceOpen && view.allEvidence.length > view.keyEvidence.length ? (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {view.allEvidence.map((ev) => (
              <div
                key={`all-${ev.id}`}
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
        ) : null}
      </section>

      {/* 5. 检查与验证对比：计数常显，成功明细与命令/URI 默认折叠 */}
      {(view.reportedChecks.length > 0 || view.auditedChecks.length > 0) ? (
        <section className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-2">
            <div>
              <h4 className="text-sm font-semibold text-slate-950">检查与验证对比</h4>
              <p className="mt-0.5 text-xs text-slate-500">
                Agent 自报执行情况与 Host 审计结论严格隔离
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-3 text-xs">
              <span className="text-slate-600">
                Agent 自报 <strong className="text-slate-900">{reportedSummary.total}</strong> 项
                {reportedSummary.total > 0 ? (
                  <span className="text-slate-500">（通过 {reportedSummary.passed}）</span>
                ) : null}
              </span>
              <span className="text-slate-600">
                任务最新审计已完成 <strong className="text-slate-900">{view.auditedChecks.length}</strong> 项
              </span>
              <button
                type="button"
                aria-expanded={checkDetailsOpen}
                onClick={() => setCheckDetailsOpen((prev) => !prev)}
                className="inline-flex items-center gap-1 font-medium text-teal-700 hover:text-teal-900"
              >
                {checkDetailsOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                {checkDetailsOpen ? "收起检查明细" : "查看检查明细"}
              </button>
            </div>
          </div>

          {checkDetailsOpen ? (
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
                  <span>Host 审计核验（任务最新审计）</span>
                  <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-[10px] text-emerald-800">
                    任务级当前状态
                  </Badge>
                </div>
                <p className="text-[10px] text-slate-500">
                  以下记录来自任务最新 Host 审计；来源按后端 sourceStageRunId 展示，不代表全部属于当前 Attempt。
                </p>
                {view.auditedChecks.length === 0 ? (
                  <div className="py-4 text-center text-xs text-slate-400">
                    当前任务最新审计尚无已完成记录
                  </div>
                ) : (
                  <div className="divide-y divide-slate-100 max-h-60 overflow-y-auto">
                    {view.auditedChecks.map((check, idx) => (
                      <div key={idx} className="py-2 text-xs space-y-1">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium text-slate-900 truncate" title={check.text}>
                            {check.id} · {check.text}
                          </span>
                          <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700 text-[10px]">
                            {check.status}
                          </Badge>
                        </div>
                        <div className="text-[10px] text-slate-500">
                          来源: {String(check.sourceStageRunId || "").trim() || "未记录 sourceStageRunId"}
                        </div>
                        {check.evidenceRefs.length > 0 ? (
                          <div className="text-[10px] text-slate-500 break-all">
                            证据引用: {check.evidenceRefs.map((ref) => `${ref.sourceKind} ${ref.uri}`).join(", ")}
                          </div>
                        ) : null}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ) : null}
        </section>
      ) : null}

      {/* 6. 原始角色结果 & 完整结果只读读取（默认折叠；完整读取依然自动完成） */}
      <section id={`stage-raw-result-${stage.stageRunId}`} className="border border-slate-200 rounded bg-white scroll-mt-4">
        <div className="flex flex-wrap items-center justify-between gap-2 p-3 bg-slate-50/70 border-b border-slate-200">
          <button
            type="button"
            aria-expanded={fullResultOpen}
            onClick={() => setFullResultOpen((prev) => !prev)}
            className="flex items-center gap-1.5 text-xs font-semibold text-slate-800 hover:text-slate-950"
          >
            {fullResultOpen ? <ChevronDown className="h-4 w-4 text-slate-600" /> : <ChevronRight className="h-4 w-4 text-slate-600" />}
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
