import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Activity, AlertTriangle, BarChart2, RefreshCw } from "lucide-react";

import { ProjectScopeSelector } from "@/components/ProjectScopeSelector";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useAsyncData } from "@/hooks";
import { useProjectScope } from "@/hooks/useProjectScope";
import { getErrorMessage } from "@/utils/error";
import { getProjectsPage, type RdProjectPage } from "@/services/projectService";
import {
  getDeliveryFailures,
  getDeliveryOverview,
  getDeliveryTasks,
  getDeliveryTimeseries,
  type DeliveryFailurePage,
  type DeliveryQuery,
  type DeliveryTaskPage,
  type DeliveryTimeseries
} from "@/services/deliveryObservabilityService";

import {
  deliveryVisualState,
  formatPercentile,
  formatRatio,
  isolateStaleResponse,
  type DeliveryVisualState
} from "./deliveryObservabilityPresentation";

const EMPTY_PROJECT_PAGE: RdProjectPage = {
  records: [],
  total: 0,
  page: 1,
  pageSize: 100,
  pages: 0
};

const WINDOWS = ["1h", "24h", "7d", "30d"] as const;

type LoadState<T> = {
  data: T | null;
  loading: boolean;
  error: string | null;
};

function allowlistedWindow(value: string | null): string {
  return WINDOWS.includes(value as (typeof WINDOWS)[number]) ? value as string : "24h";
}

function visualLabel(state: DeliveryVisualState, loading: boolean): string {
  if (loading) return "正在同步";
  if (state === "ok") return "数据可用";
  if (state === "stale") return "数据陈旧";
  if (state === "collector-failed" || state === "unavailable") return "不可用";
  return "无样本";
}

