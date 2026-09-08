import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Download,
  ExternalLink,
  FileText,
  GitBranch,
  LoaderCircle,
  Radio,
  Search,
  ShieldCheck,
  TerminalSquare,
  XCircle
} from "lucide-react";

import {
  TaskFailureRecoveryWorkbench,
  type CaptureTaskActionGuard
} from "@/components/admin/rdtask/TaskFailureRecoveryWorkbench";
import { ReadableAgentTrace } from "@/components/admin/rdtask/ReadableAgentTrace";
import { RoleEffectiveContextCard } from "@/components/admin/rdtask/RoleEffectiveContextCard";
import { RoleAgentStateCard } from "@/components/admin/rdtask/RoleAgentStateCard";
import { RoleDeliverablesPanel } from "@/components/admin/rdtask/RoleDeliverablesPanel";
import type { CodingMeaView } from "@/pages/admin/rdtask/codingMeaModel";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
import { roleCardSummary } from "@/pages/admin/rdtask/workbenchSummaryModel";
import { getRdTaskExecutionTrace } from "@/services/rdTaskService";
import type {
  AuditedTaskState,
  RdTask,
  RdTaskExecutionTrace,
  RdTaskExecutionOverview,
  RdTaskQaEvidence,
  RdTaskRolePromptStage,
  RdTaskStageRun,
  TaskMaterial,
  HostVerificationList
} from "@/services/rdTaskService";
import { retrievalRunStatusClass, retrievalRunStatusLabel, type RetrievalRun } from "@/services/retrievalRunService";
import type { TaskFailureRecoverySnapshot } from "@/services/taskRetryService";
import {
  agentRuntimeEventsPath,
  getAgentRuntimeEvents,
  getAgentRuntimeSnapshot,
  type AgentRuntimeEvent,
  type AgentRuntimeEventSnapshot
} from "@/services/executionTraceService";
import { snapshotFields } from "@/pages/admin/project/agentRuntimePresentation";
import {
  clearTaskAgentExecutionProfileOverride,
  getAgentExecutionProfiles,
  getTaskAgentExecutionProfileOverride,
  setTaskAgentExecutionProfileOverride,
  type AgentExecutionProfile,
  type AgentExecutionRole
} from "@/services/projectService";

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

