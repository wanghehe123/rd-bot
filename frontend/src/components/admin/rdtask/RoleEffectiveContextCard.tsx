import { lazy, Suspense, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Copy,
  Layers,
  ListTodo,
  LoaderCircle,
  ShieldCheck
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import {
  deriveInjectionBadge,
  deriveSourceLabel,
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

const MarkdownRenderer = lazy(() => (
  import("@/components/chat/MarkdownRenderer").then((module) => ({ default: module.MarkdownRenderer }))
));

export interface RoleEffectiveContextCardProps {
  stage?: RdTaskStageRun;
  promptStage?: RdTaskRolePromptStage;
  promptLoading: boolean;
  promptError: string;
}

export function RoleEffectiveContextCard({
  stage,
  promptStage,
  promptLoading,
  promptError
}: RoleEffectiveContextCardProps) {
  const [activeTab, setActiveTab] = useState<"effective" | "static" | "latestState">("effective");

  const effectiveContext = promptStage?.effectiveContext;
  const latestState = promptStage?.latestState;
  const runtimeType = stage?.runtimeType || promptStage?.runtimeType;
  const stageRunId = stage?.stageRunId || promptStage?.stageRunId || "";
  const attemptNo = stage?.attemptNo ?? promptStage?.attemptNo ?? 1;

  const injectionBadge = deriveInjectionBadge(effectiveContext, latestState, runtimeType);
  const source = effectiveContext?.source || latestState?.source;
  const finalized = effectiveContext?.finalized || latestState?.finalized;
  const sourceLabel = deriveSourceLabel(source, finalized);
  const isStale = isEffectiveContextStale(effectiveContext) || isEffectiveContextStale(latestState);
  const staleReason = effectiveContext?.staleReason || latestState?.staleReason || "状态投影已陈旧";

  const copyStageRunId = () => {
    if (!stageRunId) return;
    navigator.clipboard.writeText(stageRunId);
    toast.success("已复制 stageRunId");
  };

  return (
    <section className="border border-slate-200 bg-white" aria-labelledby="role-context-title">
      <header className="border-b border-slate-200 px-4 py-3 sm:px-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <h4 id="role-context-title" className="text-sm font-semibold text-slate-950">
              角色有效上下文
            </h4>
            {runtimeType === "PI" ? (
              <Badge variant="outline" className="border-teal-300 bg-teal-50 font-medium text-teal-800">
                PI Runtime
              </Badge>
            ) : runtimeType ? (
              <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-700">
                {runtimeType}
              </Badge>
            ) : null}
            <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-700">
              Attempt {attemptNo}
            </Badge>
            {stageRunId ? (
              <button
                type="button"
                onClick={copyStageRunId}
                className="inline-flex items-center gap-1 font-mono text-xs text-slate-500 hover:text-slate-900"
                title={`点击复制完整 stageRunId: ${stageRunId}`}
              >
                <span>{shortHash(stageRunId, 10)}</span>
                <Copy className="h-3 w-3" />
              </button>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <InjectionStatusBadge badge={injectionBadge} />
            {latestState?.sequence !== undefined && latestState.sequence > 0 ? (
              <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-700 font-mono text-xs">
                Seq #{latestState.sequence}
              </Badge>
            ) : null}
            {sourceLabel ? (
              <Badge variant="outline" className="border-slate-200 bg-white text-slate-600">
                {sourceLabel}
              </Badge>
            ) : null}
            {isStale ? (
              <Badge
                variant="outline"
                className="border-amber-300 bg-amber-50 text-amber-800"
                title={staleReason}
              >
                投影陈旧
              </Badge>
            ) : null}
          </div>
        </div>

        <details className="mt-2 text-xs text-slate-500">
          <summary className="cursor-pointer font-medium hover:text-slate-700">
            查看 Hash 与标识信息
          </summary>
          <div className="mt-2 grid gap-1.5 rounded border border-slate-200 bg-slate-50 p-2 font-mono text-[11px]">
            <div>Prompt Hash: <span className="text-slate-800">{effectiveContext?.promptContentHash || promptStage?.prompt?.contentHash || "未提供"}</span></div>
            <div>State Hash: <span className="text-slate-800">{effectiveContext?.stateContentHash || latestState?.contentHash || "未提供"}</span></div>
            <div>Injected Block Hash: <span className="text-slate-800">{effectiveContext?.injectedBlockHash || "未提供"}</span></div>
            <div>Effective Content Hash: <span className="text-slate-800">{effectiveContext?.contentHash || "未提供"}</span></div>
          </div>
        </details>
      </header>

      <Tabs value={activeTab} onValueChange={(val) => setActiveTab(val as typeof activeTab)}>
        <div className="border-b border-slate-200 bg-slate-50/50 px-4 pt-2 sm:px-5">
          <TabsList className="h-9 w-full justify-start gap-2 bg-transparent p-0">
            <TabsTrigger
              value="effective"
              className="h-9 rounded-none border-b-2 border-transparent px-3 text-xs text-slate-600 data-[state=active]:border-teal-600 data-[state=active]:bg-transparent data-[state=active]:font-semibold data-[state=active]:text-teal-900"
            >
              有效上下文
            </TabsTrigger>
            <TabsTrigger
              value="static"
              className="h-9 rounded-none border-b-2 border-transparent px-3 text-xs text-slate-600 data-[state=active]:border-teal-600 data-[state=active]:bg-transparent data-[state=active]:font-semibold data-[state=active]:text-teal-900"
            >
              静态 Prompt
            </TabsTrigger>
            <TabsTrigger
              value="latestState"
              className="h-9 rounded-none border-b-2 border-transparent px-3 text-xs text-slate-600 data-[state=active]:border-teal-600 data-[state=active]:bg-transparent data-[state=active]:font-semibold data-[state=active]:text-teal-900"
            >
              最新状态
            </TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="effective" className="m-0 p-4 sm:p-5">
          {promptLoading ? <LoadingLine label="正在加载有效上下文" /> : null}
          {!promptLoading && promptError ? <PanelError message={promptError} /> : null}
          {!promptLoading && !promptError && (!effectiveContext || !effectiveContext.available) ? (
            <div className="space-y-3">
              <EmptyLine
                label={
                  effectiveContext?.unavailableReason
                  || (!promptStage
                    ? "当前 Attempt 尚无已绑定的 Prompt 读模型。"
                    : runtimeType !== "PI"
                    ? "当前 Attempt 不是 PI，状态栏不适用"
                    : "已记录最新状态，但尚无可证明的注入上下文")
                }
              />
              <div className="border-l-2 border-slate-300 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                当前无法展示有效上下文。您可以切换至「
                <button
                  type="button"
                  className="font-medium text-teal-700 underline underline-offset-2 hover:text-teal-900"
                  onClick={() => setActiveTab("static")}
                >
                  静态 Prompt
                </button>
                」查看派发时静态指令，或切换至「
                <button
                  type="button"
                  className="font-medium text-teal-700 underline underline-offset-2 hover:text-teal-900"
                  onClick={() => setActiveTab("latestState")}
                >
                  最新状态
                </button>
                」查看结构化状态快照。
              </div>
            </div>
          ) : null}

          {!promptLoading && !promptError && effectiveContext?.available ? (
            <div className="space-y-3">
              <div className="grid gap-3 border-y border-slate-200 py-3 text-xs sm:grid-cols-2 lg:grid-cols-4">
                <Metric
                  label="组合顺序"
                  value={effectiveContext.compositionOrder?.join(" → ") || "PROMPT_SNAPSHOT → AGENT_STATE"}
                />
                <Metric
                  label="注入序列"
                  value={`Injection #${effectiveContext.injectionSequence} (State #${effectiveContext.stateSequence})`}
                  mono
                />
                <Metric
                  label="注入块 Hash"
                  value={shortHash(effectiveContext.injectedBlockHash) || "-"}
                  mono
                />
                <Metric
                  label="预览长度"
                  value={`${effectiveContext.previewLength.toLocaleString()} 字符`}
                />
              </div>

              <div className="max-h-[460px] overflow-auto border border-slate-200 bg-slate-50/60 p-4 text-sm leading-relaxed text-slate-700">
                <Suspense fallback={<LoadingLine label="正在渲染 Markdown" />}>
                  <MarkdownRenderer content={effectiveContext.contentPreview} />
                </Suspense>
              </div>

              {effectiveContext.truncated ? (
                <p className="text-xs text-amber-700">
                  安全预览已截断，原始长度 {effectiveContext.contentLength.toLocaleString()} 字符，当前展示 {effectiveContext.previewLength.toLocaleString()} 字符。
                </p>
              ) : null}
            </div>
          ) : null}
        </TabsContent>

        <TabsContent value="static" className="m-0 p-4 sm:p-5">
          <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
            这是派发时静态指令，不含运行中最新状态。
          </div>
          {promptLoading ? <LoadingLine label="正在加载静态 Prompt" /> : null}
          {!promptLoading && promptError ? <PanelError message={promptError} /> : null}
          {!promptLoading && !promptError && !promptStage ? (
            <EmptyLine label="当前 Attempt 尚无已绑定的 Prompt 读模型。" />
          ) : null}
          {!promptLoading && !promptError && promptStage ? (
            promptStage.prompt?.available ? (
              <div className="mt-3 space-y-3">
                <div className="grid gap-3 border-y border-slate-200 py-3 text-xs sm:grid-cols-2 lg:grid-cols-4">
                  <Metric label="Provider" value={promptStage.providerName || "-"} mono />
                  <Metric label="Prompt 产物" value={promptStage.prompt.artifactId || "-"} mono />
                  <Metric label="上下文包" value={promptStage.context?.packageId || "-"} mono />
                  <Metric label="预览长度" value={`${promptStage.prompt.previewLength.toLocaleString()} 字符`} />
                </div>
                <div className="max-h-[460px] overflow-auto border border-slate-200 bg-slate-50/60 p-4 text-sm leading-relaxed text-slate-700">
                  <Suspense fallback={<LoadingLine label="正在渲染 Markdown" />}>
                    <MarkdownRenderer content={promptStage.prompt.contentPreview} />
                  </Suspense>
                </div>
                {promptStage.prompt.truncated ? (
                  <p className="text-xs text-amber-700">
                    安全预览已截断，原始长度 {promptStage.prompt.contentLength.toLocaleString()} 字符。
                  </p>
                ) : null}
              </div>
            ) : (
              <EmptyLine label={promptStage.prompt?.unavailableReason || "当前 Attempt 暂无可审计 Prompt。"} />
            )
          ) : null}
        </TabsContent>

        <TabsContent value="latestState" className="m-0 p-4 sm:p-5">
          {promptLoading ? <LoadingLine label="正在加载最新状态" /> : null}
          {!promptLoading && promptError ? <PanelError message={promptError} /> : null}
          {!promptLoading && !promptError && (!latestState || !latestState.available) ? (
            <EmptyLine
              label={
                latestState?.unavailableReason
                || (runtimeType !== "PI"
                  ? "当前 Attempt 不是 PI，状态栏不适用"
                  : "当前 Attempt 暂无最新状态快照。")
              }
            />
          ) : null}

          {!promptLoading && !promptError && latestState?.available ? (
            <AgentLatestStatePanel state={latestState} />
          ) : null}
        </TabsContent>
      </Tabs>
    </section>
  );
}

function InjectionStatusBadge({ badge }: { badge: ReturnType<typeof deriveInjectionBadge> }) {
  const toneClass = {
    success: "border-teal-200 bg-teal-50 text-teal-700",
    warning: "border-amber-200 bg-amber-50 text-amber-800",
    neutral: "border-slate-200 bg-slate-50 text-slate-600"
  }[badge.tone];

  return <Badge variant="outline" className={toneClass}>{badge.label}</Badge>;
}

export function AgentLatestStatePanel({ state }: { state: NonNullable<RdTaskRolePromptStage["latestState"]> }) {
  return (
    <div className="space-y-4">
      <div className="grid gap-3 border-y border-slate-200 py-3 sm:grid-cols-2 lg:grid-cols-3">
        <Metric label="当前目标" value={state.currentGoal || "-"} />
        <Metric label="执行阶段" value={state.phase || "-"} />
        <Metric label="结果状态" value={state.resultStatus || "-"} />
        <Metric label="任务开始时间" value={state.taskStartedAt || "-"} />
        <Metric label="阶段开始时间" value={state.stageStartedAt || "-"} />
        <Metric
          label="最新更新时间"
          value={
            state.generatedAtEpochMillis
              ? new Date(state.generatedAtEpochMillis).toLocaleString("zh-CN", { hour12: false })
              : "-"
          }
        />
      </div>

      {state.blocker ? (
        <div className="border-l-2 border-rose-500 bg-rose-50 px-3 py-2 text-xs text-rose-900">
          <span className="font-semibold">阻断项：</span>{state.blocker}
        </div>
      ) : null}

      <div className="border border-slate-200 bg-slate-50/50 p-3">
        <h5 className="mb-2 text-xs font-semibold text-slate-900">资源与预算</h5>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <Metric label="Token 消耗" value={formatBudgetToken(state.budget)} />
          <Metric label="上下文字符" value={formatBudgetContext(state.budget)} />
          <Metric label="截止时间" value={formatBudgetDeadline(state.budget)} />
        </div>
      </div>

      <div>
        <div className="flex items-center gap-2 mb-2">
          <ListTodo className="h-4 w-4 text-slate-700" />
          <h5 className="text-sm font-semibold text-slate-950">TODO 列表</h5>
        </div>
        <AgentTodoList todos={state.todos} />
      </div>

      {state.recentErrors && state.recentErrors.length > 0 ? (
        <details className="border border-slate-200 bg-white">
          <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700">
            最近错误记录（{state.recentErrors.length}）
          </summary>
          <div className="divide-y divide-slate-200 border-t border-slate-200">
            {state.recentErrors.map((err, idx) => (
              <div key={idx} className="p-3 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-rose-900">{err.toolName || "工具错误"}</span>
                  <span className="text-slate-500">{err.timestamp ? String(err.timestamp) : ""}</span>
                </div>
                <p className="mt-1 whitespace-pre-wrap text-slate-700">{err.summary}</p>
              </div>
            ))}
          </div>
        </details>
      ) : null}

      <details className="border border-slate-200 bg-white">
        <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700">
          查看安全原始状态
        </summary>
        <div className="border-t border-slate-200 p-3 text-xs space-y-1">
          <p className="font-mono text-slate-500">Hash: {state.contentHash || "未提供"}</p>
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
        <div key={todo.id || `${todo.title}-${index}`} className="py-2.5 space-y-1">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <TodoStatusBadge status={todo.status} />
              <span className="break-words text-sm font-medium text-slate-900">{todo.title}</span>
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
              <span className="text-xs text-slate-500">{todo.evidenceCount} 项证据</span>
            ) : null}
          </div>

          {todo.acceptanceReferences && todo.acceptanceReferences.length > 0 ? (
            <div className="flex flex-wrap items-center gap-1 text-[11px] text-slate-600">
              <span className="text-slate-400">验收引用：</span>
              {todo.acceptanceReferences.map((ref, idx) => (
                <span key={idx} className="rounded bg-slate-100 px-1 py-0.5 font-mono text-[10px] text-slate-700">
                  {ref}
                </span>
              ))}
            </div>
          ) : null}

          {todo.blockerReason ? (
            <p className="rounded border border-rose-100 bg-rose-50 p-1.5 text-xs font-medium text-rose-800">
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
  return <Badge variant="outline" className={cn("text-[11px]", current.className)}>{current.label}</Badge>;
}

function Metric({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-slate-500">{label}</div>
      <div className={cn("mt-1 break-words text-sm text-slate-800", mono && "font-mono text-xs")} title={value}>
        {value || "-"}
      </div>
    </div>
  );
}

function LoadingLine({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2 py-4 text-sm text-slate-500">
      <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" />
      {label}
    </div>
  );
}

function EmptyLine({ label }: { label: string }) {
  return <div className="border-y border-dashed border-slate-200 py-4 text-sm text-slate-500">{label}</div>;
}

function PanelError({ message }: { message: string }) {
  return <div className="border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-sm text-amber-900">{message}</div>;
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
