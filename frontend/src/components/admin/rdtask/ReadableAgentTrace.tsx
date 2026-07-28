import {
  lazy,
  startTransition,
  Suspense,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type MutableRefObject,
  type ReactNode,
  type UIEvent
} from "react";
import {
  AlertTriangle,
  ArrowDown,
  CheckCircle2,
  CircleAlert,
  FileWarning,
  LoaderCircle,
  TerminalSquare
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import {
  buildAgentTraceSteps,
  type AgentTraceStep,
  type NarrativeTraceStep,
  type ResultTraceStep,
  type SystemTraceStep,
  type ToolTraceStep
} from "@/components/admin/rdtask/agentTraceModel";
import { runtimeEventDetail, runtimeEventLabel } from "@/pages/admin/project/agentRuntimePresentation";
import type { AgentRuntimeEvent } from "@/services/executionTraceService";

const MarkdownRenderer = lazy(() => (
  import("@/components/chat/MarkdownRenderer").then((module) => ({ default: module.MarkdownRenderer }))
));

type TraceView = "process" | "tools" | "raw";

type ReadableAgentTraceProps = {
  events: AgentRuntimeEvent[];
  streaming: boolean;
  truncated: boolean;
  hasMore: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
};

const completedStepStyle: CSSProperties = {
  contentVisibility: "auto",
  containIntrinsicSize: "auto 96px"
};

export function ReadableAgentTrace({
  events,
  streaming,
  truncated,
  hasMore,
  loadingMore = false,
  onLoadMore
}: ReadableAgentTraceProps) {
  const [view, setView] = useState<TraceView>("process");
  const [followingLatest, setFollowingLatest] = useState(true);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const steps = useMemo(() => buildAgentTraceSteps({
    events,
    seenSequences: events.map((event) => event.sequence || event.sourceSequence || 0)
  }), [events]);
  const processSteps = useMemo(() => steps.filter(isProcessStep), [steps]);
  const toolSteps = useMemo(() => steps.filter((step): step is ToolTraceStep => step.kind === "TOOL"), [steps]);

  useEffect(() => {
    if (!followingLatest) return;
    const frame = window.requestAnimationFrame(() => {
      const container = scrollRef.current;
      if (container) container.scrollTop = container.scrollHeight;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [followingLatest, view, events.length, steps.length]);

  const handleScroll = (event: UIEvent<HTMLDivElement>) => {
    const container = event.currentTarget;
    const nearBottom = container.scrollHeight - container.clientHeight - container.scrollTop <= 80;
    setFollowingLatest(nearBottom);
  };

  const returnToLatest = () => {
    const container = scrollRef.current;
    if (container) container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
    setFollowingLatest(true);
  };

  return (
    <TooltipProvider delayDuration={300}>
      <Tabs value={view} onValueChange={(next) => startTransition(() => setView(next as TraceView))} className="mt-3 overflow-hidden border border-[#30363d] bg-[#0d1117] text-[#c9d1d9] shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[#30363d] bg-[#161b22] px-3 py-2">
          <TabsList aria-label="执行轨迹视图" className="h-8 rounded-md border border-[#30363d] bg-[#0d1117] p-0.5">
            <TabsTrigger value="process" className="px-2.5 text-xs text-[#8b949e] data-[state=active]:bg-[#21262d] data-[state=active]:text-[#f0f6fc]">过程</TabsTrigger>
            <TabsTrigger value="tools" className="px-2.5 text-xs text-[#8b949e] data-[state=active]:bg-[#21262d] data-[state=active]:text-[#f0f6fc]">工具</TabsTrigger>
            <TabsTrigger value="raw" className="px-2.5 text-xs text-[#8b949e] data-[state=active]:bg-[#21262d] data-[state=active]:text-[#f0f6fc]">原始</TabsTrigger>
          </TabsList>
          <div className="flex items-center gap-2 text-xs text-[#8b949e]">
            <Badge variant="outline" className="h-6 border-[#30363d] bg-[#0d1117] px-2 text-[11px] font-normal text-[#8b949e]">只读执行记录</Badge>
            {streaming ? <span className="inline-flex items-center gap-1.5"><LoaderCircle className="h-3.5 w-3.5 animate-spin text-[#58a6ff]" />实时更新</span> : null}
            {!followingLatest ? (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button type="button" variant="outline" size="icon" className="h-7 w-7 border-[#30363d] bg-[#21262d] text-[#c9d1d9] hover:bg-[#30363d] hover:text-[#f0f6fc]" onClick={returnToLatest} aria-label="回到最新">
                    <ArrowDown className="h-3.5 w-3.5" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>回到最新</TooltipContent>
              </Tooltip>
            ) : null}
          </div>
        </div>

        {truncated ? (
          <div className="border-b border-[#493a17] bg-[#2d2208] px-4 py-2 text-xs text-[#e3b341]">
            更早事件已超出当前保留范围。
          </div>
        ) : null}

        <TabsContent value="process" className="m-0">
          <TraceScroller scrollRef={scrollRef} onScroll={handleScroll}>
            <LoadMore hasMore={hasMore} loading={loadingMore} onLoadMore={onLoadMore} />
            {processSteps.length > 0 ? processSteps.map((step) => <ProcessStep key={step.id} step={step} />) : <EmptyTrace />}
            <LatestMarker streaming={streaming} />
          </TraceScroller>
        </TabsContent>

        <TabsContent value="tools" className="m-0">
          <TraceScroller scrollRef={scrollRef} onScroll={handleScroll}>
            <LoadMore hasMore={hasMore} loading={loadingMore} onLoadMore={onLoadMore} />
            {toolSteps.length > 0 ? toolSteps.map((step) => <ToolStep key={step.id} step={step} />) : <EmptyTrace label="当前 Attempt 尚未调用工具。" />}
            <LatestMarker streaming={streaming} />
          </TraceScroller>
        </TabsContent>

        <TabsContent value="raw" className="m-0">
          <TraceScroller scrollRef={scrollRef} onScroll={handleScroll}>
            <LoadMore hasMore={hasMore} loading={loadingMore} onLoadMore={onLoadMore} />
            {events.length > 0 ? events.map((event) => <RawEvent key={event.sequence || event.sourceSequence} event={event} />) : <EmptyTrace />}
            <LatestMarker streaming={streaming} />
          </TraceScroller>
        </TabsContent>
      </Tabs>
    </TooltipProvider>
  );
}

function TraceScroller({
  children,
  scrollRef,
  onScroll
}: {
  children: ReactNode;
  scrollRef: MutableRefObject<HTMLDivElement | null>;
  onScroll: (event: UIEvent<HTMLDivElement>) => void;
}) {
  return (
    <div ref={scrollRef} onScroll={onScroll} className="relative max-h-[680px] overflow-y-auto overscroll-contain bg-[#0d1117] [scrollbar-color:#484f58_#0d1117]">
      <div className="divide-y divide-[#21262d] px-4">{children}</div>
    </div>
  );
}

function ProcessStep({ step }: { step: NarrativeTraceStep | SystemTraceStep | ResultTraceStep }) {
  if (step.kind === "NARRATIVE") return <NarrativeStep step={step} />;
  if (step.kind === "SYSTEM") return <SystemStep step={step} />;
  return <ResultStep step={step} />;
}

function NarrativeStep({ step }: { step: NarrativeTraceStep }) {
  return (
    <article className="py-5" style={step.streaming ? undefined : completedStepStyle}>
      <div className="mb-2 flex items-center gap-2 text-[11px] font-medium text-[#8b949e]">
        <span className="h-1.5 w-1.5 rounded-full bg-[#58a6ff]" aria-hidden="true" />
        <span>过程说明</span>
        <span>{formatEventTime(step.startedAt)}</span>
        {step.streaming ? <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-[#3fb950]" aria-label="正在输出" /> : null}
      </div>
      {step.streaming ? (
        <p className="whitespace-pre-wrap break-words text-sm leading-7 text-[#c9d1d9]">{step.markdown}</p>
      ) : (
        <div className="dark">
          <Suspense fallback={<p className="whitespace-pre-wrap break-words text-sm leading-7 text-[#c9d1d9]">{step.markdown}</p>}>
            <MarkdownRenderer content={step.markdown} />
          </Suspense>
        </div>
      )}
    </article>
  );
}

function ToolStep({ step }: { step: ToolTraceStep }) {
  const failure = step.status === "FAILED" || step.status === "BLOCKED";
  const duration = step.completedAt ? formatDuration(step.startedAt, step.completedAt) : "运行中";
  return (
    <article className="py-3" style={step.completedAt ? completedStepStyle : undefined}>
      <details className={cn("group border", failure ? "border-[#f85149]/50 bg-[#2d1618]" : "border-[#30363d] bg-[#161b22]")}>
        <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 text-sm marker:content-none hover:bg-[#21262d]">
          {failure ? <AlertTriangle className="h-4 w-4 shrink-0 text-[#f85149]" /> : step.status === "SUCCEEDED" ? <CheckCircle2 className="h-4 w-4 shrink-0 text-[#3fb950]" /> : <TerminalSquare className="h-4 w-4 shrink-0 text-[#8b949e]" />}
          <code className="min-w-0 flex-1 break-all font-mono text-xs text-[#c9d1d9]">{step.displaySummary}</code>
          <span className={cn("shrink-0 text-[11px]", failure ? "text-[#ff7b72]" : "text-[#8b949e]")}>{duration}</span>
        </summary>
        <div className="border-t border-[#30363d] px-3 py-2 text-xs leading-5 text-[#8b949e]">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1"><span>{step.toolName}</span><span>#{step.firstSequence}-{step.lastSequence}</span><span>{toolStatusLabel(step.status)}</span></div>
          {step.outputPreview ? <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words border border-[#30363d] bg-[#0d1117] p-3 text-[11px] leading-5 text-[#c9d1d9]">{step.outputPreview}</pre> : null}
        </div>
      </details>
    </article>
  );
}

function SystemStep({ step }: { step: SystemTraceStep }) {
  return (
    <article className={cn("flex gap-2 py-3 text-sm", step.tone === "ERROR" ? "text-[#ff7b72]" : "text-[#e3b341]")} style={completedStepStyle}>
      {step.tone === "ERROR" ? <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" /> : <FileWarning className="mt-0.5 h-4 w-4 shrink-0" />}
      <div className="min-w-0"><div>{step.summary}</div><div className="mt-1 text-[11px] text-[#8b949e]">{formatEventTime(step.occurredAt)}</div></div>
    </article>
  );
}

function ResultStep({ step }: { step: ResultTraceStep }) {
  const rejected = step.status === "REJECTED";
  return (
    <article className={cn("flex gap-2 py-4 text-sm font-medium", rejected ? "text-[#ff7b72]" : "text-[#3fb950]")} style={completedStepStyle}>
      {rejected ? <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> : <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />}
      <div className="min-w-0"><div>{step.summary}</div><div className="mt-1 text-[11px] font-normal text-[#8b949e]">{formatEventTime(step.occurredAt)}</div></div>
    </article>
  );
}

function RawEvent({ event }: { event: AgentRuntimeEvent }) {
  const detail = rawEventDetail(event);
  return (
    <article className="py-3 text-xs" style={completedStepStyle}>
      <div className="flex flex-wrap items-center justify-between gap-2 text-[#c9d1d9]"><span className="font-medium">{runtimeEventLabel(event)}</span><span className="text-[11px] text-[#8b949e]">{formatEventTime(event.occurredAt)}</span></div>
      {detail ? <p className="mt-1 break-words leading-5 text-[#8b949e]">{detail}</p> : null}
      <p className="mt-1 break-all text-[11px] text-[#6e7681]">{event.provider || "-"}{event.model ? ` · ${event.model}` : ""} · #{event.sequence || event.sourceSequence || "-"}</p>
    </article>
  );
}

function LoadMore({ hasMore, loading, onLoadMore }: { hasMore: boolean; loading: boolean; onLoadMore?: () => void }) {
  if (!hasMore || !onLoadMore) return null;
  return <div className="flex justify-center border-b border-[#21262d] py-3"><Button type="button" variant="outline" size="sm" className="border-[#30363d] bg-[#161b22] text-[#c9d1d9] hover:bg-[#21262d] hover:text-[#f0f6fc]" onClick={onLoadMore} disabled={loading}>{loading ? "正在加载" : "加载更多记录"}</Button></div>;
}

function EmptyTrace({ label = "该 Attempt 尚无可展示的运行步骤。" }: { label?: string }) {
  return <div className="py-8 text-center text-sm text-[#8b949e]">{label}</div>;
}

function LatestMarker({ streaming }: { streaming: boolean }) {
  return (
    <div className="flex items-center gap-2 py-4 text-[11px] text-[#8b949e]" aria-live="polite">
      <span className={cn("h-1.5 w-1.5 rounded-full", streaming ? "animate-pulse bg-[#3fb950]" : "bg-[#58a6ff]")} aria-hidden="true" />
      <span>{streaming ? "实时更新中" : "最新记录"}</span>
    </div>
  );
}

function rawEventDetail(event: AgentRuntimeEvent): string {
  const displaySummary = event.payload.displaySummary;
  if (typeof displaySummary === "string" && displaySummary.trim()) return displaySummary.trim();
  return runtimeEventDetail(event);
}

function formatEventTime(value: string): string {
  if (!value) return "";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleTimeString();
}

function formatDuration(startedAt: string, completedAt: string): string {
  const elapsed = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  return Number.isFinite(elapsed) && elapsed >= 0 ? `${Math.max(1, Math.round(elapsed / 1000))}s` : "已完成";
}

function toolStatusLabel(status: ToolTraceStep["status"]): string {
  if (status === "SUCCEEDED") return "已完成";
  if (status === "FAILED") return "执行失败";
  if (status === "BLOCKED") return "已阻止";
  return "运行中";
}

function isProcessStep(step: AgentTraceStep): step is NarrativeTraceStep | SystemTraceStep | ResultTraceStep {
  return step.kind !== "TOOL";
}