export type RoleWorkbenchTab = "issues" | "evidence" | "runs" | "trace";

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
  hostVerifications?: HostVerificationList | null;
  auditedState?: AuditedTaskState | null;
  codingMeaView?: CodingMeaView | null;
  codingMeaLoading?: boolean;
  codingMeaError?: string;
  selectedRole: string;
  selectedAttemptNo?: number;
  selectedTab: RoleWorkbenchTab;
  onSelectionChange: (role: string, attemptNo?: number) => void;
  onTabChange: (tab: RoleWorkbenchTab) => void;
  onNavigateToQaAttempt?: (qaAttemptNo: number) => void;
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
  hostVerifications,
  auditedState = null,
  codingMeaView = null,
  codingMeaLoading = false,
  codingMeaError = "",
  selectedRole,
  selectedAttemptNo,
  selectedTab,
  onSelectionChange,
  onTabChange,
  onNavigateToQaAttempt,
  onInspectRetrievalRun,
  onRefresh,
  captureTaskActionGuard
}: TaskRoleWorkbenchProps) {
  const roles = useMemo(() => buildRoleWorkbench(
    overview?.stageRuns || [],
    promptStages,
    qaEvidence,
    task.taskType,
    hostVerifications?.runs[0] || null
  ), [overview?.stageRuns, promptStages, qaEvidence, task.taskType, hostVerifications?.runs]);
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

  const handleNavigateToQaAttempt = useCallback((qaAttemptNo: number) => {
    if (onNavigateToQaAttempt) {
      onNavigateToQaAttempt(qaAttemptNo);
    } else {
      onSelectionChange("QA_AGENT", qaAttemptNo);
    }
  }, [onNavigateToQaAttempt, onSelectionChange]);

  // 任务级 PR 只认任务 DTO 的 pullRequestUrl（TASK_PR 材料类型在系统里不存在）
  const taskPrUrl = task.pullRequestUrl || task.executionEvidence?.pullRequestUrl || "";
  const taskPrLink = taskPrUrl ? { prUrl: taskPrUrl, workBranch: task.workBranch || undefined } : null;

  // 四角色卡的一句话产物摘要：只消费已加载字段（最近 Attempt 预览/任务级证据与审计），不发新请求
  const roleCardSummaries = useMemo(() => new Map(roles.map((role) => [role.role, roleCardSummary({
    taskId: task.taskId,
    stage: role.latestStage,
    qaEvidence,
    auditedRecords: auditedState?.records || null,
    hostVerification: hostVerifications?.runs[0] || null,
    taskPrUrl
  })])), [roles, qaEvidence, auditedState, hostVerifications, task.taskId, taskPrUrl]);

  // 所选阶段行的副标题：阻断优先，其次所选 Attempt 自己的产物摘要（不使用占位 resultSummary）
  const selectedStageHostVerify = useMemo(() => (
    hostVerifications?.runs?.find((run) => run.codingStageRunId === selectedStage?.stageRunId) || null
  ), [hostVerifications, selectedStage?.stageRunId]);
  const selectedStageSummary = useMemo(() => selectedStage ? roleCardSummary({
    taskId: task.taskId,
    stage: selectedStage,
    qaEvidence,
    auditedRecords: auditedState?.records || null,
    hostVerification: selectedStageHostVerify,
    taskPrUrl
  }) : "", [selectedStage, task.taskId, qaEvidence, auditedState, selectedStageHostVerify, taskPrUrl]);

  return (
    <section className="overflow-hidden border border-slate-200 bg-white" aria-labelledby="role-workbench-title">
      <header className="border-b border-slate-200 px-4 py-2.5 sm:px-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <h2 id="role-workbench-title" className="text-base font-semibold text-slate-950">角色工作台</h2>
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
                  "min-h-[72px] flex-col items-stretch justify-between rounded-lg border border-slate-200 bg-white px-3 py-2 text-left shadow-none",
                  "hover:border-slate-300 hover:bg-slate-50",
                  "data-[state=active]:border-teal-500 data-[state=active]:bg-teal-50/60 data-[state=active]:text-slate-950 data-[state=active]:shadow-none"
                )}
              >
                <span className="flex w-full min-w-0 items-center justify-between gap-2">
                  <span className="truncate font-semibold text-slate-900">{ROLE_LABEL[role.role] || role.role}</span>
                  <RoleStatusIcon status={role.status} />
                </span>
                <span className="mt-0.5 flex w-full min-w-0 items-center justify-between gap-2 text-[11px] font-normal text-slate-600">
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
                <span className="mt-0.5 block w-full truncate text-[11px] font-normal text-slate-500" title={roleCardSummaries.get(role.role) || ""}>
                  {role.unboundPromptCount > 0
                    ? `${role.unboundPromptCount} 条 Prompt 绑定异常`
                    : roleCardSummaries.get(role.role) || "结构化摘要暂不可用"}
                </span>
              </TabsTrigger>
            );
          })}
        </TabsList>

        <TabsContent value={selection.role} className="m-0">
          <div className="border-b border-slate-200 px-4 py-3 sm:px-5">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="text-lg font-semibold text-slate-950">{ROLE_LABEL[selection.role] || selection.role}</h3>
                  {selectedStage ? <StatusBadge status={selectedStage.status} /> : null}
                  {selectedAttempt && selectedRoleView ? (
                    <Badge
                      variant="outline"
                      className={cn(
                        "text-[11px]",
                        selectedAttempt.stageRunId === selectedRoleView.latestStage?.stageRunId
                          ? "border-teal-300 bg-teal-50 text-teal-800"
                          : "border-slate-300 bg-slate-50 text-slate-600"
                      )}
                    >
                      {selectedAttempt.stageRunId === selectedRoleView.latestStage?.stageRunId
                        ? "当前 Attempt"
                        : `历史 Attempt ${selectedAttempt.attemptNo}`}
                    </Badge>
                  ) : null}
                  {selectedQaEvidence.length > 0 ? (
                    <Badge variant="outline" className="border-cyan-200 bg-cyan-50 text-cyan-800">
                      {selectedQaEvidence.length} 项 QA 证据
                    </Badge>
                  ) : null}
                </div>
                <p className="mt-1 line-clamp-2 text-sm text-slate-600">
                  {selectedRoleView?.blocker || selectedStageSummary || "选择 Attempt 后查看该角色的执行输入与结果。"}
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
              {selectedRoleView?.blocker || "该角色尚未创建执行 Attempt。"}
            </div>
          ) : (
            <div className="lg:grid lg:grid-cols-3">
              {/* 单份状态 DOM：窄屏位于内容 tabs 之前，桌面固定右列 */}
              <aside className="border-b border-slate-200 bg-slate-50/30 p-4 sm:p-5 lg:col-start-3 lg:row-start-1 lg:border-b-0 lg:border-l lg:sticky lg:top-16 lg:self-start">
                <RoleAgentStateCard
                  stage={selectedStage}
                  promptStage={selectedPrompt}
                  promptLoading={promptLoading}
                  promptError={promptError}
                  isLatestAttempt={selectedAttempt?.stageRunId === selectedRoleView?.latestStage?.stageRunId}
                />
              </aside>
              <div className="min-w-0 lg:col-start-1 lg:col-span-2 lg:row-start-1">
                <Tabs value={selectedTab} onValueChange={(value) => onTabChange(value as RoleWorkbenchTab)}>
                  <TabsList className="grid h-11 w-full grid-cols-4 border-b border-slate-200 bg-white px-3 sm:w-[680px] sm:border-r">
                    <InspectorTab value="issues">产物与证据</InspectorTab>
                    <InspectorTab value="evidence">Prompt</InspectorTab>
                    <InspectorTab value="trace">执行轨迹</InspectorTab>
                    <InspectorTab value="runs">运行记录</InspectorTab>
                  </TabsList>
                  <div>
                    <TabsContent value="issues" className="m-0">
                      <RoleDeliverablesPanel
                        key={`${task.taskId}-${selectedStage.stageRunId}`}
                        taskId={task.taskId}
                        stage={selectedStage}
                        promptStage={selectedPrompt}
                        qaEvidence={selectedQaEvidence}
                        materials={materials}
                        hostVerifications={hostVerifications}
                        auditedState={auditedState}
                        taskPr={taskPrLink}
                        recovery={selectedRecovery}
                        recoveryLoading={recoveryExpectedForSelectedAttempt && failureRecoveryLoading}
                        recoveryError={recoveryExpectedForSelectedAttempt ? failureRecoveryError : ""}
                        codingMeaView={codingMeaView}
                        codingMeaLoading={codingMeaLoading}
                        codingMeaError={codingMeaError}
                        onNavigateToQaAttempt={handleNavigateToQaAttempt}
                        onRefresh={onRefresh}
                        captureTaskActionGuard={captureTaskActionGuard}
                      />
                    </TabsContent>
                    <TabsContent value="evidence" className="m-0">
                      <RoleEvidencePanel
                        stage={selectedStage}
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
                      <div className="space-y-4 px-4 py-5 sm:px-5">
                        <RuntimeExecutionProfilePanel taskId={task.taskId} stage={selectedStage} />
                        <TaskRuntimeOverridePanel task={task} stage={selectedStage} />
                        <RoleHistoryPanel role={selectedRoleView} overview={overview} selectedStageRunId={selectedStage.stageRunId} />
                      </div>
                    </TabsContent>
                    <TabsContent value="trace" className="m-0">
                      <ExecutionTracePanel taskId={task.taskId} stage={selectedStage} />
                    </TabsContent>
                  </div>
                </Tabs>
              </div>
            </div>
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
  stage,
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
  stage?: RdTaskStageRun;
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
      <RoleEffectiveContextCard
        stage={stage}
        promptStage={promptStage}
        promptLoading={promptLoading}
        promptError={promptError}
        includeLatestStateTab={false}
      />

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

function TaskRuntimeOverridePanel({ task, stage }: { task: RdTask; stage: RdTaskStageRun }) {
  const [profiles, setProfiles] = useState<AgentExecutionProfile[]>([]);
  const [override, setOverride] = useState<AgentExecutionProfile | null>(null);
  const [selectedProfileId, setSelectedProfileId] = useState("");
  const [mutationToken, setMutationToken] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (task.taskType !== "REQUIREMENT" || !task.projectId) {
      setProfiles([]);
      setOverride(null);
      setSelectedProfileId("");
      setLoading(false);
      return;
    }
    setLoading(true);
    setMutationToken("");
    Promise.all([
      getAgentExecutionProfiles(task.projectId),
      getTaskAgentExecutionProfileOverride(task.taskId, stage.role as AgentExecutionRole).catch(() => null)
    ]).then(([nextProfiles, nextOverride]) => {
      if (cancelled) return;
      setProfiles(nextProfiles || []);
      setOverride(nextOverride);
      setSelectedProfileId(nextOverride?.profileId || "");
    }).catch(() => {
      if (!cancelled) {
        setProfiles([]);
        setOverride(null);
        setSelectedProfileId("");
      }
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [stage.role, task.projectId, task.taskId, task.taskType]);

  if (task.taskType !== "REQUIREMENT") return null;

  const roleProfiles = profiles.filter((profile) => profile.role === stage.role);
  const save = async () => {
    if (!selectedProfileId || !mutationToken.trim()) {
      toast.error("请选择同角色 Profile 并输入操作令牌");
      return;
    }
    setSaving(true);
    try {
      const next = await setTaskAgentExecutionProfileOverride(
        task.taskId,
        stage.role as AgentExecutionRole,
        selectedProfileId,
        mutationToken
      );
      setOverride(roleProfiles.find((profile) => profile.profileId === next.profileId) || null);
      setMutationToken("");
      toast.success("任务级执行策略已设置；新 Attempt 才会读取它");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设置任务级执行策略失败");
    } finally {
      setSaving(false);
    }
  };

  const clear = async () => {
    if (!mutationToken.trim()) {
      toast.error("请输入操作令牌");
      return;
    }
    setSaving(true);
    try {
      await clearTaskAgentExecutionProfileOverride(task.taskId, stage.role as AgentExecutionRole, mutationToken);
      setOverride(null);
      setSelectedProfileId("");
      setMutationToken("");
      toast.success("任务级执行策略已清除，将回到项目默认或兼容默认");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "清除任务级执行策略失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="border border-slate-200 bg-white">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-3 py-3">
        <div className="text-sm font-semibold text-slate-950">任务级执行策略</div>
        <Badge variant="outline" className={override ? "border-amber-200 bg-amber-50 text-amber-800" : "border-slate-200 bg-white text-slate-600"}>{override ? "TASK_OVERRIDE" : "未覆盖"}</Badge>
      </div>
      <div className="space-y-3 px-3 py-3">
        <p className="text-xs leading-5 text-slate-600">只允许选择当前角色所属项目的已注册 Profile。已有 Attempt 的不可变快照不会被修改。</p>
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,220px)]">
          <Select value={selectedProfileId || "__empty__"} onValueChange={(value) => setSelectedProfileId(value === "__empty__" ? "" : value)} disabled={loading || saving}>
            <SelectTrigger><SelectValue placeholder="选择任务级 Profile" /></SelectTrigger>
            <SelectContent>
              <SelectItem value="__empty__">不设置覆盖</SelectItem>
              {roleProfiles.filter((profile) => profile.enabled).map((profile) => <SelectItem key={profile.profileId} value={profile.profileId}>{profile.name} · {profile.runtimeType}</SelectItem>)}
            </SelectContent>
          </Select>
          <Input type="password" placeholder="操作令牌" value={mutationToken} onChange={(event) => setMutationToken(event.target.value)} disabled={loading || saving} autoComplete="one-time-code" />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={() => void save()} disabled={loading || saving || !selectedProfileId}>应用到后续 Attempt</Button>
          <Button size="sm" variant="outline" onClick={() => void clear()} disabled={loading || saving || !override}>清除任务覆盖</Button>
          {override ? <span className="self-center text-xs text-slate-500">当前：{override.name} · {override.runtimeType}</span> : null}
        </div>
      </div>
    </section>
  );
}

function RuntimeExecutionProfilePanel({ taskId, stage }: { taskId: string; stage: RdTaskStageRun }) {
  const [snapshot, setSnapshot] = useState<Awaited<ReturnType<typeof getAgentRuntimeSnapshot>> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    setSnapshot(null);
    getAgentRuntimeSnapshot(taskId, stage.stageRunId).then((next) => {
      if (!cancelled) setSnapshot(next);
    }).catch((cause) => {
      if (!cancelled) setError(cause instanceof Error ? cause.message : "执行快照暂不可用");
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [stage.stageRunId, taskId]);

  if (loading) return <LoadingLine label="正在读取执行快照" />;
  if (error) return <PanelError message={error} />;
  if (!snapshot) return <EmptyLine label="该 Attempt 尚无不可变执行快照。" />;

  let parsed: Record<string, unknown> = {};
  try {
    const value = JSON.parse(snapshot.snapshotJson);
    if (value && typeof value === "object" && !Array.isArray(value)) parsed = value as Record<string, unknown>;
  } catch {
    parsed = {};
  }
  const fields = snapshotFields(parsed);
  const imageReference = typeof parsed.imageReference === "string" ? parsed.imageReference : typeof parsed.image === "string" ? parsed.image : "-";
  const imageDigest = typeof parsed.imageDigest === "string" ? parsed.imageDigest : "未写入快照";

  return (
    <section className="border border-slate-200 bg-white">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-3 py-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-950"><ShieldCheck className="h-4 w-4 text-teal-700" />不可变执行快照</div>
        <Badge variant="outline" className="border-slate-200 bg-white text-slate-600">{fields.resolvedFrom}</Badge>
      </div>
      <dl className="grid gap-3 px-3 py-3 text-xs sm:grid-cols-2 lg:grid-cols-4">
        <SnapshotMeta label="Runtime" value={fields.runtime} />
        <SnapshotMeta label="Provider" value={fields.provider} />
        <SnapshotMeta label="Model" value={fields.model} />
        <SnapshotMeta label="Protocol" value={fields.protocol} />
        <SnapshotMeta label="Tool Policy" value={fields.toolPolicy} />
        <SnapshotMeta label="Extension Set" value={fields.extensionSet} />
        <SnapshotMeta label="Image" value={imageReference} />
        <SnapshotMeta label="Image Digest" value={imageDigest} />
      </dl>
      <div className="border-t border-slate-200 px-3 py-3 text-xs text-slate-500">
        <div>Snapshot ID：<code className="break-all text-slate-700">{snapshot.snapshotId}</code></div>
        <div className="mt-1">Snapshot Hash：<code className="break-all text-slate-700">{snapshot.snapshotHash}</code></div>
        <div className="mt-1">Credential 环境变量：<code className="break-all text-slate-700">{fields.credentialVariable}</code></div>
      </div>
    </section>
  );
}

function SnapshotMeta({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-slate-500">{label}</dt>
      <dd className="mt-1 break-all font-medium text-slate-800">{value || "-"}</dd>
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

function ExecutionTracePanel({ taskId, stage }: { taskId: string; stage: RdTaskStageRun }) {
  const [runtimeEventsAvailable, setRuntimeEventsAvailable] = useState(false);
  return (
    <div className="space-y-4">
      <RuntimeExecutionEventsPanel
        taskId={taskId}
        stage={stage}
        onAvailabilityChange={setRuntimeEventsAvailable}
      />
      {!runtimeEventsAvailable ? <LegacyExecutionTracePanel taskId={taskId} stage={stage} /> : null}
    </div>
  );
}

const RUNTIME_EVENT_TYPES = [
  "RUNTIME_READY",
  "AGENT_STARTED",
  "AGENT_SETTLED",
  "RUNTIME_STOPPED",
  "TURN_STARTED",
  "TURN_COMPLETED",
  "ASSISTANT_TEXT_DELTA",
  "ASSISTANT_TEXT_COMPLETED",
  "TOOL_STARTED",
  "TOOL_PROGRESS",
  "TOOL_COMPLETED",
  "TOOL_BLOCKED",
  "PROVIDER_REQUESTED",
  "PROVIDER_RESPONDED",
  "PROVIDER_RETRYING",
  "COMPACTION_STARTED",
  "COMPACTION_COMPLETED",
  "RESOURCES_LOADED",
  "EXTENSION_FAILED",
  "USAGE_UPDATED",
  "RESULT_SUBMITTED",
  "RESULT_REJECTED",
  "ARTIFACT_WRITTEN",
  "PROTOCOL_ERROR"
] as const;

function RuntimeExecutionEventsPanel({
  taskId,
  stage,
  onAvailabilityChange
}: {
  taskId: string;
  stage: RdTaskStageRun;
  onAvailabilityChange: (available: boolean) => void;
}) {
  const [trace, setTrace] = useState<AgentRuntimeEventSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingHistory, setLoadingHistory] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [transport, setTransport] = useState<"SSE" | "POLL" | "ARCHIVED" | "NONE">("NONE");
  const cursorRef = useRef(0);
  const finalizedRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    let source: EventSource | null = null;
    let pollTimer: number | undefined;
    let polling = false;

    cursorRef.current = 0;
    finalizedRef.current = false;
    setTrace(null);
    setLoading(true);
    setLoadingHistory(true);
    setLoadingMore(false);
    setError("");
    setTransport("NONE");
    onAvailabilityChange(false);

    const mergeEvent = (event: AgentRuntimeEvent) => {
      if (cancelled) return;
      finalizedRef.current = finalizedRef.current
        || event.eventType === "AGENT_SETTLED"
        || event.eventType === "RUNTIME_STOPPED";
      cursorRef.current = Math.max(cursorRef.current, event.sequence || event.sourceSequence || 0);
      setTrace((current) => mergeRuntimeTrace(current, {
        version: 1,
        source: "LIVE",
        available: true,
        finalized: finalizedRef.current,
        truncated: current?.truncated || false,
        hasMore: current?.hasMore || false,
        nextSequence: cursorRef.current,
        events: [event]
      }));
      onAvailabilityChange(true);
      setLoading(false);
    };

    const schedulePoll = () => {
      if (cancelled || pollTimer || finalizedRef.current || !stage.running) return;
      pollTimer = window.setTimeout(() => {
        pollTimer = undefined;
        void poll();
      }, 1_500);
    };

    const poll = async (options?: { latest?: boolean }): Promise<AgentRuntimeEventSnapshot | null> => {
      if (cancelled || polling) return null;
      polling = true;
      try {
        const next = await getAgentRuntimeEvents(taskId, stage.stageRunId, {
          after: cursorRef.current,
          limit: 100,
          latest: Boolean(options?.latest && cursorRef.current === 0)
        });
        if (cancelled) return null;
        cursorRef.current = Math.max(cursorRef.current, next.nextSequence);
        finalizedRef.current = next.finalized;
        setTrace((current) => mergeRuntimeTrace(current, next));
        setTransport(next.source === "ARCHIVED" ? "ARCHIVED" : "POLL");
        setLoading(false);
        setError("");
        if (next.available) onAvailabilityChange(true);
        if (!next.finalized && stage.running && !source) schedulePoll();
        return next;
      } catch (cause) {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "运行时事件暂不可用");
          setLoading(false);
          if (stage.running && !source) schedulePoll();
        }
        return null;
      } finally {
        polling = false;
      }
    };

    const openSse = () => {
      if (cancelled || !stage.running || finalizedRef.current) return;
      if (pollTimer) {
        window.clearTimeout(pollTimer);
        pollTimer = undefined;
      }
      try {
        source = new EventSource(agentRuntimeEventsPath(taskId, stage.stageRunId, {
          after: cursorRef.current,
          limit: 100
        }));
        source.onopen = () => {
          if (!cancelled) setTransport("SSE");
        };
        const handleMessage = (message: MessageEvent<string>) => {
          try {
            mergeEvent(JSON.parse(message.data) as AgentRuntimeEvent);
          } catch {
            setError("运行时 SSE 事件格式无效");
          }
        };
        source.onmessage = handleMessage;
        for (const eventType of RUNTIME_EVENT_TYPES) {
          source.addEventListener(eventType, handleMessage as EventListener);
        }
        source.onerror = () => {
          source?.close();
          source = null;
          if (!cancelled && !finalizedRef.current) {
            setTransport("POLL");
            void poll();
          }
        };
      } catch (cause) {
        source = null;
        setTransport("POLL");
        setError(cause instanceof Error ? cause.message : "无法建立运行时 SSE");
        void poll();
      }
    };

    void (async () => {
      let next = await poll({ latest: true });
      while (next?.hasMore && next.source !== "ARCHIVED" && !cancelled) {
        next = await poll();
      }
      if (!cancelled) setLoadingHistory(false);
      if (!cancelled && stage.running && !finalizedRef.current) openSse();
    })();

    return () => {
      cancelled = true;
      if (pollTimer) window.clearTimeout(pollTimer);
      source?.close();
    };
  }, [onAvailabilityChange, stage.running, stage.stageRunId, taskId]);

  const loadMore = useCallback(async () => {
    if (!trace?.hasMore || loadingMore) return;
    setLoadingMore(true);
    try {
      const next = await getAgentRuntimeEvents(taskId, stage.stageRunId, {
        after: trace.nextSequence,
        limit: 200
      });
      cursorRef.current = Math.max(cursorRef.current, next.nextSequence);
      finalizedRef.current = finalizedRef.current || next.finalized;
      setTrace((current) => mergeRuntimeTrace(current, next));
      setTransport(next.source === "ARCHIVED" ? "ARCHIVED" : "POLL");
      setError("");
      if (next.available) onAvailabilityChange(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "加载更多运行时事件失败");
    } finally {
      setLoadingMore(false);
    }
  }, [loadingMore, onAvailabilityChange, stage.stageRunId, taskId, trace?.hasMore, trace?.nextSequence]);

  if (!stage.running && !loading && !trace?.available) return null;

  return (
    <section className="border border-slate-200 bg-white px-4 py-4 sm:px-5">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-950">
          <Radio className={cn("h-4 w-4", stage.running ? "text-teal-600" : "text-slate-500")} />
          运行时事件
        </div>
        <div className="flex items-center gap-2 text-xs text-slate-500">
          {transport !== "NONE" ? <Badge variant="outline" className="border-slate-200 bg-white text-slate-600">{transport === "SSE" ? "SSE 实时" : transport === "POLL" ? "轮询降级" : "已归档"}</Badge> : null}
          {trace?.events[0]?.runtimeType ? <span>{trace.events[0].runtimeType}</span> : null}
        </div>
      </div>
      {loading || loadingHistory ? <LoadingLine label={loadingHistory ? "正在定位最新记录" : "正在连接运行时事件"} /> : null}
      {!loading && !loadingHistory && error && !trace?.available ? <PanelError message={error} /> : null}
      {!loading && !loadingHistory && trace?.available && trace.events.length > 0 ? (
        <ReadableAgentTrace
          events={trace.events}
          streaming={stage.running && !trace.finalized}
          truncated={trace.truncated}
          hasMore={trace.hasMore}
          loadingMore={loadingMore}
          onLoadMore={loadMore}
        />
      ) : null}
      {!loading && !loadingHistory && !error && !trace?.available && stage.running ? <EmptyLine label="等待容器产生可见运行时事件。" /> : null}
    </section>
  );
}

