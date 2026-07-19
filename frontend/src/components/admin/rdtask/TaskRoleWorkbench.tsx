import { lazy, Suspense, useEffect, useMemo } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Download,
  ExternalLink,
  FileText,
  GitBranch,
  LoaderCircle,
  Search,
  TerminalSquare
} from "lucide-react";

import {
  TaskFailureRecoveryWorkbench,
  type CaptureTaskActionGuard
} from "@/components/admin/rdtask/TaskFailureRecoveryWorkbench";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import {
  buildRoleWorkbench,
  isActiveStageStatus,
  isFailedStageStatus,
  isRetryableRequirementTaskStatus,
  projectRoleResult,
  selectRoleAttempt
} from "@/pages/admin/rdtask/roleWorkbenchModel";
import type {
  RdTask,
  RdTaskExecutionOverview,
  RdTaskQaEvidence,
  RdTaskRolePromptStage,
  RdTaskStageRun,
  TaskMaterial
} from "@/services/rdTaskService";
import { retrievalRunStatusClass, retrievalRunStatusLabel, type RetrievalRun } from "@/services/retrievalRunService";
import type { TaskFailureRecoverySnapshot } from "@/services/taskRetryService";

const MarkdownRenderer = lazy(() => (
  import("@/components/chat/MarkdownRenderer").then((module) => ({ default: module.MarkdownRenderer }))
));

const ROLE_LABEL: Record<string, string> = {
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证",
  BUG_EVIDENCE_COLLECTOR: "证据收集",
  BUG_RAG_RETRIEVER: "RAG 检索",
  BUG_ACCEPTANCE_PLANNER: "验收规划",
  BUG_CODING_AGENT: "修复执行"
};

const STATUS_LABEL: Record<string, string> = {
  PENDING: "待开始",
  CONTEXT_READY: "上下文就绪",
  DISPATCHING: "调度中",
  RUNNING: "执行中",
  RESULT_COLLECTING: "收集结果",
  VERIFYING: "验证中",
  SUCCEEDED: "已成功",
  FAILED_RETRYABLE: "可重试失败",
  FAILED_NEEDS_HUMAN: "需人工处理",
  SKIPPED: "已跳过",
  CANCELLED: "已取消",
  RECOVERING: "恢复中"
};

const STATUS_CLASS: Record<string, string> = {
  PENDING: "border-slate-200 bg-slate-50 text-slate-600",
  CONTEXT_READY: "border-cyan-200 bg-cyan-50 text-cyan-700",
  DISPATCHING: "border-amber-200 bg-amber-50 text-amber-700",
  RUNNING: "border-teal-200 bg-teal-50 text-teal-700",
  RESULT_COLLECTING: "border-blue-200 bg-blue-50 text-blue-700",
  VERIFYING: "border-sky-200 bg-sky-50 text-sky-700",
  SUCCEEDED: "border-green-200 bg-green-50 text-green-700",
  FAILED_RETRYABLE: "border-rose-200 bg-rose-50 text-rose-700",
  FAILED_NEEDS_HUMAN: "border-orange-200 bg-orange-50 text-orange-700",
  SKIPPED: "border-slate-200 bg-slate-50 text-slate-600",
  CANCELLED: "border-slate-200 bg-slate-100 text-slate-600",
  RECOVERING: "border-cyan-200 bg-cyan-50 text-cyan-700"
};

const QA_EVIDENCE_LABEL: Record<string, string> = {
  QA_COMMAND_LOG: "命令日志",
  QA_SCREENSHOT: "浏览器截图",
  QA_TRACE: "Playwright trace",
  QA_CONSOLE_LOG: "控制台日志",
  QA_NETWORK_LOG: "网络记录",
  QA_HTTP_TRANSCRIPT: "HTTP 实链",
  QA_VIDEO: "失败录像",
  QA_EVIDENCE_MANIFEST: "证据清单"
};

export type RoleWorkbenchTab = "issues" | "evidence" | "runs";

type TaskRoleWorkbenchProps = {
  task: RdTask;
  overview: RdTaskExecutionOverview | null;
  overviewError: string;
  promptStages: RdTaskRolePromptStage[];
  promptLoading: boolean;
  promptError: string;
  evidenceLoading: boolean;
  qaEvidence: RdTaskQaEvidence[];
  qaEvidenceError: string;
  retrievalRuns: RetrievalRun[];
  retrievalError: string;
  materials: TaskMaterial[];
  failureRecovery: TaskFailureRecoverySnapshot | null;
  failureRecoveryLoading: boolean;
  failureRecoveryError: string;
  selectedRole: string;
  selectedAttemptNo?: number;
  selectedTab: RoleWorkbenchTab;
  onSelectionChange: (role: string, attemptNo?: number) => void;
  onTabChange: (tab: RoleWorkbenchTab) => void;
  onInspectRetrievalRun: (run: RetrievalRun) => Promise<void>;
  onRefresh: () => Promise<void>;
  captureTaskActionGuard: CaptureTaskActionGuard;
};

