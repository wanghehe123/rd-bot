import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  Activity,
  Ban,
  BarChart3,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  CircleHelp,
  FileCode2,
  FlaskConical,
  Gauge,
  GitCompareArrows,
  Play,
  RefreshCw,
  RotateCcw,
  Search,
  ScrollText,
  ServerCog,
  TerminalSquare
} from "lucide-react";
import { toast } from "sonner";

import { Input } from "@/components/ui/input";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import {
  cancelEvaluationRun,
  createEvaluationRun,
  createTaskRunEvaluation,
  getEvaluationArtifactContent,
  getEvaluationArtifacts,
  getEvaluationCapabilities,
  getEvaluationLogs,
  getEvaluationRun,
  getEvaluationRuns,
  getEvaluationTimeline,
  isEvaluationRunActive,
  retryEvaluationRun,
  type EvaluationArtifact,
  type EvaluationCapabilities,
  type EvaluationDatasetKind,
  type EvaluationJudgeProvider,
  type EvaluationMetric,
  type EvaluationMetricsPayload,
  type EvaluationRun,
  type EvaluationRunHistoryQuery,
  type EvaluationRunPage,
  type EvaluationRunConfig,
  type EvaluationRunEvent,
  type EvaluationRunStatus,
  type EvaluationSummary,
  type EvaluationSource
} from "@/services/evaluationService";
import { getErrorMessage } from "@/utils/error";

function Badge({
  tone = "neutral",
  children,
  className = ""
}: {
  tone?: "neutral" | "success" | "warning" | "danger" | "info" | "primary" | "teal" | "blue" | "green" | "red";
  children: React.ReactNode;
  className?: string;
}) {
  const toneClasses: Record<string, string> = {
    neutral: "border-slate-200 bg-slate-50 text-slate-600",
    success: "border-emerald-200 bg-emerald-50 text-emerald-700",
    green: "border-emerald-200 bg-emerald-50 text-emerald-700",
    warning: "border-amber-200 bg-amber-50 text-amber-700",
    danger: "border-rose-200 bg-rose-50 text-rose-700",
    red: "border-rose-200 bg-rose-50 text-rose-700",
    info: "border-sky-200 bg-sky-50 text-sky-700",
    blue: "border-sky-200 bg-sky-50 text-sky-700",
    teal: "border-teal-200 bg-teal-50 text-teal-700",
    primary: "border-indigo-200 bg-indigo-50 text-indigo-700"
  };
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold ${
        toneClasses[tone] || toneClasses.neutral
      } ${className}`}
    >
      {children}
    </span>
  );
}

function Button({
  variant = "default",
  className = "",
  children,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "default" | "primary" | "ghost" | "danger" | "outline";
}) {
  const variantClasses: Record<string, string> = {
    default: "bg-slate-900 text-white hover:bg-slate-800",
    primary: "admin-primary-gradient text-white shadow-sm hover:opacity-95",
    ghost: "bg-transparent hover:bg-slate-100 text-slate-700",
    danger: "bg-rose-600 text-white hover:bg-rose-700",
    outline: "border border-slate-200 bg-white hover:bg-slate-50 text-slate-700"
  };
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed ${
        variantClasses[variant] || variantClasses.default
      } ${className}`}
    >
      {children}
    </button>
  );
}

function Card({
  title,
  description,
  className = "",
  children
}: {
  title?: React.ReactNode;
  description?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={`rounded-xl border border-slate-200/80 bg-white shadow-xs ${className}`}>
      {title || description ? (
        <div className="border-b border-slate-100 p-4">
          {title ? <h3 className="text-sm font-semibold text-slate-900">{title}</h3> : null}
          {description ? <p className="text-xs text-muted-foreground mt-0.5">{description}</p> : null}
        </div>
      ) : null}
      <div className="p-4">{children}</div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="field flex flex-col gap-1 text-xs">
      <span className="field-label font-medium text-slate-700">{label}</span>
      {children}
    </label>
  );
}

function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={`h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-xs shadow-xs focus:outline-none focus:ring-1 focus:ring-ring ${props.className || ""}`}
    />
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <div className="py-8 text-center text-xs text-muted-foreground">{children}</div>;
}