function mergeRuntimeTrace(
  current: AgentRuntimeEventSnapshot | null,
  next: AgentRuntimeEventSnapshot
): AgentRuntimeEventSnapshot {
  const existing = current?.events || [];
  const seen = new Set(existing.map((event) => event.sequence || event.sourceSequence || 0));
  const additions = next.events.filter((event) => {
    const key = event.sequence || event.sourceSequence || 0;
    return key <= 0 || !seen.has(key);
  });
  const events = [...existing, ...additions].sort((left, right) => (
    (left.sequence || left.sourceSequence || 0) - (right.sequence || right.sourceSequence || 0)
  ));
  return {
    ...next,
    available: current?.available || next.available || events.length > 0,
    finalized: current?.finalized || next.finalized || events.some((event) => event.eventType === "RUNTIME_STOPPED" || event.eventType === "AGENT_SETTLED"),
    truncated: current?.truncated || next.truncated,
    nextSequence: Math.max(current?.nextSequence || 0, next.nextSequence || 0, ...events.map((event) => event.sequence || event.sourceSequence || 0)),
    events
  };
}

function LegacyExecutionTracePanel({ taskId, stage }: { taskId: string; stage: RdTaskStageRun }) {
  const [trace, setTrace] = useState<RdTaskExecutionTrace | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    let after = 0;

    const load = async () => {
      try {
        const next = await getRdTaskExecutionTrace(taskId, stage.stageRunId, { after, limit: 100 });
        if (cancelled) return;
        setTrace((current) => mergeTrace(current, next, after));
        setError("");
        after = next.source === "LIVE" ? next.nextSequence : 0;
        if (stage.running && !next.finalized) {
          timer = window.setTimeout(() => void load(), 1_500);
        }
      } catch (cause) {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "执行轨迹暂不可用");
          if (stage.running) {
            timer = window.setTimeout(() => void load(), 2_000);
          }
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    setTrace(null);
    setLoading(true);
    setError("");
    void load();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [stage.stageRunId, stage.running, taskId]);

  return (
    <div className="space-y-4 px-4 py-5 sm:px-5">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-950">
          {stage.running && !trace?.finalized ? <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" /> : <TerminalSquare className="h-4 w-4 text-slate-600" />}
          执行轨迹
        </div>
        {trace?.source ? <Badge variant="outline" className="border-slate-200 bg-white text-slate-600">{trace.source === "LIVE" ? "实时" : "已归档"}</Badge> : null}
      </div>

      {loading ? <LoadingLine label="正在读取执行轨迹" /> : null}
      {!loading && error ? <PanelError message={error} /> : null}
      {!loading && !error && trace?.truncated ? (
        <div className="border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          仅保留最近的执行事件。
        </div>
      ) : null}
      {!loading && !error && (!trace || !trace.available || trace.entries.length === 0) ? (
        <EmptyLine label={stage.running ? "等待容器产生可见事件。" : "该 Attempt 没有容器执行轨迹。评审/方案角色走一次性模型 HTTP 调用，事件只在 Coding/QA 的 Pi 容器里产生。可在「运行记录」查看 Provider 尝试。"} />
      ) : null}
      {!loading && !error && trace && trace.entries.length > 0 ? (
        <ol className="divide-y divide-slate-200 border-y border-slate-200">
          {trace.entries.map((entry) => (
            <li key={`${entry.sequence}-${entry.kind}-${entry.detail}`} className="flex min-w-0 gap-3 py-3">
              <TraceEventIcon entry={entry} />
              <div className="min-w-0">
                <div className={cn("text-sm font-medium", entry.error ? "text-rose-800" : "text-slate-800")}>{entry.label}</div>
                {entry.detail ? <p className="mt-1 break-words text-xs leading-5 text-slate-600">{entry.detail}</p> : null}
              </div>
            </li>
          ))}
        </ol>
      ) : null}
    </div>
  );
}

function mergeTrace(
  current: RdTaskExecutionTrace | null,
  next: RdTaskExecutionTrace,
  after: number
): RdTaskExecutionTrace {
  if (!current || after <= 0 || current.source !== next.source) return next;
  const seen = new Set(current.entries.map((entry) => entry.sequence));
  const entries = [...current.entries, ...next.entries.filter((entry) => !seen.has(entry.sequence))].slice(-200);
  return { ...next, entries };
}

function TraceEventIcon({ entry }: { entry: RdTaskExecutionTrace["entries"][number] }) {
  if (entry.error) return <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-rose-600" />;
  if (entry.kind === "RESULT") return <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />;
  if (entry.kind === "TOOL_STARTED" || entry.kind === "TOOL_COMPLETED") return <TerminalSquare className="mt-0.5 h-4 w-4 shrink-0 text-slate-500" />;
  return <Clock3 className="mt-0.5 h-4 w-4 shrink-0 text-teal-600" />;
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
  if (status === "CANCELLED") return <XCircle className="h-4 w-4 shrink-0 text-slate-500" aria-label="已取消" />;
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