/** Role-first task inspector that keeps result, evidence, and retry history bound to one immutable attempt. */
export function TaskRoleWorkbench({
  task,
  overview,
  overviewError,
  promptStages,
  promptLoading,
  promptError,
  evidenceLoading,
  qaEvidence,
  qaEvidenceError,
  retrievalRuns,
  retrievalError,
  materials,
  failureRecovery,
  failureRecoveryLoading,
  failureRecoveryError,
  selectedRole,
  selectedAttemptNo,
  selectedTab,
  onSelectionChange,
  onTabChange,
  onInspectRetrievalRun,
  onRefresh,
  captureTaskActionGuard
}: TaskRoleWorkbenchProps) {
  const roles = useMemo(() => buildRoleWorkbench(
    overview?.stageRuns || [],
    promptStages,
    qaEvidence,
    task.taskType
  ), [overview?.stageRuns, promptStages, qaEvidence, task.taskType]);
  const selection = useMemo(() => (
    selectRoleAttempt(roles, selectedRole, selectedAttemptNo)
  ), [roles, selectedRole, selectedAttemptNo]);
  const selectedRoleView = roles.find((item) => item.role === selection.role) || roles[0];
  const selectedAttempt = selectedRoleView?.attempts.find((item) => (
    item.stageRunId === selection.stageRunId
  ));
  const selectedStage = selectedAttempt?.stage;
  const selectedPrompt = selectedAttempt?.promptStage;
  const selectedQaEvidence = selectedAttempt
    ? qaEvidence.filter((item) => item.stageRunId === selectedAttempt.stageRunId)
    : [];
  const selectedRetrievalRuns = selectedAttempt
    ? retrievalRuns.filter((item) => item.stageRunId === selectedAttempt.stageRunId)
    : [];
  const exactStageRecovery = selectedAttempt && failureRecovery?.retryPoint.failurePhase === "AGENT_ROLE"
    && failureRecovery.retryPoint.failedStageRunId === selectedAttempt.stageRunId
    ? failureRecovery
    : null;
  const exactRagRecovery = selectedAttempt && failureRecovery?.retryPoint.failurePhase === "RAG"
    && selectedRetrievalRuns.some((run) => run.runId === failureRecovery.retryPoint.failedRetrievalRunId)
    ? failureRecovery
    : null;
  const selectedRecovery = exactStageRecovery || exactRagRecovery;
  const recoveryMatchesSelectedAttempt = Boolean(selectedRecovery);
  const unresolvedRagTargetsSelectedAttempt = Boolean(
    selectedStage
    && failureRecovery?.retryPoint.failurePhase === "RAG"
    && !exactRagRecovery
    && failureRecovery.retryPoint.retryFromRole === selectedStage.role
    && selectedStage.stageRunId === selectedRoleView?.latestStage?.stageRunId
  );
  const recoveryExpectedForSelectedAttempt = recoveryMatchesSelectedAttempt || Boolean(
    !failureRecovery
    && selectedStage
    && selectedStage.stageRunId === selectedRoleView?.latestStage?.stageRunId
    && isFailedStageStatus(selectedStage.status)
    && isRetryableRequirementTaskStatus(task.status)
  ) || unresolvedRagTargetsSelectedAttempt;

  useEffect(() => {
    if (selection.role !== selectedRole || selection.attemptNo !== selectedAttemptNo) {
      onSelectionChange(selection.role, selection.attemptNo);
    }
  }, [onSelectionChange, selectedAttemptNo, selectedRole, selection.attemptNo, selection.role]);

  return (
    <section className="overflow-hidden border border-slate-200 bg-white" aria-labelledby="role-workbench-title">
      <header className="border-b border-slate-200 px-4 py-4 sm:px-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 id="role-workbench-title" className="text-base font-semibold text-slate-950">角色工作台</h2>
            <p className="mt-1 text-xs text-slate-600">围绕一个角色和 Attempt 查看问题、真实输入与完整运行记录。</p>
          </div>
          {overview ? (
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-600">
              <span>阶段推进 <strong className="text-slate-900">{overview.progressCompleted}/{overview.progressTotal}</strong></span>
              <span>当前角色 <strong className="text-slate-900">{ROLE_LABEL[overview.currentRole] || overview.currentRole || "-"}</strong></span>
              <span>耗时 <strong className="text-slate-900">{formatDuration(overview.elapsedMillis)}</strong></span>
            </div>
          ) : null}
        </div>
        {overviewError ? (
          <div className="mt-3 border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-xs text-amber-900">
            执行摘要暂不可用：{overviewError}
          </div>
        ) : null}
      </header>

      <Tabs
        value={selection.role}
        onValueChange={(role) => {
          const nextRole = roles.find((item) => item.role === role);
          onSelectionChange(role, nextRole?.attempts[0]?.attemptNo);
        }}
      >
        <TabsList className="grid w-full grid-cols-2 gap-2 border-b border-slate-200 bg-slate-50/70 p-3 xl:grid-cols-4" aria-label="交付角色">
          {roles.map((role) => {
            const latestAttempt = role.attempts[0];
            const issueCount = roleIssueCount(role.latestStage, failureRecovery, retrievalRuns);
            const issueLabel = issueCount > 0
              ? `${issueCount} ${isFailedStageStatus(role.status) ? "个问题" : "项关注"}`
              : STATUS_LABEL[role.status] || role.status;
            return (
              <TabsTrigger
                key={role.role}
                value={role.role}
                className={cn(
                  "min-h-[76px] flex-col items-stretch justify-between rounded-lg border border-slate-200 bg-white px-3 py-2 text-left shadow-none",
                  "hover:border-slate-300 hover:bg-slate-50",
                  "data-[state=active]:border-teal-500 data-[state=active]:bg-teal-50/60 data-[state=active]:text-slate-950 data-[state=active]:shadow-none"
                )}
              >
                <span className="flex w-full min-w-0 items-center justify-between gap-2">
                  <span className="truncate font-semibold text-slate-900">{ROLE_LABEL[role.role] || role.role}</span>
                  <RoleStatusIcon status={role.status} />
                </span>
                <span className="mt-1 flex w-full min-w-0 items-center justify-between gap-2 text-[11px] font-normal text-slate-600">
                  <span>{latestAttempt ? `Attempt ${latestAttempt.attemptNo}` : "尚未创建"}</span>
                  <span className={cn(
                    "truncate",
                    issueCount > 0
                      ? isFailedStageStatus(role.status) ? "text-rose-700" : "text-amber-700"
                      : "text-slate-500"
                  )}>
                    {issueLabel}
                  </span>
                </span>
                <span className="mt-1 block w-full truncate text-[11px] font-normal text-slate-500">
                  {role.blocker || (role.unboundPromptCount > 0 ? `${role.unboundPromptCount} 条 Prompt 绑定异常` : "无当前阻断")}
                </span>
              </TabsTrigger>
            );
          })}
        </TabsList>

        <TabsContent value={selection.role} className="m-0">
          <div className="border-b border-slate-200 px-4 py-4 sm:px-5">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="text-lg font-semibold text-slate-950">{ROLE_LABEL[selection.role] || selection.role}</h3>
                  {selectedStage ? <StatusBadge status={selectedStage.status} /> : null}
                  {selectedQaEvidence.length > 0 ? (
                    <Badge variant="outline" className="border-cyan-200 bg-cyan-50 text-cyan-800">
                      {selectedQaEvidence.length} 项 QA 证据
                    </Badge>
                  ) : null}
                </div>
                <p className="mt-1 line-clamp-2 text-sm text-slate-600">
                  {selectedRoleView?.blocker || selectedStage?.resultSummary || "选择 Attempt 后查看该角色的执行输入与结果。"}
                </p>
              </div>
              <div className="w-full shrink-0 sm:w-64">
                <Select
                  value={selectedAttempt?.stageRunId || ""}
                  onValueChange={(stageRunId) => {
                    const attempt = selectedRoleView?.attempts.find((item) => item.stageRunId === stageRunId);
                    onSelectionChange(selection.role, attempt?.attemptNo);
                  }}
                  disabled={!selectedRoleView?.attempts.length}
                >
                  <SelectTrigger aria-label="选择角色 Attempt"><SelectValue placeholder="暂无 Attempt" /></SelectTrigger>
                  <SelectContent>
                    {selectedRoleView?.attempts.map((attempt) => (
                      <SelectItem key={attempt.stageRunId} value={attempt.stageRunId}>
                        Attempt {attempt.attemptNo} · {STATUS_LABEL[attempt.status] || attempt.status}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>

          {!selectedStage ? (
            <div className="px-4 py-10 text-center text-sm text-slate-500 sm:px-5">
              该角色尚未创建执行 Attempt。
            </div>
          ) : (
            <Tabs value={selectedTab} onValueChange={(value) => onTabChange(value as RoleWorkbenchTab)}>
              <TabsList className="grid h-11 w-full grid-cols-3 border-b border-slate-200 bg-white px-3 sm:w-[520px] sm:border-r">
                <InspectorTab value="issues">结果与问题</InspectorTab>
                <InspectorTab value="evidence">输入与证据</InspectorTab>
                <InspectorTab value="runs">运行记录</InspectorTab>
              </TabsList>
              <TabsContent value="issues" className="m-0">
                <RoleResultPanel
                  stage={selectedStage}
                  recovery={selectedRecovery}
                  recoveryLoading={recoveryExpectedForSelectedAttempt && failureRecoveryLoading}
                  recoveryError={recoveryExpectedForSelectedAttempt ? failureRecoveryError : ""}
                  task={task}
                  materials={materials}
                  onRefresh={onRefresh}
                  captureTaskActionGuard={captureTaskActionGuard}
                />
              </TabsContent>
              <TabsContent value="evidence" className="m-0">
                <RoleEvidencePanel
                  promptStage={selectedPrompt}
                  promptLoading={promptLoading}
                  promptError={promptError}
                  evidenceLoading={evidenceLoading}
                  qaEvidence={selectedQaEvidence}
                  qaEvidenceError={qaEvidenceError}
                  retrievalRuns={selectedRetrievalRuns}
                  retrievalError={retrievalError}
                  onInspectRetrievalRun={onInspectRetrievalRun}
                />
              </TabsContent>
              <TabsContent value="runs" className="m-0">
                <RoleHistoryPanel role={selectedRoleView} overview={overview} selectedStageRunId={selectedStage.stageRunId} />
              </TabsContent>
            </Tabs>
          )}
        </TabsContent>
      </Tabs>
    </section>
  );
}

function InspectorTab({ value, children }: { value: RoleWorkbenchTab; children: React.ReactNode }) {
  return (
    <TabsTrigger
      value={value}
      className="h-full border-b-2 border-transparent px-2 text-xs text-slate-600 data-[state=active]:border-teal-600 data-[state=active]:text-teal-800"
    >
      {children}
    </TabsTrigger>
  );
}

function RoleResultPanel({
  stage,
  recovery,
  recoveryLoading,
  recoveryError,
  task,
  materials,
  onRefresh,
  captureTaskActionGuard
}: {
  stage: RdTaskStageRun;
  recovery: TaskFailureRecoverySnapshot | null;
  recoveryLoading: boolean;
  recoveryError: string;
  task: RdTask;
  materials: TaskMaterial[];
  onRefresh: () => Promise<void>;
  captureTaskActionGuard: CaptureTaskActionGuard;
}) {
  const result = projectRoleResult(stage.role, stage.resultPreview);
  const hasIssues = result.problems.length + result.risks.length + result.acceptanceGaps.length > 0;

  return (
    <div className="space-y-5 px-4 py-5 sm:px-5">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Metric label="状态" value={STATUS_LABEL[stage.status] || stage.status} />
        <Metric label="Provider" value={stage.providerName || "尚未派发"} mono />
        <Metric label="耗时" value={formatDuration(stage.elapsedMillis)} />
        <Metric label="结果产物" value={stage.resultArtifactId || "尚未归档"} mono />
      </div>

      {stage.errorMessage ? (
        <section className="border-l-2 border-rose-500 bg-rose-50 px-3 py-3">
          <div className="text-sm font-semibold text-rose-950">{stage.errorCategory || "阶段错误"}</div>
          <p className="mt-1 whitespace-pre-wrap break-words text-sm text-rose-900">{stage.errorMessage}</p>
        </section>
      ) : null}

      {result.available ? (
        <section>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h4 className="text-sm font-semibold text-slate-950">结构化角色结果</h4>
              <p className="mt-1 text-sm text-slate-700">{result.summary || stage.resultSummary || "已归档角色结果。"}</p>
            </div>
            {result.headline ? <StatusBadge status={result.headline} /> : null}
          </div>
          {result.facts.length > 0 ? (
            <dl className="mt-4 grid gap-3 border-y border-slate-200 py-3 sm:grid-cols-2">
              {result.facts.map((fact) => (
                <div key={fact.label} className="min-w-0">
                  <dt className="text-xs text-slate-500">{fact.label}</dt>
                  <dd className="mt-1 break-words text-sm text-slate-800">{fact.value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
        </section>
      ) : null}

      {hasIssues ? (
        <div className="grid gap-4 lg:grid-cols-3">
          <IssueList title="待处理问题" items={result.problems} tone="problem" />
          <IssueList title="风险" items={result.risks} tone="risk" />
          <IssueList title="验收缺口" items={result.acceptanceGaps} tone="gap" />
        </div>
      ) : (
        <div className="flex items-center gap-2 border-y border-slate-200 py-4 text-sm text-slate-600">
          <CheckCircle2 className="h-4 w-4 text-emerald-600" />
          当前 Attempt 没有可识别的结构化阻断项。
        </div>
      )}

      {result.qaChecks.length > 0 ? (
        <section>
          <SectionHeading title="验收执行结果" description="保留 CURRENT / REGRESSION 范围、命令和退出码。" />
          <div className="mt-3 divide-y divide-slate-200 border-y border-slate-200">
            {result.qaChecks.map((check, index) => (
              <div key={`${check.scope}-${check.criteria}-${index}`} className="grid gap-2 py-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-start">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-slate-900">{check.criteria}</span>
                    {check.scope ? <Badge variant="outline" className="border-slate-200 bg-white text-slate-600">{check.scope}</Badge> : null}
                    <StatusBadge status={check.status} />
                  </div>
                  {check.command ? <code className="mt-2 block overflow-x-auto whitespace-pre-wrap break-words text-xs text-slate-600">{check.command}</code> : null}
                </div>
                <div className="text-xs text-slate-500">
                  {check.exitCode !== undefined ? `exit ${check.exitCode}` : ""}
                  {check.durationMillis ? ` · ${formatDuration(check.durationMillis)}` : ""}
                </div>
              </div>
            ))}
          </div>
          {result.browserValidation ? (
            <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600">
              <span>浏览器：{result.browserValidation.browser || "未记录"}</span>
              <span>已执行：{result.browserValidation.performed ? "是" : "否"}</span>
              <span>视口：{result.browserValidation.viewports.join("、") || "未记录"}</span>
            </div>
          ) : null}
        </section>
      ) : null}

      {result.failureCategory || result.retryRecommendation ? (
        <div className="grid gap-3 border-y border-slate-200 py-3 text-sm sm:grid-cols-2">
          <Metric label="失败分类" value={result.failureCategory || "-"} />
          <Metric label="重试建议" value={result.retryRecommendation || "-"} />
        </div>
      ) : null}

      {stage.resultPreview ? (
        <details className="border border-slate-200 bg-slate-50/40">
          <summary className="cursor-pointer px-3 py-2 text-sm font-medium text-slate-700">原始角色结果</summary>
          <pre className="max-h-80 overflow-auto border-t border-slate-200 bg-slate-950 p-3 text-xs leading-5 text-slate-100">
            {formatJson(stage.resultPreview)}
          </pre>
        </details>
      ) : null}

      {recovery || recoveryLoading || recoveryError ? (
        <TaskFailureRecoveryWorkbench
          key={`${task.taskId}-${stage.stageRunId}`}
          task={task}
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

function RoleEvidencePanel({
  promptStage,
  promptLoading,
  promptError,
  evidenceLoading,
  qaEvidence,
  qaEvidenceError,
  retrievalRuns,
  retrievalError,
  onInspectRetrievalRun
}: {
  promptStage?: RdTaskRolePromptStage;
  promptLoading: boolean;
  promptError: string;
  evidenceLoading: boolean;
  qaEvidence: RdTaskQaEvidence[];
  qaEvidenceError: string;
  retrievalRuns: RetrievalRun[];
  retrievalError: string;
  onInspectRetrievalRun: (run: RetrievalRun) => Promise<void>;
}) {
  return (
    <div className="space-y-6 px-4 py-5 sm:px-5">
      <section>
        <SectionHeading title="实际角色 Prompt" description="仅展示当前 stageRunId 精确绑定的安全预览。" />
        {promptLoading ? <LoadingLine label="正在加载角色 Prompt" /> : null}
        {!promptLoading && promptError ? <PanelError message={promptError} /> : null}
        {!promptLoading && !promptError && !promptStage ? (
          <EmptyLine label="当前 Attempt 尚无已绑定的 Prompt 读模型。" />
        ) : null}
        {!promptLoading && !promptError && promptStage ? (
          promptStage.prompt.available ? (
            <div className="mt-3">
              <div className="grid gap-3 border-y border-slate-200 py-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                <Metric label="Provider" value={promptStage.providerName || "-"} mono />
                <Metric label="Prompt 产物" value={promptStage.prompt.artifactId || "-"} mono />
                <Metric label="上下文包" value={promptStage.context.packageId || "-"} mono />
                <Metric label="预览长度" value={`${promptStage.prompt.previewLength.toLocaleString()} chars`} />
              </div>
              <div className="mt-3 max-h-[420px] overflow-auto border border-slate-200 bg-slate-50/60 p-4 text-sm leading-relaxed text-slate-700">
                <Suspense fallback={<LoadingLine label="正在渲染 Markdown" />}>
                  <MarkdownRenderer content={promptStage.prompt.contentPreview} />
                </Suspense>
              </div>
              {promptStage.prompt.truncated ? (
                <p className="mt-2 text-xs text-amber-700">安全预览已截断，原始长度 {promptStage.prompt.contentLength.toLocaleString()} 字符。</p>
              ) : null}
            </div>
          ) : (
            <EmptyLine label={promptStage.prompt.unavailableReason || "当前 Attempt 暂无可审计 Prompt。"} />
          )
        ) : null}
      </section>

      <section>
        <SectionHeading title="上下文证据" description="验收约束、风险和 RAG 证据均来自当前 Attempt 的 RoleContextPackage。" />
        {promptLoading ? <LoadingLine label="正在加载上下文证据" /> : null}
        {!promptLoading && promptError ? <PanelError message={promptError} /> : null}
        {!promptLoading && !promptError && promptStage?.context.available ? (
          <div className="mt-3 space-y-4">
            <div className="grid gap-3 border-y border-slate-200 py-3 sm:grid-cols-2 lg:grid-cols-4">
              <Metric label="上下文版本" value={`v${promptStage.context.packageVersion}`} />
              <Metric label="字符预算" value={`${promptStage.context.usedChars.toLocaleString()} / ${promptStage.context.maxChars.toLocaleString()}`} />
              <Metric label="已选证据" value={`${promptStage.context.evidence.length} 条`} />
              <Metric label="省略证据" value={`${promptStage.context.omittedEvidenceIds.length} 条`} />
            </div>
            {promptStage.context.evidence.length > 0 ? (
              <div className="divide-y divide-slate-200 border-y border-slate-200">
                {promptStage.context.evidence.map((evidence) => (
                  <article key={evidence.evidenceId} className="py-3">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h5 className="break-words text-sm font-medium text-slate-900">{evidence.title || evidence.evidenceId}</h5>
                          <Badge variant="outline" className="border-slate-200 bg-white text-slate-600">{evidence.sourceType || "UNKNOWN"}</Badge>
                          {evidence.sharedRoot ? <Badge variant="outline" className="border-cyan-200 bg-cyan-50 text-cyan-800">共享根证据</Badge> : null}
                        </div>
                        <p className="mt-1 break-words text-xs leading-5 text-slate-600">{evidence.summary || "无安全摘要"}</p>
                      </div>
                      <span className="shrink-0 text-xs text-slate-500">相关性 {Math.round(evidence.relevanceScore * 100)}%</span>
                    </div>
                    <div className="mt-2 grid gap-2 text-xs text-slate-500 sm:grid-cols-2">
                      <span className="break-words">入选原因：{evidence.selectionReason || "未记录"}</span>
                      <span className="break-words">关键类型：{evidence.requiredEvidenceType || "未指定"}</span>
                      <span className="break-all font-mono">来源：{evidence.sourceUri || "未提供"}</span>
                      <span className="break-all font-mono">hash：{evidence.contentHash || "未提供"}</span>
                    </div>
                  </article>
                ))}
              </div>
            ) : <EmptyLine label="当前上下文包没有可展示证据。" />}
            {(promptStage.context.acceptanceCriteria.length > 0 || promptStage.context.riskHints.length > 0) ? (
              <details className="border border-slate-200 bg-slate-50/40">
                <summary className="cursor-pointer px-3 py-2 text-sm font-medium text-slate-700">验收约束与风险提示</summary>
                <div className="grid gap-5 border-t border-slate-200 p-3 sm:grid-cols-2">
                  <IssueList title="验收约束" items={promptStage.context.acceptanceCriteria} tone="gap" />
                  <IssueList title="风险提示" items={promptStage.context.riskHints} tone="risk" />
                </div>
              </details>
            ) : null}
          </div>
        ) : null}
        {!promptLoading && !promptError && !promptStage?.context.available ? (
          <EmptyLine label={promptStage?.context.unavailableReason || "当前 Attempt 暂无可审计上下文。"} />
        ) : null}
      </section>

      <section>
        <SectionHeading title="检索运行" description="只显示 stageRunId 与当前 Attempt 完全一致的 RetrievalRun。" />
        {evidenceLoading ? <LoadingLine label="正在加载检索运行" /> : null}
        {!evidenceLoading && retrievalError ? <PanelError message={retrievalError} /> : null}
        {!evidenceLoading && !retrievalError && retrievalRuns.length === 0 ? (
          <EmptyLine label="当前 Attempt 没有检索运行。" />
        ) : null}
        {!evidenceLoading && !retrievalError && retrievalRuns.length > 0 ? (
          <div className="mt-3 divide-y divide-slate-200 border-y border-slate-200">
            {retrievalRuns.map((run) => (
              <div key={run.runId} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="outline" className={retrievalRunStatusClass(run.status)}>{retrievalRunStatusLabel(run.status)}</Badge>
                    <span className="text-sm font-medium text-slate-800">Attempt {run.attemptNo}</span>
                  </div>
                  <p className="mt-1 line-clamp-2 text-xs text-slate-600">{run.errorMessage || run.stopReason || run.queryPreview || run.runId}</p>
                </div>
                <Button variant="outline" size="sm" onClick={() => void onInspectRetrievalRun(run)}>
                  <Search className="h-4 w-4" />查看过程
                </Button>
              </div>
            ))}
          </div>
        ) : null}
      </section>

      <section>
        <SectionHeading title="QA 验证证据" description={`当前 Attempt 精确绑定 ${qaEvidence.length} 项证据。`} />
        {evidenceLoading ? <LoadingLine label="正在加载 QA 验证证据" /> : null}
        {!evidenceLoading && qaEvidenceError ? <PanelError message={qaEvidenceError} /> : null}
        {!evidenceLoading && !qaEvidenceError && qaEvidence.length === 0 ? (
          <EmptyLine label="当前 Attempt 尚无 QA 验证证据。" />
        ) : null}
        {!evidenceLoading && !qaEvidenceError && qaEvidence.length > 0 ? (
          <QaEvidenceList evidence={qaEvidence} />
        ) : null}
      </section>
    </div>
  );
}

function RoleHistoryPanel({
  role,
  overview,
  selectedStageRunId
}: {
  role: ReturnType<typeof buildRoleWorkbench<RdTaskStageRun, RdTaskRolePromptStage, RdTaskQaEvidence>>[number];
  overview: RdTaskExecutionOverview | null;
  selectedStageRunId: string;
}) {
  const running = overview?.runningExecutions.find((item) => item.stageRunId === selectedStageRunId);
  return (
    <div className="space-y-5 px-4 py-5 sm:px-5">
      {running ? (
        <section className="border-l-2 border-teal-500 bg-teal-50/60 px-3 py-3">
          <div className="flex flex-wrap items-center gap-2 text-sm font-semibold text-teal-950">
            <LoaderCircle className="h-4 w-4 animate-spin" />当前容器仍在运行
          </div>
          <div className="mt-2 grid gap-2 text-xs text-teal-900 sm:grid-cols-2">
            <span className="break-all font-mono">{running.containerName || running.executionTaskId}</span>
            <span>累计 Token：{running.tokenUsage.totalTokens.toLocaleString()}</span>
          </div>
        </section>
      ) : null}

      <section>
        <SectionHeading title="Attempt 记录" description="历史 Attempt 不改写，最新记录位于最上方。" />
        <div className="mt-3 divide-y divide-slate-200 border-y border-slate-200">
          {role.attempts.map((attempt) => {
            const stage = attempt.stage;
            if (!stage) return null;
            return (
              <article key={stage.stageRunId} className={cn("py-4", stage.stageRunId === selectedStageRunId && "bg-teal-50/35 px-3")}>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-semibold text-slate-900">Attempt {stage.attemptNo}</span>
                      <StatusBadge status={stage.status} />
                      {stage.stageRunId === selectedStageRunId ? <Badge className="bg-teal-700 text-white">当前查看</Badge> : null}
                    </div>
                    <p className="mt-1 line-clamp-2 text-xs text-slate-600">{stage.errorMessage || stage.resultSummary || "暂无结果摘要"}</p>
                  </div>
                  <div className="text-right text-xs text-slate-500">
                    <div>{stage.providerName || "未派发 Provider"}</div>
                    <div className="mt-1">{formatDuration(stage.elapsedMillis)}</div>
                  </div>
                </div>
                <dl className="mt-3 grid gap-2 border-t border-slate-200 pt-3 text-xs sm:grid-cols-2 lg:grid-cols-4">
                  <ArtifactMeta label="Stage Run" value={stage.stageRunId} />
                  <ArtifactMeta label="Context" value={stage.contextPackageId} />
                  <ArtifactMeta label="Prompt" value={stage.promptArtifactId} />
                  <ArtifactMeta label="Result" value={stage.resultArtifactId} />
                </dl>
                {stage.providerAttempts.length > 0 ? (
                  <details className="mt-3 border border-slate-200 bg-white">
                    <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700">
                      Provider 尝试记录（{stage.providerAttempts.length}）
                    </summary>
                    <div className="divide-y divide-slate-200 border-t border-slate-200">
                      {stage.providerAttempts.map((providerAttempt, index) => (
                        <pre key={index} className="overflow-auto whitespace-pre-wrap break-words p-3 text-xs leading-5 text-slate-700">
                          {JSON.stringify(providerAttempt, null, 2)}
                        </pre>
                      ))}
                    </div>
                  </details>
                ) : null}
              </article>
            );
          })}
        </div>
      </section>
    </div>
  );
}

function QaEvidenceList({ evidence }: { evidence: RdTaskQaEvidence[] }) {
  const screenshots = evidence.filter((item) => item.type === "QA_SCREENSHOT");
  const records = evidence.filter((item) => item.type !== "QA_SCREENSHOT");
  return (
    <div>
      {screenshots.length > 0 ? (
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {screenshots.map((item) => (
            <a key={item.artifactId} href={item.contentUrl} target="_blank" rel="noreferrer" className="min-w-0 border border-slate-200 bg-slate-50 p-2">
              <img src={item.contentUrl} alt={item.summary || item.name} className="aspect-video w-full bg-white object-contain" loading="lazy" />
              <span className="mt-2 block truncate text-xs font-medium text-slate-700">{item.summary || item.name}</span>
            </a>
          ))}
        </div>
      ) : null}
      {records.length > 0 ? (
        <div className="mt-3 divide-y divide-slate-200 border-y border-slate-200">
          {records.map((item) => (
            <div key={item.artifactId} className="flex min-w-0 items-center justify-between gap-3 py-3">
              <div className="flex min-w-0 items-start gap-3">
                {item.type === "QA_TRACE" ? <FileText className="mt-0.5 h-4 w-4 shrink-0 text-slate-500" /> : <TerminalSquare className="mt-0.5 h-4 w-4 shrink-0 text-slate-500" />}
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium text-slate-800">{QA_EVIDENCE_LABEL[item.type] || item.type}</div>
                  <div className="mt-1 truncate text-xs text-slate-500">{item.summary || item.name} · {formatBytes(item.sizeBytes)}</div>
                </div>
              </div>
              <Button asChild variant="outline" size="icon" title={item.previewable ? "查看证据" : "下载证据"}>
                <a href={item.contentUrl} target={item.previewable ? "_blank" : undefined} rel={item.previewable ? "noreferrer" : undefined} download={item.previewable ? undefined : true}>
                  {item.previewable ? <ExternalLink className="h-4 w-4" /> : <Download className="h-4 w-4" />}
                </a>
              </Button>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function RoleStatusIcon({ status }: { status: string }) {
  if (status === "SUCCEEDED") return <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600" aria-label="已成功" />;
  if (isFailedStageStatus(status)) return <AlertTriangle className="h-4 w-4 shrink-0 text-rose-600" aria-label="存在问题" />;
  if (isActiveStageStatus(status)) return <LoaderCircle className="h-4 w-4 shrink-0 animate-spin text-teal-600" aria-label="执行中" />;
  return <Clock3 className="h-4 w-4 shrink-0 text-slate-400" aria-label="待开始" />;
}

function StatusBadge({ status }: { status: string }) {
  const knownStatus = STATUS_LABEL[status] || status;
  const className = STATUS_CLASS[status]
    || (status === "FAILED" || status === "REJECTED" || status === "NEED_INFO"
      ? "border-rose-200 bg-rose-50 text-rose-700"
      : "border-slate-200 bg-slate-50 text-slate-700");
  return <Badge variant="outline" className={className}>{knownStatus}</Badge>;
}

function Metric({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-slate-500">{label}</div>
      <div className={cn("mt-1 break-words text-sm text-slate-800", mono && "font-mono text-xs")} title={value}>{value || "-"}</div>
    </div>
  );
}

function ArtifactMeta({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-slate-500">{label}</dt>
      <dd className="mt-1 truncate font-mono text-slate-700" title={value || "-"}>{value || "-"}</dd>
    </div>
  );
}

function IssueList({ title, items, tone }: { title: string; items: string[]; tone: "problem" | "risk" | "gap" }) {
  const toneClass = {
    problem: "border-rose-200 bg-rose-50/60",
    risk: "border-amber-200 bg-amber-50/60",
    gap: "border-cyan-200 bg-cyan-50/60"
  }[tone];
  return (
    <section className={cn("min-w-0 border px-3 py-3", toneClass)}>
      <h5 className="text-sm font-semibold text-slate-900">{title}</h5>
      {items.length === 0 ? <p className="mt-2 text-sm text-slate-500">无</p> : (
        <ul className="mt-2 space-y-2 text-sm text-slate-700">
          {items.map((item, index) => <li key={`${item}-${index}`} className="break-words">{index + 1}. {item}</li>)}
        </ul>
      )}
    </section>
  );
}

function SectionHeading({ title, description }: { title: string; description: string }) {
  return (
    <div>
      <h4 className="text-sm font-semibold text-slate-950">{title}</h4>
      <p className="mt-1 text-xs text-slate-500">{description}</p>
    </div>
  );
}

function LoadingLine({ label }: { label: string }) {
  return <div className="mt-3 flex items-center gap-2 py-4 text-sm text-slate-500"><LoaderCircle className="h-4 w-4 animate-spin" />{label}</div>;
}

function EmptyLine({ label }: { label: string }) {
  return <div className="mt-3 border-y border-dashed border-slate-200 py-4 text-sm text-slate-500">{label}</div>;
}

function PanelError({ message }: { message: string }) {
  return <div className="mt-3 border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-sm text-amber-900">{message}</div>;
}

function formatDuration(value?: number) {
  const ms = Math.max(0, value || 0);
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)}min`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
}

function formatBytes(value?: number) {
  const bytes = Math.max(0, value || 0);
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
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

function roleIssueCount(
  stage: RdTaskStageRun | undefined,
  recovery: TaskFailureRecoverySnapshot | null,
  retrievalRuns: RetrievalRun[]
) {
  if (!stage) return 0;
  const result = projectRoleResult(stage.role, stage.resultPreview);
  const projected = result.problems.length + result.risks.length + result.acceptanceGaps.length;
  const recoveryIssues = recovery?.retryPoint.failurePhase === "AGENT_ROLE"
    && recovery.retryPoint.failedStageRunId === stage.stageRunId
    ? recovery.diagnostic.issues.length + recovery.diagnostic.risks.length + recovery.diagnostic.acceptanceGaps.length
    : 0;
  const ragRecoveryIssues = recovery?.retryPoint.failurePhase === "RAG"
    && retrievalRuns.some((run) => (
      run.runId === recovery.retryPoint.failedRetrievalRunId && run.stageRunId === stage.stageRunId
    ))
    ? recovery.diagnostic.issues.length + recovery.diagnostic.risks.length + recovery.diagnostic.acceptanceGaps.length
    : 0;
  return Math.max(projected, recoveryIssues, ragRecoveryIssues, stage.errorMessage || isFailedStageStatus(stage.status) ? 1 : 0);
}