function Table({
  headers,
  children,
  minWidth
}: {
  headers: (string | React.ReactNode)[];
  children: React.ReactNode;
  minWidth?: number;
}) {
  return (
    <div className="ui-table-wrap overflow-x-auto">
      <table className="ui-table w-full text-left text-xs" style={{ minWidth }}>
        <thead className="ui-table-header border-b bg-slate-50/50">
          <tr>
            {headers.map((h, i) => (
              <th key={i} className="p-2.5 font-medium text-muted-foreground">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">{children}</tbody>
      </table>
    </div>
  );
}

import {
  evaluationDatasetKindLabel,
  evaluationGateLabel,
  evaluationJudgeLabel,
  evaluationJudgeStatusLabel,
  evaluationSourceLabel,
  evaluationStatusLabel,
  evaluationStatusTone,
  formatEvaluationDuration
} from "./evaluationPresentation";

const EMPTY_CAPABILITIES: EvaluationCapabilities = {
  enabled: false,
  sources: [],
  judgeProviders: [],
  datasets: [],
  defaultBaseUrl: "http://127.0.0.1:18080",
  maxSampleLimit: 10_000,
  maxTimeoutSeconds: 3_600
};

const EMPTY_RUN_PAGE: EvaluationRunPage = {
  records: [],
  total: 0,
  page: 1,
  pageSize: 10,
  pages: 0,
  overview: {
    total: 0,
    active: 0,
    gatePassed: 0,
    incomplete: 0,
    failed: 0
  }
};

const initialConfig = (capabilities: EvaluationCapabilities): EvaluationRunConfig => ({
  name: `RAG 回归 ${new Date().toLocaleDateString("zh-CN")}`,
  datasetId: capabilities.datasets[0]?.id || "",
  source: "FIXTURE",
  environmentId: "local-web",
  sampleLimit: 0,
  baseUrl: capabilities.defaultBaseUrl || "http://127.0.0.1:18080",
  ragLogPath: "rag-retrieval.jsonl",
  timeoutSeconds: 90,
  judgeProvider: "NONE",
  judgeLimit: 0,
  strictMissingRecords: false,
  baselineRunId: "",
  taskId: ""
});

type MetricRow = EvaluationMetric;

type EvaluationMetricsView = {
  metrics: MetricRow[];
  summary: EvaluationSummary | null;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseEvaluationMetricsPayload(metricsJson: string | undefined): EvaluationMetricsView {
  try {
    const parsed: unknown = JSON.parse(metricsJson || "[]");
    if (Array.isArray(parsed)) {
      return { metrics: parsed.filter(isRecord) as MetricRow[], summary: null };
    }
    if (isRecord(parsed)) {
      const envelope = parsed as Partial<EvaluationMetricsPayload>;
      return {
        metrics: Array.isArray(envelope.metrics) ? envelope.metrics : [],
        summary: isRecord(envelope.summary) ? envelope.summary as EvaluationSummary : null
      };
    }
  } catch {
    // Persisted legacy rows can contain an empty or malformed metrics string.
  }
  return { metrics: [], summary: null };
}

function evaluationConclusion(run: EvaluationRun) {
  const parsed = parseEvaluationMetricsPayload(run.metricsJson).summary;
  const gateStatus = parsed?.gateStatus
    || (run.status === "SUCCEEDED" ? (run.overallPassed ? "PASSED" : "NOT_PASSED") : "");
  const judgeStatus = parsed?.judgeStatus
    || parsed?.judge?.status
    || (run.config.judgeProvider === "NONE" ? "NOT_REQUESTED" : "");
  return { gateStatus, judgeStatus };
}

function evaluationDatasetKind(run: EvaluationRun, capabilities: EvaluationCapabilities): EvaluationDatasetKind {
  if (run.config.source === "TASK_RUN") return "TASK_RUN";
  return capabilities.datasets.find((dataset) => dataset.id === run.config.datasetId)?.kind || "SCORER_SMOKE";
}

const PHASES: EvaluationRunStatus[] = ["QUEUED", "RECORDING", "SCORING", "REPORTING", "DIFFING", "SUCCEEDED"];

const HISTORY_PAGE_SIZES = [10, 20, 50] as const;

const HISTORY_EXECUTION_STATUSES: EvaluationRunStatus[] = [
  "CREATED",
  "QUEUED",
  "RECORDING",
  "SCORING",
  "REPORTING",
  "DIFFING",
  "CANCEL_REQUESTED",
  "SUCCEEDED",
  "FAILED",
  "CANCELLED"
];

const HISTORY_GATE_STATUSES = ["PASSED", "NOT_PASSED", "INCOMPLETE", "PENDING"] as const;
const HISTORY_JUDGE_STATUSES = ["AVAILABLE", "FAILED", "PARTIAL", "SKIPPED", "NOT_REQUESTED", "PENDING"] as const;

type HistoryDatasetKind = "ALL" | EvaluationDatasetKind;
type HistoryGateStatus = "ALL" | (typeof HISTORY_GATE_STATUSES)[number];
type HistoryJudgeStatus = "ALL" | (typeof HISTORY_JUDGE_STATUSES)[number];

export function EvaluationPage() {
  const [searchParams] = useSearchParams();
  const [capabilities, setCapabilities] = useState(EMPTY_CAPABILITIES);
  const [config, setConfig] = useState<EvaluationRunConfig>(() => initialConfig(EMPTY_CAPABILITIES));
  const [runPage, setRunPage] = useState<EvaluationRunPage>(EMPTY_RUN_PAGE);
  const [successfulRuns, setSuccessfulRuns] = useState<EvaluationRun[]>([]);
  const [selectedRunId, setSelectedRunId] = useState(() => searchParams.get("runId") || "");
  const [selectedRun, setSelectedRun] = useState<EvaluationRun | null>(null);
  const [historyKeyword, setHistoryKeyword] = useState("");
  const [historyDatasetKind, setHistoryDatasetKind] = useState<HistoryDatasetKind>("ALL");
  const [historyExecutionStatus, setHistoryExecutionStatus] = useState<"ALL" | EvaluationRunStatus>("ALL");
  const [historyGateStatus, setHistoryGateStatus] = useState<HistoryGateStatus>("ALL");
  const [historyJudgeStatus, setHistoryJudgeStatus] = useState<HistoryJudgeStatus>("ALL");
  const [historyPageSize, setHistoryPageSize] = useState<number>(HISTORY_PAGE_SIZES[0]);
  const [historyPage, setHistoryPage] = useState(1);
  const [timeline, setTimeline] = useState<EvaluationRunEvent[]>([]);
  const [artifacts, setArtifacts] = useState<EvaluationArtifact[]>([]);
  const [logContent, setLogContent] = useState("");
  const [artifactContent, setArtifactContent] = useState("");
  const [artifactTitle, setArtifactTitle] = useState("");
  const [failureContent, setFailureContent] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const historyQuery = useMemo<EvaluationRunHistoryQuery>(() => ({
    keyword: historyKeyword.trim() || undefined,
    datasetKind: historyDatasetKind === "ALL" ? undefined : historyDatasetKind,
    status: historyExecutionStatus === "ALL" ? undefined : historyExecutionStatus,
    gateStatus: historyGateStatus === "ALL" ? undefined : historyGateStatus,
    judgeStatus: historyJudgeStatus === "ALL" ? undefined : historyJudgeStatus,
    page: historyPage,
    pageSize: historyPageSize
  }), [historyDatasetKind, historyExecutionStatus, historyGateStatus, historyJudgeStatus, historyKeyword, historyPage, historyPageSize]);
  const runs = runPage.records;

  const refreshRuns = useCallback(async (query: EvaluationRunHistoryQuery = historyQuery) => {
    const nextPage = await getEvaluationRuns(query);
    setRunPage(nextPage);
    setHistoryPage(nextPage.pages === 0 ? 1 : Math.min(nextPage.page, nextPage.pages));
    setSelectedRunId((current) => current || nextPage.records[0]?.runId || "");
    return nextPage;
  }, [historyQuery]);

  const refreshFirstHistoryPage = useCallback(
    () => refreshRuns({ ...historyQuery, page: 1 }),
    [historyQuery, refreshRuns]
  );

  const refreshBaselineRuns = useCallback(async () => {
    const nextPage = await getEvaluationRuns({ status: "SUCCEEDED", page: 1, pageSize: 100 });
    setSuccessfulRuns(nextPage.records);
  }, []);

  const refreshCapabilities = useCallback(async () => {
    const nextCapabilities = await getEvaluationCapabilities();
    setCapabilities(nextCapabilities);
    setConfig((current) => ({
      ...current,
      datasetId: current.datasetId || nextCapabilities.datasets[0]?.id || "",
      baseUrl: current.baseUrl || nextCapabilities.defaultBaseUrl
    }));
  }, []);

  const refreshDetail = useCallback(async (runId: string) => {
    if (!runId) return;
    const [run, events, nextArtifacts, logs] = await Promise.all([
      getEvaluationRun(runId),
      getEvaluationTimeline(runId),
      getEvaluationArtifacts(runId),
      getEvaluationLogs(runId)
    ]);
    setSelectedRun(run);
    setTimeline(events);
    setArtifacts(nextArtifacts);
    setLogContent(logs.content);
    const failures = nextArtifacts.find((artifact) => artifact.artifactType === "FAILURES");
    if (failures) {
      const content = await getEvaluationArtifactContent(runId, "FAILURES");
      setFailureContent(content.content);
    } else {
      setFailureContent("");
    }
  }, []);

  const refreshAll = useCallback(async () => {
    setError("");
    try {
      await Promise.all([refreshCapabilities(), refreshRuns(), refreshBaselineRuns()]);
    } catch (requestError) {
      setError(getErrorMessage(requestError, "加载评测控制台失败"));
    } finally {
      setLoading(false);
    }
  }, [refreshBaselineRuns, refreshCapabilities, refreshRuns]);

  useEffect(() => {
    void refreshAll();
  }, [refreshAll]);

  useEffect(() => {
    if (!selectedRunId) {
      setSelectedRun(null);
      return;
    }
    void refreshDetail(selectedRunId).catch((requestError) => setError(getErrorMessage(requestError, "加载评测详情失败")));
  }, [refreshDetail, selectedRunId]);

  useEffect(() => {
    const hasActiveRun = runs.some((run) => isEvaluationRunActive(run.status));
    if (!hasActiveRun && !(selectedRun && isEvaluationRunActive(selectedRun.status))) return;
    const timer = window.setInterval(() => {
      void refreshRuns()
        .then(() => selectedRunId ? refreshDetail(selectedRunId) : undefined)
        .catch((requestError) => setError(getErrorMessage(requestError, "刷新评测状态失败")));
    }, 2_000);
    return () => window.clearInterval(timer);
  }, [refreshDetail, refreshRuns, runs, selectedRun, selectedRunId]);

  const metricsPayload = useMemo(
    () => parseEvaluationMetricsPayload(selectedRun?.metricsJson),
    [selectedRun?.metricsJson]
  );
  const metrics = metricsPayload.metrics;
  const evaluationSummary = metricsPayload.summary;

  const summary = runPage.overview;
  const selectedConclusion = useMemo(
    () => selectedRun ? evaluationConclusion(selectedRun) : { gateStatus: "", judgeStatus: "" },
    [selectedRun]
  );

  const historyPageCount = Math.max(1, runPage.pages);
  const historyRangeStart = runs.length ? (runPage.page - 1) * runPage.pageSize + 1 : 0;
  const historyRangeEnd = runs.length ? historyRangeStart + runs.length - 1 : 0;
  const hasHistoryFilters = Boolean(historyKeyword.trim())
    || historyDatasetKind !== "ALL"
    || historyExecutionStatus !== "ALL"
    || historyGateStatus !== "ALL"
    || historyJudgeStatus !== "ALL";

  const updateConfig = <K extends keyof EvaluationRunConfig>(key: K, value: EvaluationRunConfig[K]) => {
    setConfig((current) => ({ ...current, [key]: value }));
  };

  const submit = async () => {
    if (!config.name.trim()
      || (config.source === "TASK_RUN" ? !config.taskId.trim() : !config.datasetId)) {
      toast.error(config.source === "TASK_RUN" ? "请填写评测名称和任务 ID" : "请填写评测名称并选择数据集");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      const created = config.source === "TASK_RUN"
        ? await createTaskRunEvaluation(config.taskId, config)
        : await createEvaluationRun(config);
      toast.success(`评测 ${created.runId} 已进入本地执行队列`);
      setHistoryPage(1);
      setSelectedRunId(created.runId);
      await Promise.all([refreshFirstHistoryPage(), refreshBaselineRuns()]);
      await refreshDetail(created.runId);
    } catch (requestError) {
      const message = getErrorMessage(requestError, "创建评测失败");
      setError(message);
      toast.error(message);
    } finally {
      setSubmitting(false);
    }
  };

  const cancel = async () => {
    if (!selectedRun || !window.confirm(`确认取消评测 ${selectedRun.runId}？本地子进程将被终止。`)) return;
    try {
      await cancelEvaluationRun(selectedRun.runId);
      toast.success("评测已取消");
      await Promise.all([refreshRuns(), refreshBaselineRuns()]);
      await refreshDetail(selectedRun.runId);
    } catch (requestError) {
      toast.error(getErrorMessage(requestError, "取消评测失败"));
    }
  };

  const retry = async () => {
    if (!selectedRun || !window.confirm(`确认基于 Attempt ${selectedRun.attemptNo} 创建新的评测尝试？`)) return;
    try {
      const retried = await retryEvaluationRun(selectedRun.runId);
      toast.success(`已创建 Attempt ${retried.attemptNo}`);
      setHistoryPage(1);
      setSelectedRunId(retried.runId);
      await Promise.all([refreshFirstHistoryPage(), refreshBaselineRuns()]);
    } catch (requestError) {
      toast.error(getErrorMessage(requestError, "重试评测失败"));
    }
  };

  const openArtifact = async (artifact: EvaluationArtifact) => {
    try {
      const content = await getEvaluationArtifactContent(selectedRunId, artifact.artifactType);
      setArtifactTitle(artifact.artifactType);
      setArtifactContent(content.content);
    } catch (requestError) {
      toast.error(getErrorMessage(requestError, "读取评测产物失败"));
    }
  };

  return (
    <div className="admin-page evaluation-page space-y-4">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <FlaskConical className="h-6 w-6 text-primary" />
            <span>评测</span>
          </h1>
          <p className="admin-page-subtitle">
            在管理端配置并运行本机 RAG/Agent 量化评测，持续查看过程、指标和失败样本
          </p>
        </div>
        <div className="admin-page-actions flex items-center gap-2">
          <Button variant="outline" onClick={() => void refreshAll()} disabled={loading} title="刷新评测控制台">
            <RefreshCw className={loading ? "spin mr-1.5 h-4 w-4" : "mr-1.5 h-4 w-4"} aria-hidden="true" />
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="evaluation-notice" role="alert"><CircleAlert aria-hidden="true" />{error}</div> : null}

      <section className="evaluation-kpis" aria-label="评测运行概览">
        <Kpi icon={FlaskConical} label="累计 Run" value={summary.total} tone="teal" />
        <Kpi icon={Activity} label="本地执行中" value={summary.active} tone="blue" />
        <Kpi icon={CheckCircle2} label="门禁通过" value={summary.gatePassed} tone="green" />
        <Kpi icon={CircleHelp} label="评测不完整" value={summary.incomplete} tone="blue" />
        <Kpi icon={CircleAlert} label="执行失败" value={summary.failed} tone="red" />
      </section>

      <section className="evaluation-command-deck" aria-label="创建评测">
        <div className="evaluation-deck-heading">
          <div><ServerCog aria-hidden="true" /><span><strong>本地评测配置</strong><small>只允许执行仓库内固定 Python 评测脚本</small></span></div>
          <Badge tone={capabilities.enabled ? "success" : "danger"}>{capabilities.enabled ? "LOCAL READY" : "DISABLED"}</Badge>
        </div>
        <div className="evaluation-form-grid">
          <Field label="评测名称"><Input value={config.name} maxLength={120} onChange={(event) => updateConfig("name", event.target.value)} /></Field>
          {config.source !== "TASK_RUN" ? <Field label="数据集">
            <Select value={config.datasetId} onChange={(event) => updateConfig("datasetId", event.target.value)}>
              <option value="">选择 JSONL 数据集</option>
              {capabilities.datasets.map((dataset) => <option key={dataset.id} value={dataset.id}>[{evaluationDatasetKindLabel(dataset.kind)}] {dataset.id} · {dataset.sampleCount} 条</option>)}
            </Select>
          </Field> : <Field label="任务 ID"><Input value={config.taskId} inputMode="numeric" placeholder="输入已执行的 RD 任务 ID" onChange={(event) => updateConfig("taskId", event.target.value.replace(/\D/g, ""))} /></Field>}
          <Field label="录制模式">
            <div className="evaluation-segmented" role="group" aria-label="录制模式">
              {(["FIXTURE", "RAG_HTTP", "TASK_RUN"] as EvaluationSource[]).map((source) => (
                <button key={source} type="button" className={config.source === source ? "is-active" : ""} onClick={() => setConfig((current) => ({
                  ...current,
                  source,
                  datasetId: source === "TASK_RUN" ? "task-run.generated.jsonl" : (current.datasetId === "task-run.generated.jsonl" ? capabilities.datasets[0]?.id || "" : current.datasetId),
                  taskId: source === "TASK_RUN" ? current.taskId : ""
                }))}>
                  {evaluationSourceLabel(source)}
                </button>
              ))}
            </div>
          </Field>
          <Field label="Judge">
            <Select value={config.judgeProvider} onChange={(event) => updateConfig("judgeProvider", event.target.value as EvaluationJudgeProvider)}>
              {(["NONE", "RAGAS", "OPENAI_COMPATIBLE"] as EvaluationJudgeProvider[]).map((provider) => (
                <option key={provider} value={provider}>{evaluationJudgeLabel(provider)}</option>
              ))}
            </Select>
          </Field>
          {config.source !== "TASK_RUN" ? <Field label="样本上限"><Input type="number" min={0} max={capabilities.maxSampleLimit} value={config.sampleLimit} onChange={(event) => updateConfig("sampleLimit", Number(event.target.value))} /></Field> : null}
          <Field label="Judge 样本上限"><Input type="number" min={0} max={capabilities.maxSampleLimit} value={config.judgeLimit} disabled={config.judgeProvider === "NONE"} onChange={(event) => updateConfig("judgeLimit", Number(event.target.value))} /></Field>
          <Field label="单阶段超时（秒）"><Input type="number" min={1} max={capabilities.maxTimeoutSeconds} value={config.timeoutSeconds} onChange={(event) => updateConfig("timeoutSeconds", Number(event.target.value))} /></Field>
          <Field label="环境标识"><Input value={config.environmentId} onChange={(event) => updateConfig("environmentId", event.target.value)} /></Field>
          {config.source === "RAG_HTTP" ? <>
            <Field label="本机 RD-Bot 地址"><Input value={config.baseUrl} onChange={(event) => updateConfig("baseUrl", event.target.value)} /></Field>
            <Field label="RAG 检索日志"><Input value={config.ragLogPath} onChange={(event) => updateConfig("ragLogPath", event.target.value)} /></Field>
          </> : null}
          <Field label="基线 Run">
            <Select value={config.baselineRunId} onChange={(event) => updateConfig("baselineRunId", event.target.value)}>
              <option value="">不生成 Diff</option>
              {successfulRuns.map((run) => <option key={run.runId} value={run.runId}>{run.name} · {run.runId}</option>)}
            </Select>
          </Field>
          {config.source !== "TASK_RUN" ? <label className="evaluation-check"><input type="checkbox" checked={config.strictMissingRecords} onChange={(event) => updateConfig("strictMissingRecords", event.target.checked)} /><span><strong>严格缺失检查</strong><small>部分录制时，缺少的样本也进入失败统计</small></span></label> : null}
        </div>
        <div className="evaluation-deck-actions">
          <span><TerminalSquare aria-hidden="true" />执行环境由后端环境变量管理，网页不会接收 Python 路径或密钥。</span>
          <Button variant="primary" onClick={() => void submit()} disabled={submitting || !capabilities.enabled || (config.source !== "TASK_RUN" && !capabilities.datasets.length)}>
            <Play aria-hidden="true" />{submitting ? "正在创建..." : "启动本地评测"}
          </Button>
        </div>
      </section>

      <div className="evaluation-workspace">
        <Card title="运行历史" description="每个重试都是新的 Attempt，终态 Run 保持不可变。" className="evaluation-run-card">
          <div className="evaluation-history-toolbar" aria-label="运行历史筛选">
            <Field label="搜索">
              <span className="evaluation-history-search">
                <Search aria-hidden="true" />
                <Input
                  value={historyKeyword}
                  placeholder="名称 / Run ID / Task ID"
                  onChange={(event) => {
                    setHistoryKeyword(event.target.value);
                    setHistoryPage(1);
                  }}
                />
              </span>
            </Field>
            <Field label="数据类型">
              <Select value={historyDatasetKind} onChange={(event) => {
                setHistoryDatasetKind(event.target.value as HistoryDatasetKind);
                setHistoryPage(1);
              }}>
                <option value="ALL">全部类型</option>
                <option value="QUALITY_BENCHMARK">质量基线</option>
                <option value="TASK_RUN">任务实跑</option>
                <option value="SCORER_SMOKE">评分器自检</option>
              </Select>
            </Field>
            <Field label="执行">
              <Select value={historyExecutionStatus} onChange={(event) => {
                setHistoryExecutionStatus(event.target.value as "ALL" | EvaluationRunStatus);
                setHistoryPage(1);
              }}>
                <option value="ALL">全部状态</option>
                {HISTORY_EXECUTION_STATUSES.map((status) => <option key={status} value={status}>{evaluationStatusLabel(status)}</option>)}
              </Select>
            </Field>
            <Field label="门禁">
              <Select value={historyGateStatus} onChange={(event) => {
                setHistoryGateStatus(event.target.value as HistoryGateStatus);
                setHistoryPage(1);
              }}>
                <option value="ALL">全部结果</option>
                {HISTORY_GATE_STATUSES.map((status) => <option key={status} value={status}>{status === "PENDING" ? "待计算" : evaluationGateLabel(status)}</option>)}
              </Select>
            </Field>
            <Field label="Judge">
              <Select value={historyJudgeStatus} onChange={(event) => {
                setHistoryJudgeStatus(event.target.value as HistoryJudgeStatus);
                setHistoryPage(1);
              }}>
                <option value="ALL">全部 Judge</option>
                {HISTORY_JUDGE_STATUSES.map((status) => <option key={status} value={status}>{status === "PENDING" ? "Judge 待执行" : evaluationJudgeStatusLabel(status)}</option>)}
              </Select>
            </Field>
            <Button
              className="evaluation-history-reset"
              variant="ghost"
              onClick={() => {
                setHistoryKeyword("");
                setHistoryDatasetKind("ALL");
                setHistoryExecutionStatus("ALL");
                setHistoryGateStatus("ALL");
                setHistoryJudgeStatus("ALL");
                setHistoryPage(1);
              }}
              disabled={!hasHistoryFilters}
              title="重置运行历史筛选"
              aria-label="重置运行历史筛选"
            >
              <RotateCcw aria-hidden="true" />
            </Button>
          </div>

          {runs.length ? <>
            <Table headers={["评测", "数据类型", "模式", "执行", "门禁", "Judge", "样本", "耗时", ""]} minWidth={1120}>
            {runs.map((run) => {
              const conclusion = evaluationConclusion(run);
              const datasetKind = evaluationDatasetKind(run, capabilities);
              return <tr key={run.runId} className={selectedRunId === run.runId ? "is-selected" : ""}>
                <td><button className="evaluation-run-link" type="button" onClick={() => setSelectedRunId(run.runId)}><strong>{run.name}</strong><span>{run.runId} · Attempt {run.attemptNo}</span></button></td>
                <td><Badge tone={datasetKind === "QUALITY_BENCHMARK" ? "success" : datasetKind === "TASK_RUN" ? "info" : "neutral"}>{evaluationDatasetKindLabel(datasetKind)}</Badge></td>
                <td><span>{evaluationSourceLabel(run.config.source)}</span><small>{run.config.taskId ? `Task ${run.config.taskId}` : evaluationJudgeLabel(run.config.judgeProvider)}</small></td>
                <td><Badge tone={evaluationStatusTone(run.status)}>{evaluationStatusLabel(run.status)}</Badge><div className="evaluation-mini-progress"><i style={{ width: `${run.progressPercent}%` }} /></div></td>
                <td>{conclusion.gateStatus ? <span className={conclusion.gateStatus === "PASSED" ? "evaluation-gate-pass" : "evaluation-gate-fail"}>{evaluationGateLabel(conclusion.gateStatus)}</span> : "--"}</td>
                <td>{conclusion.judgeStatus ? evaluationJudgeStatusLabel(conclusion.judgeStatus) : "--"}</td>
                <td>{run.sampleCount || "--"}</td>
                <td>{formatEvaluationDuration(run.startedAtEpochMillis, run.finishedAtEpochMillis)}</td>
                <td><Button variant="ghost" onClick={() => setSelectedRunId(run.runId)} title="查看评测详情"><ChevronRight aria-hidden="true" /></Button></td>
              </tr>;
            })}
            </Table>
            <div className="evaluation-history-pagination" aria-label="运行历史分页">
              <span>显示 {historyRangeStart}-{historyRangeEnd} / {runPage.total} 条</span>
              <div>
                <label className="evaluation-history-page-size"><span>每页</span><Select value={historyPageSize} onChange={(event) => {
                  setHistoryPageSize(Number(event.target.value));
                  setHistoryPage(1);
                }}>{HISTORY_PAGE_SIZES.map((size) => <option key={size} value={size}>{size}</option>)}</Select></label>
                <Button variant="ghost" onClick={() => setHistoryPage((current) => Math.max(1, current - 1))} disabled={historyPage <= 1} title="上一页" aria-label="上一页"><ChevronLeft aria-hidden="true" /></Button>
                <strong>第 {historyPage} / {historyPageCount} 页</strong>
                <Button variant="ghost" onClick={() => setHistoryPage((current) => Math.min(historyPageCount, current + 1))} disabled={historyPage >= historyPageCount} title="下一页" aria-label="下一页"><ChevronRight aria-hidden="true" /></Button>
              </div>
            </div>
          </> : <Empty>{loading ? "正在读取评测历史..." : hasHistoryFilters ? "没有符合当前筛选条件的评测 Run。" : "还没有评测 Run，从上方配置一次本地评测。"}</Empty>}
        </Card>

        <aside className="evaluation-inspector">
          {!selectedRun ? <Empty>选择一个评测 Run 查看执行过程和内容。</Empty> : <>
            <div className="evaluation-inspector-head">
              <div><span>RUN INSPECTOR</span><strong>{selectedRun.name}</strong><small>{selectedRun.runId} · Attempt {selectedRun.attemptNo}</small></div>
              <span className="evaluation-inspector-statuses">
                <Badge tone={evaluationStatusTone(selectedRun.status)}>{evaluationStatusLabel(selectedRun.status)}</Badge>
                {selectedConclusion.gateStatus ? <Badge tone={selectedConclusion.gateStatus === "PASSED" ? "success" : selectedConclusion.gateStatus === "INCOMPLETE" ? "warning" : "danger"}>{evaluationGateLabel(selectedConclusion.gateStatus)}</Badge> : null}
                {selectedConclusion.judgeStatus ? <Badge tone={["FAILED", "PARTIAL"].includes(selectedConclusion.judgeStatus) ? "warning" : "neutral"}>{evaluationJudgeStatusLabel(selectedConclusion.judgeStatus)}</Badge> : null}
              </span>
            </div>
            <div className="evaluation-phase-rail" aria-label="评测阶段轨道">
              {PHASES.map((phase, index) => {
                const currentIndex = PHASES.indexOf(selectedRun.status);
                const reached = selectedRun.status === "SUCCEEDED" || (currentIndex >= index && currentIndex >= 0);
                return <div key={phase} className={reached ? "is-reached" : ""}><i>{index + 1}</i><span>{evaluationStatusLabel(phase)}</span></div>;
              })}
            </div>
            <div className="evaluation-inspector-actions">
              {isEvaluationRunActive(selectedRun.status) ? <Button variant="danger" onClick={() => void cancel()}><Ban aria-hidden="true" />取消</Button> : null}
              {["FAILED", "CANCELLED", "SUCCEEDED"].includes(selectedRun.status) ? <Button variant="ghost" onClick={() => void retry()}><RotateCcw aria-hidden="true" />重试</Button> : null}
            </div>
            {selectedRun.errorMessage ? <div className="evaluation-run-error"><CircleAlert aria-hidden="true" /><span><strong>{selectedRun.errorCategory || "执行错误"}</strong>{selectedRun.errorMessage}</span></div> : null}
            <div className="evaluation-timeline">
              {timeline.map((event) => <div key={event.eventId}><i /><span><strong>{evaluationStatusLabel(event.toStatus)}</strong><small>{event.message}</small></span><time>{new Date(event.occurredAtEpochMillis).toLocaleTimeString("zh-CN")}</time></div>)}
            </div>
          </>}
        </aside>
      </div>

      {selectedRun ? <section className="evaluation-results">
        {evaluationSummary ? <section className="evaluation-summary-band" aria-label="简要评测报告">
          <div className="evaluation-summary-head">
            <div><span>RUN SUMMARY</span><strong>简要评测报告</strong></div>
            <Badge tone={evaluationSummary.gateStatus === "PASSED" ? "success" : evaluationSummary.gateStatus === "INCOMPLETE" ? "warning" : "danger"}>{evaluationGateLabel(evaluationSummary.gateStatus || "")}</Badge>
          </div>
          <div className="evaluation-summary-grid">
            <div className="evaluation-summary-conclusion">
              <strong>{evaluationSummary.headline || "评测完成，等待生成可读摘要。"}</strong>
              <span>本地指标 {evaluationSummary.localMetricCount ?? 0} 项，已通过 {evaluationSummary.passedMetricCount ?? 0} 项，未达标 {evaluationSummary.failedMetricCount ?? 0} 项，跳过 {evaluationSummary.skippedMetricCount ?? 0} 项。</span>
            </div>
            <div className="evaluation-summary-findings">
              <span>待处理</span>
              {evaluationSummary.failedMetrics?.length ? evaluationSummary.failedMetrics.slice(0, 3).map((metric) => <div key={metric.name}>
                <strong>{metric.label || metric.name}</strong>
                <small>{metric.reason || "未达到门槛"}</small>
                <em>{metric.nextAction || "查看 failures.jsonl"}</em>
              </div>) : <small>当前没有门禁失败指标。</small>}
            </div>
            <div className="evaluation-summary-judge">
              <span>Judge · {evaluationJudgeStatusLabel(evaluationSummary.judgeStatus || evaluationSummary.judge?.status || "SKIPPED")}</span>
              <strong>{evaluationSummary.judge?.headline || "本次未启用或未执行 Judge 评分。"}</strong>
              <small>Provider {evaluationSummary.judge?.provider || "none"} · 已评样本 {evaluationSummary.judge?.evaluatedSampleCount ?? 0}</small>
              {evaluationSummary.judge?.strengths?.length ? <p>优点：{evaluationSummary.judge.strengths.join("；")}</p> : null}
              {evaluationSummary.judge?.risks?.length ? <p>风险：{evaluationSummary.judge.risks.join("；")}</p> : null}
              {evaluationSummary.judge?.nextActions?.length ? <p>建议：{evaluationSummary.judge.nextActions.join("；")}</p> : null}
            </div>
          </div>
        </section> : null}
        <div className="evaluation-section-heading"><BarChart3 aria-hidden="true" /><span><strong>指标门禁</strong><small>来自 `_scores.json`，SKIPPED 不会伪装成模型评分。</small></span></div>
        <TooltipProvider delayDuration={250}>
          <div className="evaluation-metric-grid">
            {metrics.length ? metrics.map((metric) => <article key={metric.name} className={`evaluation-metric evaluation-metric--${(metric.status || "unknown").toLowerCase()}`}>
              <div className="evaluation-metric-head">
                <code>{metric.name}</code>
                <span className="evaluation-metric-head-actions">
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button className="evaluation-metric-help" type="button" aria-label={`${metric.label || metric.name} 指标说明`}>
                        <CircleHelp aria-hidden="true" />
                      </button>
                    </TooltipTrigger>
                    <TooltipContent side="top" className="evaluation-metric-tooltip">
                      <strong>{metric.label || metric.name}</strong>
                      <span><b>作用</b>{metric.purpose || "用于观察当前评测运行的质量信号。"}</span>
                      <span><b>计算</b>{metric.calculation || "按同名评分规则对参与评分的样本聚合计算。"}</span>
                      {metric.direction && metric.threshold != null ? <span><b>门槛</b>{metric.direction} {metric.threshold}</span> : null}
                    </TooltipContent>
                  </Tooltip>
                  <Badge tone={metric.status === "PASS" ? "success" : ["FAIL", "FAILED"].includes(metric.status || "") ? "danger" : "neutral"}>{metric.status || "N/A"}</Badge>
                </span>
              </div>
              <strong>{typeof metric.value === "number" ? metric.value.toFixed(3) : "--"}</strong>
              <span>{metric.label || (metric.direction && metric.threshold != null ? `门槛 ${metric.direction} ${metric.threshold}` : metric.reason || "观察指标")}</span>
            </article>) : <Empty>评测完成后显示量化指标。</Empty>}
          </div>
        </TooltipProvider>

        <div className="evaluation-output-grid">
          <Card title={<span className="evaluation-card-title"><TerminalSquare aria-hidden="true" />执行日志</span>} description="本地 record → score → report → diff 的实时输出。" className="evaluation-output-card">
            <pre className="evaluation-log">{logContent || "等待本地执行输出..."}</pre>
          </Card>
          <Card title={<span className="evaluation-card-title"><CircleAlert aria-hidden="true" />失败样本</span>} description="来自 failures.jsonl，可直接回流数据集修订。" className="evaluation-output-card">
            <pre className="evaluation-failures">{failureContent || "当前没有失败样本。"}</pre>
          </Card>
        </div>

        <Card title={<span className="evaluation-card-title"><FileCode2 aria-hidden="true" />评测产物</span>} description="只展示允许读取的报告与结构化文件，不暴露绝对路径。" className="evaluation-artifact-card">
          <div className="evaluation-artifacts">
            {artifacts.map((artifact) => <button key={artifact.artifactId} type="button" onClick={() => void openArtifact(artifact)}>
              <span>{artifact.artifactType === "DIFF" ? <GitCompareArrows aria-hidden="true" /> : artifact.artifactType === "REPORT" ? <ScrollText aria-hidden="true" /> : <FileCode2 aria-hidden="true" />}<strong>{artifact.artifactType}</strong></span>
              <small>{formatBytes(artifact.sizeBytes)} · {artifact.contentHash.slice(0, 12)}</small>
            </button>)}
            {!artifacts.length ? <Empty>执行完成后归档日志、记录、分数、报告和 Diff。</Empty> : null}
          </div>
          {artifactTitle ? <div className="evaluation-artifact-viewer"><div><strong>{artifactTitle}</strong><Button variant="ghost" onClick={() => { setArtifactTitle(""); setArtifactContent(""); }}>关闭</Button></div><pre>{artifactContent}</pre></div> : null}
        </Card>
      </section> : null}
    </div>
  );
}

function Kpi({ icon: Icon, label, value, tone }: { icon: typeof Gauge; label: string; value: number; tone: string }) {
  return <article className={`evaluation-kpi evaluation-kpi--${tone}`}><Icon aria-hidden="true" /><span><small>{label}</small><strong>{value}</strong></span></article>;
}

function formatBytes(bytes: number) {
  if (bytes < 1_024) return `${bytes} B`;
  if (bytes < 1_048_576) return `${(bytes / 1_024).toFixed(1)} KB`;
  return `${(bytes / 1_048_576).toFixed(1)} MB`;
}
