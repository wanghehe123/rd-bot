import { useState } from "react";
import {
  AlertTriangle,
  Clock3,
  Copy,
  Layers,
  ListTodo,
  LoaderCircle,
  ShieldCheck
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  deriveInjectionBadge,
  deriveSourceLabel,
  isActiveStageStatus,
  isEffectiveContextStale,
  shortHash,
  sortAgentTodos
} from "@/pages/admin/rdtask/roleWorkbenchModel";
import type {
  RdTaskAgentStateBudget,
  RdTaskAgentTodo,
  RdTaskRolePromptStage,
  RdTaskStageRun
} from "@/services/rdTaskService";

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

export interface RoleAgentStateCardProps {
  stage?: RdTaskStageRun;
  promptStage?: RdTaskRolePromptStage;
  promptLoading: boolean;
  promptError: string;
  isLatestAttempt?: boolean;
}

export function RoleAgentStateCard({
  stage,
  promptStage,
  promptLoading,
  promptError,
  isLatestAttempt = true
}: RoleAgentStateCardProps) {
  const [mobileExpanded, setMobileExpanded] = useState(false);

  const effectiveContext = promptStage?.effectiveContext;
  const latestState = promptStage?.latestState;
  const runtimeType = stage?.runtimeType || promptStage?.runtimeType;
  const stageRunId = stage?.stageRunId || promptStage?.stageRunId || "";
  const attemptNo = stage?.attemptNo ?? promptStage?.attemptNo ?? 1;
  const role = stage?.role || promptStage?.role || "";
  const roleName = ROLE_LABEL[role] || role || "Agent";

  const isRunning = Boolean(stage?.running || (stage && isActiveStageStatus(stage.status)));
  const title = isRunning ? "当前运行状态" : isLatestAttempt ? "最新状态" : "最后状态";

  const injectionBadge = deriveInjectionBadge(effectiveContext, latestState, runtimeType);
  const source = latestState?.source || effectiveContext?.source;
  const finalized = latestState?.finalized || effectiveContext?.finalized;
  const sourceLabel = deriveSourceLabel(source, finalized);
  const isStale = isEffectiveContextStale(latestState) || isEffectiveContextStale(effectiveContext);
  const staleReason = latestState?.staleReason || effectiveContext?.staleReason || "状态投影已陈旧";

  const copyStageRunId = () => {
    if (!stageRunId) return;
    navigator.clipboard.writeText(stageRunId);
    toast.success("已复制 stageRunId");
  };

  return (
    <section
      className="border border-slate-200 bg-white shadow-sm"
      aria-labelledby="agent-state-card-title"
    >
      <header className="border-b border-slate-200 bg-slate-50/70 px-4 py-3 sm:px-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2 min-w-0">
            <h4 id="agent-state-card-title" className="text-sm font-semibold text-slate-950">
              {title}
            </h4>
            <Badge variant="outline" className="border-slate-300 bg-white font-medium text-slate-800 text-xs">
              {roleName} · Attempt {attemptNo}
            </Badge>
            {runtimeType === "PI" ? (
              <Badge variant="outline" className="border-teal-300 bg-teal-50 font-medium text-teal-800 text-[11px]">
                PI Runtime
              </Badge>
            ) : runtimeType ? (
              <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-700 text-[11px]">
                {runtimeType}
              </Badge>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            {stage?.status ? (
              <Badge
                variant="outline"
                className={cn(
                  "text-[11px]",
                  stage.status === "SUCCEEDED"
                    ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                    : stage.status.startsWith("FAILED")
                    ? "border-rose-200 bg-rose-50 text-rose-700"
                    : isRunning
                    ? "border-teal-200 bg-teal-50 text-teal-700"
                    : "border-slate-200 bg-white text-slate-600"
                )}
              >
                {STATUS_LABEL[stage.status] || stage.status}
              </Badge>
            ) : null}
            {isStale ? (
              <Badge
                variant="outline"
                className="border-amber-300 bg-amber-50 text-amber-800 text-[11px]"
                title={staleReason}
              >
                投影陈旧
              </Badge>
            ) : null}
          </div>
        </div>

        {stageRunId ? (
          <div className="mt-1.5 flex items-center justify-between text-xs text-slate-500">
            <button
              type="button"
              onClick={copyStageRunId}
              className="inline-flex items-center gap-1 font-mono text-[11px] text-slate-500 hover:text-slate-900"
              title={`点击复制完整 stageRunId: ${stageRunId}`}
            >
              <span>{shortHash(stageRunId, 12)}</span>
              <Copy className="h-3 w-3" />
            </button>
            {sourceLabel ? (
              <span className="text-[11px] text-slate-400">{sourceLabel}</span>
            ) : null}
          </div>
        ) : null}

        {/* 390px/900px 移动端快速折叠开关 */}
        <div className="mt-2 block lg:hidden">
          <button
            type="button"
            onClick={() => setMobileExpanded((prev) => !prev)}
            className="w-full text-center text-xs font-medium text-teal-700 hover:text-teal-900 py-1 bg-white border border-slate-200 rounded"
          >
            {mobileExpanded ? "收起状态详情" : "展开完整状态与待办"}
          </button>
        </div>

        <details className="mt-2 text-xs text-slate-500 hidden lg:block">
          <summary className="cursor-pointer font-medium hover:text-slate-700">
            查看 Hash 与标识信息
          </summary>
          <div className="mt-2 grid gap-1 rounded border border-slate-200 bg-white p-2 font-mono text-[10px]">
            <div>Sequence: <span className="text-slate-800">{latestState?.sequence !== undefined && latestState.sequence > 0 ? `#${latestState.sequence}` : "未提供"}</span></div>
            <div>Prompt Hash: <span className="text-slate-800">{effectiveContext?.promptContentHash || promptStage?.prompt?.contentHash || "未提供"}</span></div>
            <div>State Hash: <span className="text-slate-800">{latestState?.contentHash || effectiveContext?.stateContentHash || "未提供"}</span></div>
            <div>Injection Hash: <span className="text-slate-800">{effectiveContext?.injectedBlockHash || "未提供"}</span></div>
          </div>
        </details>
      </header>

      <div className={cn("p-4 space-y-4", !mobileExpanded && "hidden lg:block")}>
        {promptLoading ? <LoadingLine label="正在加载状态快照" /> : null}
        {!promptLoading && promptError ? <PanelError message={promptError} /> : null}
        {!promptLoading && !promptError && (!latestState || !latestState.available) ? (
          <EmptyLine
            label={
              latestState?.unavailableReason
              || (runtimeType !== "PI"
                ? "当前 Attempt 不是 PI，状态栏不适用"
                : !promptStage
                ? "当前 Attempt 尚无已绑定的 Prompt 读模型。"
                : "当前 Attempt 暂无最新状态快照。")
            }
          />
        ) : null}

        {!promptLoading && !promptError && latestState?.available ? (
          <AgentLatestStatePanel state={latestState} />
        ) : null}
      </div>
    </section>
  );
}

export function AgentLatestStatePanel({ state }: { state: NonNullable<RdTaskRolePromptStage["latestState"]> }) {
  return (
    <div className="space-y-4">
      {state.blocker ? (
        <div className="border-l-2 border-rose-500 bg-rose-50 px-3 py-2 text-xs text-rose-900">
          <span className="font-semibold">阻断项：</span>{state.blocker}
        </div>
      ) : null}

      <div className="grid gap-2 border-y border-slate-200 py-3 text-xs sm:grid-cols-2">
        <Metric label="当前目标" value={state.currentGoal || "-"} />
        <Metric label="执行阶段" value={state.phase || "-"} />
        <Metric label="结果状态" value={state.resultStatus || "-"} />
        <Metric
          label="最新更新时间"
          value={
            state.generatedAtEpochMillis
              ? new Date(state.generatedAtEpochMillis).toLocaleString("zh-CN", { hour12: false })
              : "-"
          }
        />
        {state.taskStartedAt ? <Metric label="任务开始时间" value={state.taskStartedAt} /> : null}
        {state.stageStartedAt ? <Metric label="阶段开始时间" value={state.stageStartedAt} /> : null}
      </div>

      <details className="border border-slate-200 bg-white rounded">
        <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700">
          资源与预算详情
        </summary>
        <div className="border-t border-slate-200 bg-slate-50/50 p-3">
          <div className="grid gap-2 text-xs sm:grid-cols-2">
            <Metric label="Token 消耗" value={formatBudgetToken(state.budget)} />
            <Metric label="上下文字符" value={formatBudgetContext(state.budget)} />
            {state.budget?.deadlineEpochMillis ? (
              <Metric label="截止时间" value={formatBudgetDeadline(state.budget)} />
            ) : null}
          </div>
        </div>
      </details>

      <div>
        <div className="flex items-center gap-1.5 mb-2">
          <ListTodo className="h-4 w-4 text-slate-700" />
          <h5 className="text-xs font-semibold text-slate-950">TODO 列表</h5>
        </div>
        <AgentTodoList todos={state.todos} />
      </div>

      {state.recentErrors && state.recentErrors.length > 0 ? (
        <details className="border border-slate-200 bg-white rounded">
          <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700">
            最近错误记录（{state.recentErrors.length}）
          </summary>
          <div className="divide-y divide-slate-200 border-t border-slate-200">
            {state.recentErrors.map((err, idx) => (
              <div key={idx} className="p-2.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-rose-900">{err.toolName || "工具错误"}</span>
                  <span className="text-slate-400 text-[10px]">{err.timestamp ? String(err.timestamp) : ""}</span>
                </div>
                <p className="mt-1 whitespace-pre-wrap text-slate-700 text-[11px] leading-relaxed">{err.summary}</p>
              </div>
            ))}
          </div>
        </details>
      ) : null}

      <details className="border border-slate-200 bg-white rounded">
        <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700">
          查看安全原始状态
        </summary>
        <div className="border-t border-slate-200 p-3 text-xs space-y-1 font-mono text-[11px]">
          <p className="text-slate-500 break-all">Hash: {state.contentHash || "未提供"}</p>
          {state.previewTruncated ? <p className="text-amber-700">状态安全预览已截断。</p> : null}
        </div>
      </details>
    </div>
  );
}

export function AgentTodoList({ todos }: { todos?: RdTaskAgentTodo[] }) {
  if (!todos || todos.length === 0) {
    return <EmptyLine label="暂无 TODO 项。" />;
  }

  const sorted = sortAgentTodos(todos);

  return (
    <div className="divide-y divide-slate-200 border-y border-slate-200">
      {sorted.map((todo, index) => (
        <div key={todo.id || `${todo.title}-${index}`} className="py-2 space-y-1">
          <div className="flex flex-wrap items-center justify-between gap-1.5">
            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
              <TodoStatusBadge status={todo.status} />
              <span className="break-words text-xs font-medium text-slate-900">{todo.title}</span>
              {todo.required ? (
                <Badge variant="outline" className="border-amber-200 bg-amber-50 text-[10px] text-amber-800">
                  必须
                </Badge>
              ) : null}
              {todo.source ? (
                <Badge variant="outline" className="border-slate-200 bg-white text-[10px] text-slate-600">
                  {todo.source}
                </Badge>
              ) : null}
            </div>
            {todo.evidenceCount !== undefined && todo.evidenceCount > 0 ? (
              <span className="text-[11px] text-slate-500">{todo.evidenceCount} 项证据</span>
            ) : null}
          </div>

          {todo.acceptanceReferences && todo.acceptanceReferences.length > 0 ? (
            <div className="flex flex-wrap items-center gap-1 text-[10px] text-slate-600">
              <span className="text-slate-400">验收引用：</span>
              {todo.acceptanceReferences.map((ref, idx) => (
                <span key={idx} className="rounded bg-slate-100 px-1 py-0.5 font-mono text-[10px] text-slate-700">
                  {ref}
                </span>
              ))}
            </div>
          ) : null}

          {todo.blockerReason ? (
            <p className="rounded border border-rose-100 bg-rose-50 p-1.5 text-[11px] font-medium text-rose-800">
              阻断原因：{todo.blockerReason}
            </p>
          ) : null}
        </div>
      ))}
    </div>
  );
}

function TodoStatusBadge({ status }: { status?: string }) {
  const normalized = (status || "").toUpperCase();
  const config: Record<string, { label: string; className: string }> = {
    IN_PROGRESS: { label: "进行中", className: "border-teal-200 bg-teal-50 text-teal-700" },
    BLOCKED: { label: "已阻断", className: "border-rose-200 bg-rose-50 text-rose-700" },
    PENDING: { label: "待处理", className: "border-slate-200 bg-slate-50 text-slate-600" },
    DONE: { label: "已完成", className: "border-emerald-200 bg-emerald-50 text-emerald-700" },
    CANCELLED: { label: "已取消", className: "border-slate-200 bg-slate-100 text-slate-500" }
  };

  const current = config[normalized] || { label: status || "未知", className: "border-slate-200 bg-slate-50 text-slate-600" };
  return <Badge variant="outline" className={cn("text-[10px] px-1 py-0", current.className)}>{current.label}</Badge>;
}

function Metric({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <div className="text-[11px] text-slate-500">{label}</div>
      <div className={cn("mt-0.5 break-words text-xs text-slate-800", mono && "font-mono text-[11px]")} title={value}>
        {value || "-"}
      </div>
    </div>
  );
}

function LoadingLine({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2 py-4 text-xs text-slate-500">
      <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" />
      {label}
    </div>
  );
}

function EmptyLine({ label }: { label: string }) {
  return <div className="border-y border-dashed border-slate-200 py-3 text-xs text-slate-500">{label}</div>;
}

function PanelError({ message }: { message: string }) {
  return <div className="border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-xs text-amber-900">{message}</div>;
}

function formatBudgetToken(budget?: RdTaskAgentStateBudget): string {
  if (!budget || (budget.tokenUsed === undefined && budget.tokenMax === undefined)) {
    return "未提供";
  }
  if (budget.tokenUsed !== undefined && budget.tokenMax !== undefined && budget.tokenMax > 0) {
    const ratio = budget.tokenUsageRatio ?? (budget.tokenUsed / budget.tokenMax);
    return `${budget.tokenUsed.toLocaleString()} / ${budget.tokenMax.toLocaleString()} (${Math.round(ratio * 100)}%)`;
  }
  if (budget.tokenUsed !== undefined) {
    return `${budget.tokenUsed.toLocaleString()}`;
  }
  return "未提供";
}

function formatBudgetContext(budget?: RdTaskAgentStateBudget): string {
  if (!budget || (budget.contextUsedChars === undefined && budget.contextMaxChars === undefined)) {
    return "未提供";
  }
  if (budget.contextUsedChars !== undefined && budget.contextMaxChars !== undefined && budget.contextMaxChars > 0) {
    const ratio = budget.contextUsageRatio ?? (budget.contextUsedChars / budget.contextMaxChars);
    return `${budget.contextUsedChars.toLocaleString()} / ${budget.contextMaxChars.toLocaleString()} (${Math.round(ratio * 100)}%)`;
  }
  if (budget.contextUsedChars !== undefined) {
    return `${budget.contextUsedChars.toLocaleString()} chars`;
  }
  return "未提供";
}

function formatBudgetDeadline(budget?: RdTaskAgentStateBudget): string {
  if (budget?.deadlineEpochMillis && budget.deadlineEpochMillis > 0) {
    return new Date(budget.deadlineEpochMillis).toLocaleString("zh-CN", { hour12: false });
  }
  return "未提供";
}