export function DeliveryObservabilityPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const projectsState = useAsyncData(
    () => getProjectsPage({ page: 1, pageSize: 100 }),
    [],
    EMPTY_PROJECT_PAGE
  );
  const projects = projectsState.data.records;
  const projectScope = useProjectScope(projects, { allowAll: true });
  const windowToken = allowlistedWindow(searchParams.get("window"));
  const role = searchParams.get("role")?.trim() || "";
  const runtime = searchParams.get("runtime")?.trim() || "";
  const provider = searchParams.get("provider")?.trim() || "";
  const failureCategory = searchParams.get("failureCategory")?.trim() || "";
  const page = Math.max(1, Number(searchParams.get("page") || "1") || 1);

  const setFilter = useCallback((key: string, value: string) => {
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    if (key !== "page") {
      next.delete("page");
    }
    setSearchParams(next, { replace: false });
  }, [searchParams, setSearchParams]);

  const query: DeliveryQuery = useMemo(() => ({
    projectId: projectScope.projectId,
    window: windowToken,
    role,
    runtime,
    provider,
    failureCategory,
    page,
    pageSize: 20
  }), [failureCategory, page, projectScope.projectId, provider, role, runtime, windowToken]);

  const overviewState = useScopedResource(
    query,
    (current) => getDeliveryOverview(current),
    [query.projectId, query.window, query.role, query.runtime, query.provider, query.failureCategory]
  );
  const timeseriesState = useScopedResource(
    query,
    (current) => getDeliveryTimeseries(current),
    [query.projectId, query.window]
  );
  const failuresState = useScopedResource(
    query,
    (current) => getDeliveryFailures(current),
    [query.projectId, query.window, query.failureCategory, query.page]
  );
  const tasksState = useScopedResource(
    query,
    (current) => getDeliveryTasks(current),
    [query.projectId, query.window, query.role, query.runtime, query.provider, query.failureCategory, query.page]
  );

  const overview = overviewState.data;
  const visualState = deliveryVisualState(overview?.dataQuality, Boolean(overview?.successRate.noSample));
  const loading = overviewState.loading || timeseriesState.loading;
  const loadError = overviewState.error || timeseriesState.error || failuresState.error || tasksState.error;

  const refresh = useCallback(() => {
    void projectsState.refresh();
    overviewState.refresh();
    timeseriesState.refresh();
    failuresState.refresh();
    tasksState.refresh();
  }, [failuresState, overviewState, projectsState, tasksState, timeseriesState]);

  const slowPhases = useMemo(() => {
    const latency = overview?.phaseLatency || {};
    return Object.entries(latency)
      .filter(([, metric]) => metric && !metric.noSample && metric.available)
      .sort((left, right) => (right[1].p95Seconds || 0) - (left[1].p95Seconds || 0));
  }, [overview]);

  return (
    <div className="admin-page observability-page space-y-4">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <BarChart2 className="h-6 w-6 text-primary" />
            <span>交付观测</span>
          </h1>
          <p className="admin-page-subtitle">
            项目范围内的吞吐、延迟、容量、成本和数据质量。下钻进入既有任务详情与执行追踪
          </p>
        </div>
        <div className="observability-header-actions flex flex-wrap items-center gap-2">
          <ProjectScopeSelector
            projects={projects}
            projectId={projectScope.projectId}
            onProjectChange={projectScope.setProjectId}
            allowAll
            loading={projectsState.loading}
            unavailable={Boolean(projectsState.error)}
            className="dashboard-project-selector"
          />
          <select
            aria-label="时间窗口"
            value={windowToken}
            onChange={(event) => setFilter("window", event.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs shadow-xs focus:outline-none focus:ring-1 focus:ring-ring"
          >
            {WINDOWS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
          <select
            aria-label="角色"
            value={role}
            onChange={(event) => setFilter("role", event.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs shadow-xs focus:outline-none focus:ring-1 focus:ring-ring"
          >
            <option value="">全部角色</option>
            <option value="REQUIREMENT_REVIEWER">需求评审</option>
            <option value="SOLUTION_ARCHITECT">方案架构</option>
            <option value="CODING_AGENT">编码 Agent</option>
            <option value="QA_AGENT">QA Agent</option>
          </select>
          <Badge
            variant="outline"
            className={
              visualState === "ok"
                ? "border-emerald-200 bg-emerald-50 text-emerald-700 text-xs"
                : visualState === "stale"
                ? "border-amber-200 bg-amber-50 text-amber-700 text-xs"
                : "border-slate-200 bg-slate-50 text-slate-600 text-xs"
            }
          >
            {visualLabel(visualState, loading)}
          </Badge>
          <Button type="button" variant="outline" onClick={refresh} disabled={loading} aria-label="刷新交付观测">
            <RefreshCw className={loading ? "spin mr-1.5 h-4 w-4" : "mr-1.5 h-4 w-4"} aria-hidden="true" />
            刷新
          </Button>
        </div>
      </div>

      {loadError ? (
        <div className="dashboard-data-notice" role="status" data-state="unavailable">
          <AlertTriangle aria-hidden="true" />
          <span>{loadError}</span>
          <Button type="button" variant="ghost" size="sm" onClick={refresh}>重试</Button>
        </div>
      ) : null}

      <section className="observability-quality" aria-label="数据质量" data-state={visualState}>
        {(overview?.dataQuality || []).map((item) => (
          <div key={item.source} className="observability-quality-item" data-source={item.source}>
            <strong>{item.source}</strong>
            <span>{item.available ? (item.stale ? "过期" : "可用") : "采集失败"}</span>
            {item.warning ? <em>{item.warning}</em> : null}
          </div>
        ))}
        {!overview && !loading ? <div className="py-6 text-center text-xs text-muted-foreground">暂无采集状态</div> : null}
      </section>

      <section className="observability-metric-grid" aria-label="吞吐与结果">
        <MetricCard label="已接受" value={overview?.acceptedCount} />
        <MetricCard label="终态" value={overview?.terminalCount} />
        <MetricCard label="运行中" value={overview?.runningCount} />
        <MetricCard label="成功率" value={formatRatio(overview?.successRate)} />
        <MetricCard label="QA 通过率" value={formatRatio(overview?.qaPassRate)} />
        <MetricCard label="PR 创建率" value={formatRatio(overview?.prCreationRate)} />
        <MetricCard label="人工介入率" value={formatRatio(overview?.humanInterventionRate)} />
        <MetricCard label="重试率" value={formatRatio(overview?.retryRate)} />
      </section>

      <div className="observability-grid">
        <Card>
          <CardHeader>
            <CardTitle>端到端延迟</CardTitle>
            <CardDescription>分位始终带样本数与窗口，P99 不足时单独标明。</CardDescription>
          </CardHeader>
          <CardContent>
            <p data-state={overview?.endToEndLatency?.noSample ? "no-sample" : visualState}>
              {formatPercentile(overview?.endToEndLatency, windowToken)}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>阶段瓶颈</CardTitle>
            <CardDescription>按 P95 排序。点击进入该阶段相关任务下钻。</CardDescription>
          </CardHeader>
          <CardContent>
            {slowPhases.length ? (
              <ul className="observability-phase-list">
                {slowPhases.map(([phase, metric]) => (
                  <li key={phase}>
                    <Link to={tasksHref(searchParams, { page: "1" })}>
                      {phase} · {formatPercentile(metric, windowToken)}
                    </Link>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="py-6 text-center text-xs text-muted-foreground">当前窗口无阶段样本</div>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>队列与容量</CardTitle>
            <CardDescription>积压来自 durable command ledger，容量为当前调度器快照。</CardDescription>
          </CardHeader>
          <CardContent>
            <p>积压 {overview?.queueBacklog ?? "—"} · 最老队列 {
              overview && Number.isFinite(overview.oldestQueueAgeSeconds)
                ? `${overview.oldestQueueAgeSeconds.toFixed(1)}s`
                : "无样本"
            }</p>
            <ul>
              {(overview?.capacities || []).map((capacity) => (
                <li key={capacity.resource}>
                  {capacity.resource}: {capacity.inUse}/{capacity.capacity}
                  {capacity.saturated ? " · 饱和" : ""}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Provider / 成本</CardTitle>
            <CardDescription>Token 为整数，成本为估算 CNY，不是账单。</CardDescription>
          </CardHeader>
          <CardContent>
            {(overview?.usageBuckets || []).length ? (
              <ul>
                {overview?.usageBuckets.map((bucket) => (
                  <li key={`${bucket.runtime}-${bucket.provider}-${bucket.role}`}>
                    {bucket.runtime}/{bucket.provider}/{bucket.role}:
                    in {bucket.inputTokens} / out {bucket.outputTokens} / cache {bucket.cacheTokens}
                    · {Number.isFinite(bucket.estimatedCostCny)
                      ? `¥${bucket.estimatedCostCny.toFixed(2)}`
                      : "成本不可用"}
                  </li>
                ))}
              </ul>
            ) : (
              <div className="py-6 text-center text-xs text-muted-foreground">当前窗口无用量样本</div>
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>失败类别</CardTitle>
          <CardDescription>点击类别过滤任务列表，再进入执行追踪或任务详情。</CardDescription>
        </CardHeader>
        <CardContent>
          <FailureTable
            page={failuresState.data}
            onSelect={(category) => setFilter("failureCategory", category)}
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>任务下钻</CardTitle>
          <CardDescription>只展示任务摘要。Prompt 与原始事件留在任务详情/执行追踪。</CardDescription>
        </CardHeader>
        <CardContent>
          <TaskTable page={tasksState.data} timeseries={timeseriesState.data} />
        </CardContent>
      </Card>
    </div>
  );
}

function MetricCard({ label, value }: { label: string; value: string | number | undefined }) {
  return (
    <article className="observability-metric-card">
      <span>{label}</span>
      <strong>{value ?? "—"}</strong>
    </article>
  );
}

function FailureTable({
  page,
  onSelect
}: {
  page: DeliveryFailurePage | null;
  onSelect: (category: string) => void;
}) {
  if (!page?.items.length) {
    return <div className="py-6 text-center text-xs text-muted-foreground">当前窗口无失败类别</div>;
  }
  return (
    <div className="ui-table-wrap">
      <table className="ui-table">
        <thead>
          <tr>
            <th>类别</th>
            <th>数量</th>
            <th>占比</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((row) => (
            <tr key={row.category}>
              <td>
                <button type="button" className="observability-link-button" onClick={() => onSelect(row.category)}>
                  {row.category}
                </button>
              </td>
              <td>{row.count}</td>
              <td>{Number.isFinite(row.share) ? `${(row.share * 100).toFixed(1)}%` : "无样本"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TaskTable({
  page,
  timeseries
}: {
  page: DeliveryTaskPage | null;
  timeseries: DeliveryTimeseries | null;
}) {
  if (!page?.items.length) {
    return (
      <div>
        {timeseries?.points.length ? <p>窗口内有吞吐样本，但当前过滤下没有任务行。</p> : null}
        <div className="py-6 text-center text-xs text-muted-foreground">当前过滤下没有可下钻任务</div>
      </div>
    );
  }
  return (
    <div className="ui-table-wrap">
      <table className="ui-table" style={{ minWidth: 720 }}>
        <thead>
          <tr>
            <th>任务</th>
            <th>状态</th>
            <th>角色</th>
            <th>耗时</th>
            <th>失败类别</th>
            <th>入口</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((row) => (
            <tr key={row.taskId}>
              <td>
                <div className="table-title">
                  <Activity aria-hidden="true" />
                  <span>{row.title || row.taskId}</span>
                </div>
              </td>
              <td>{row.status}</td>
              <td>{row.role || "—"}</td>
              <td>{Number.isFinite(row.durationSeconds) ? `${row.durationSeconds.toFixed(1)}s` : "无样本"}</td>
              <td>{row.failureCategory || "—"}</td>
              <td>
                <Link to={row.tracePath}>执行追踪</Link>
                {" · "}
                <Link to={row.taskPath}>任务详情</Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function tasksHref(searchParams: URLSearchParams, patch: Record<string, string>): string {
  const next = new URLSearchParams(searchParams);
  Object.entries(patch).forEach(([key, value]) => {
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
  });
  const query = next.toString();
  return query ? `/admin/observability?${query}` : "/admin/observability";
}

function useScopedResource<T extends { projectId?: string; window?: string }>(
  query: DeliveryQuery,
  loader: (query: DeliveryQuery) => Promise<T>,
  deps: unknown[]
) {
  const requestSequence = useRef(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [state, setState] = useState<LoadState<T>>({ data: null, loading: false, error: null });

  const refresh = useCallback(() => {
    setRefreshVersion((version) => version + 1);
  }, []);

  useEffect(() => {
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setState((current) => ({
      data: isolateStaleResponse(current.data, query.projectId || "all", query.window || "24h"),
      loading: true,
      error: null
    }));
    void loader(query)
      .then((data) => {
        if (requestSequence.current === sequence) {
          setState({ data, loading: false, error: null });
        }
      })
      .catch((error: unknown) => {
        if (requestSequence.current === sequence) {
          setState((current) => ({
            ...current,
            loading: false,
            error: getErrorMessage(error, "无法读取交付观测")
          }));
        }
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, refreshVersion]);

  return { ...state, refresh };
}
