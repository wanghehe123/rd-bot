import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  Activity,
  AlertTriangle,
  Bug,
  CheckCircle2,
  ClipboardList,
  Clock3,
  Database,
  PlayCircle,
  RefreshCw,
  ShieldCheck
} from "lucide-react";

import { Empty, PageHeader } from "@/components/Ui";
import { ProjectScopeSelector } from "@/components/ProjectScopeSelector";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useAsyncData } from "@/hooks";
import { useProjectScope } from "@/hooks/useProjectScope";
import { getErrorMessage } from "@/utils/error";
import { formatFullDateTime } from "@/utils/time";
import { getProjectsPage, type RdProjectPage } from "@/services/projectService";
import { getDashboardOverview, type DashboardTaskSummary, type RdDashboardOverview } from "@/services/dashboardService";

import {
  formatAvailabilityRatio,
  formatCny,
  formatDashboardDuration,
  formatDashboardRole,
  formatDashboardStageStatus,
  formatDashboardStatus,
  dashboardDataForScope,
  taskListHref
} from "./dashboard/dashboardPresentation";

const EMPTY_PROJECT_PAGE: RdProjectPage = {
  records: [],
  total: 0,
  page: 1,
  pageSize: 100,
  pages: 0
};

type DashboardLoadState = {
  responseProjectId: string;
  data: RdDashboardOverview | null;
  loading: boolean;
  error: string | null;
};

function useDashboardOverview(projectId: string, ready: boolean) {
  const requestSequence = useRef(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [state, setState] = useState<DashboardLoadState>({
    responseProjectId: "",
    data: null,
    loading: false,
    error: null
  });

  const refresh = useCallback(() => {
    setRefreshVersion((version) => version + 1);
  }, []);

  useEffect(() => {
    if (!ready) {
      requestSequence.current += 1;
      setState({ responseProjectId: projectId, data: null, loading: false, error: null });
      return;
    }

    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setState({ responseProjectId: projectId, data: null, loading: true, error: null });

    void getDashboardOverview({ projectId: projectId === "all" ? undefined : projectId })
      .then((data) => {
        if (requestSequence.current === sequence) {
          setState({ responseProjectId: projectId, data, loading: false, error: null });
        }
      })
      .catch((error: unknown) => {
        if (requestSequence.current === sequence) {
          setState((current) => ({
            ...current,
            loading: false,
            error: getErrorMessage(error, "无法读取项目交付概览")
          }));
        }
      });
  }, [projectId, ready, refreshVersion]);

  return { ...state, data: dashboardDataForScope(state.responseProjectId, projectId, state.data), refresh };
}

export function DashboardPage() {
  const projectsState = useAsyncData(
    () => getProjectsPage({ page: 1, pageSize: 100 }),
    [],
    EMPTY_PROJECT_PAGE
  );
  const projects = projectsState.data.records;
  const projectScope = useProjectScope(projects, { allowAll: true });
  const scopeReady = projectScope.isAllProjects || Boolean(projectScope.projectId);
  const dashboardState = useDashboardOverview(projectScope.projectId, scopeReady);
  const overview = dashboardState.data;
  const selectedScopeName = overview?.projectName || (projectScope.isAllProjects
    ? "全部项目"
    : scopeReady
      ? "当前项目"
      : "未选择项目");

  const statusRows = useMemo(
    () => Object.entries(overview?.statusCounts || {})
      .filter(([, count]) => Number.isFinite(count) && count > 0)
      .sort(([leftStatus, leftCount], [rightStatus, rightCount]) => rightCount - leftCount || leftStatus.localeCompare(rightStatus)),
    [overview]
  );
  const statusMax = Math.max(1, ...statusRows.map(([, count]) => count));

  const refresh = useCallback(() => {
    void projectsState.refresh();
    dashboardState.refresh();
  }, [dashboardState, projectsState]);

  const dataUnavailable = Boolean(projectsState.error || dashboardState.error);
  const loading = projectsState.loading || dashboardState.loading;
  const dataStatusText = dataUnavailable
    ? "部分数据不可用"
    : !scopeReady
      ? "未选择项目"
      : loading
        ? "正在同步"
        : "数据已同步";
  const metrics = [
    {
      label: "需求总数",
      value: overview?.requirementCount,
      detail: "查看项目需求",
      icon: <ClipboardList aria-hidden="true" />,
      href: taskListHref(projectScope.projectId, { taskType: "REQUIREMENT" })
    },
    {
      label: "Bug 总数",
      value: overview?.bugFixCount,
      detail: "查看修复任务",
      icon: <Bug aria-hidden="true" />,
      href: taskListHref(projectScope.projectId, { taskType: "BUG_FIX" })
    },
    {
      label: "进行中",
      value: overview?.inProgressCount,
      detail: "查看当前项目任务",
      icon: <PlayCircle aria-hidden="true" />,
      href: taskListHref(projectScope.projectId)
    },
    {
      label: "等待人工",
      value: overview?.waitingHumanCount,
      detail: "审批与人工处理",
      icon: <Clock3 aria-hidden="true" />,
      href: taskListHref(projectScope.projectId)
    },
    {
      label: "已完成交付",
      value: overview?.completedCount,
      detail: "提交、合并或完成",
      icon: <CheckCircle2 aria-hidden="true" />,
      href: taskListHref(projectScope.projectId)
    },
    {
      label: "失败或阻塞",
      value: overview?.blockedCount,
      detail: "查看需要处置的任务",
      icon: <AlertTriangle aria-hidden="true" />,
      href: taskListHref(projectScope.projectId)
    }
  ];

  return (
    <div className="admin-page dashboard-page">
      <PageHeader
        title="Dashboard"
        description={`${selectedScopeName}的研发交付状态、执行进度与风险概览`}
        action={
          <div className="dashboard-header-actions">
            <ProjectScopeSelector
              projects={projects}
              projectId={projectScope.projectId}
              onProjectChange={projectScope.setProjectId}
              allowAll
              loading={projectsState.loading}
              unavailable={Boolean(projectsState.error)}
              className="dashboard-project-selector"
            />
            <Badge
              variant="outline"
              className={dataUnavailable ? "border-amber-200 bg-amber-50 text-amber-700" : "border-emerald-200 bg-emerald-50 text-emerald-700"}
            >
              {dataStatusText}
            </Badge>
            <Button
              type="button"
              variant="outline"
              onClick={refresh}
              disabled={loading}
              aria-label="刷新项目交付数据"
              title="刷新项目交付数据"
            >
              <RefreshCw className={loading ? "spin" : undefined} aria-hidden="true" />
              刷新
            </Button>
          </div>
        }
      />

      {projectsState.error || dashboardState.error ? (
        <div className="dashboard-data-notice" role="status">
          <AlertTriangle aria-hidden="true" />
          <span>{projectsState.error || dashboardState.error}</span>
          <Button type="button" variant="ghost" size="sm" onClick={refresh}>重试</Button>
        </div>
      ) : null}

      <section className="dashboard-metric-grid" aria-label="项目交付核心指标">
        {metrics.map((metric) => (
          <DashboardMetric
            key={metric.label}
            label={metric.label}
            value={metric.value}
            detail={metric.detail}
            icon={metric.icon}
            href={metric.href}
            loading={loading && !overview}
          />
        ))}
      </section>

      <div className="dashboard-grid dashboard-delivery-grid">
        <main className="dashboard-main">
          <Card className="dashboard-section-card">
            <CardHeader>
              <CardTitle>任务状态分布</CardTitle>
              <CardDescription>按任务主状态统计，点击状态可进入对应任务列表。</CardDescription>
            </CardHeader>
            <CardContent className="dashboard-status-content">
              {statusRows.length > 0 ? (
                <div className="dashboard-status-list">
                  {statusRows.map(([status, count]) => (
                    <Link
                      key={status}
                      to={taskListHref(projectScope.projectId, { status })}
                      className="dashboard-status-row"
                      aria-label={`${formatDashboardStatus(status)}，${count} 个任务`}
                    >
                      <span className="dashboard-status-label">{formatDashboardStatus(status)}</span>
                      <span className="dashboard-status-bar" aria-hidden="true">
                        <span style={{ width: `${Math.max(3, (count / statusMax) * 100)}%` }} />
                      </span>
                      <strong>{count.toLocaleString("zh-CN")}</strong>
                    </Link>
                  ))}
                </div>
              ) : (
                <DashboardEmpty loading={loading} message="当前范围暂无任务状态" />
              )}
            </CardContent>
          </Card>

          <Card className="dashboard-section-card">
            <CardHeader>
              <CardTitle>当前执行</CardTitle>
              <CardDescription>展示正在运行或尚未结束的真实交付阶段。</CardDescription>
            </CardHeader>
            <CardContent className="dashboard-table-content">
              {overview?.currentExecutions.length ? (
                <div className="dashboard-table-wrap">
                  <table className="dashboard-table">
                    <thead>
                      <tr>
                        <th>任务</th>
                        <th>当前阶段</th>
                        <th>Provider</th>
                        <th>进度</th>
                        <th>运行时长</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.currentExecutions.map((task) => <ExecutionRow key={task.taskId} task={task} />)}
                    </tbody>
                  </table>
                </div>
              ) : (
                <DashboardEmpty loading={loading} message="当前没有正在执行的任务" />
              )}
            </CardContent>
          </Card>

          <Card className="dashboard-section-card">
            <CardHeader>
              <CardTitle>近期交付</CardTitle>
              <CardDescription>已完成、已合并、失败或被驳回的任务会优先出现在这里。</CardDescription>
            </CardHeader>
            <CardContent className="dashboard-delivery-content">
              {overview?.recentDeliveries.length ? (
                <div className="dashboard-delivery-list">
                  {overview.recentDeliveries.map((task) => <RecentDeliveryRow key={task.taskId} task={task} />)}
                </div>
              ) : (
                <DashboardEmpty loading={loading} message="暂无近期交付记录" />
              )}
            </CardContent>
          </Card>
        </main>

        <aside className="dashboard-side">
          <Card className="dashboard-section-card dashboard-health-card">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <ShieldCheck className="h-4 w-4 text-teal-700" aria-hidden="true" />
                项目健康
              </CardTitle>
              <CardDescription>成功、阻塞、运行与预算均来自当前项目范围。</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="dashboard-health-stack">
                <HealthRatio value={overview?.successRate} />
                <HealthMetric
                  label="失败或阻塞"
                  value={overview ? overview.blockedCount.toLocaleString("zh-CN") : "--"}
                  href={taskListHref(projectScope.projectId)}
                />
                <HealthMetric
                  label="活跃告警"
                  value={overview?.runtime.available ? overview.runtime.activeAlertCount.toLocaleString("zh-CN") : "--"}
                />
                <HealthMetric
                  label="运行执行"
                  value={overview?.runtime.available ? overview.runtime.runningExecutionCount.toLocaleString("zh-CN") : "--"}
                />
                <HealthMetric
                  label="预计支出"
                  value={overview?.runtime.costAvailable ? formatCny(overview.runtime.estimatedSpendCny) : "--"}
                />
                <HealthMetric
                  label="数据生成"
                  value={overview ? formatFullDateTime(new Date(overview.generatedAtEpochMillis).toISOString()) : "--"}
                />
              </div>
            </CardContent>
          </Card>

          <Card className="dashboard-section-card dashboard-support-card">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Database className="h-4 w-4 text-teal-700" aria-hidden="true" />
                项目支撑资源
              </CardTitle>
              <CardDescription>知识库仅作为当前交付的辅助上下文。</CardDescription>
            </CardHeader>
            <CardContent>
              {overview?.knowledgeSupport.available ? (
                <div className="dashboard-knowledge-support">
                  <Link
                    to={`/admin/knowledge/${overview.knowledgeSupport.knowledgeBaseId}`}
                    className="dashboard-knowledge-name"
                    title={overview.knowledgeSupport.knowledgeBaseName || overview.knowledgeSupport.knowledgeBaseId}
                  >
                    {overview.knowledgeSupport.knowledgeBaseName || overview.knowledgeSupport.knowledgeBaseId}
                  </Link>
                  <div className="dashboard-knowledge-counts">
                    <span><strong>{overview.knowledgeSupport.documentCount.toLocaleString("zh-CN")}</strong> 文档</span>
                    <span><strong>{overview.knowledgeSupport.enabledDocumentCount.toLocaleString("zh-CN")}</strong> 已启用</span>
                  </div>
                </div>
              ) : (
                <DashboardEmpty loading={loading} message="当前范围没有可用的项目知识库摘要" />
              )}
            </CardContent>
          </Card>
        </aside>
      </div>
    </div>
  );
}

function DashboardMetric({
  label,
  value,
  detail,
  icon,
  href,
  loading
}: {
  label: string;
  value: number | undefined;
  detail: string;
  icon: ReactNode;
  href: string;
  loading: boolean;
}) {
  return (
    <Link to={href} className="dashboard-metric-card" aria-label={`${label}，${value == null ? "数据未就绪" : value}，${detail}`}>
      <span className="dashboard-metric-icon">{icon}</span>
      <span className="dashboard-metric-copy">
        <span className="dashboard-metric-label">{label}</span>
        <strong className={loading ? "dashboard-metric-value is-loading" : "dashboard-metric-value"}>
          {value == null ? "--" : value.toLocaleString("zh-CN")}
        </strong>
        <span className="dashboard-metric-detail">{detail}</span>
      </span>
    </Link>
  );
}

function ExecutionRow({ task }: { task: DashboardTaskSummary }) {
  const progress = task.progressTotal > 0 ? `${task.progressCompleted}/${task.progressTotal}` : "--";

  return (
    <tr>
      <td>
        <Link to={`/admin/rd-tasks/${task.taskId}`} className="dashboard-task-link" title={task.title}>
          {task.title || task.taskId}
        </Link>
        <span className="dashboard-task-meta">{task.taskType === "BUG_FIX" ? "Bug 修复" : "需求交付"}</span>
      </td>
      <td>
        <span className="dashboard-stage-role">{formatDashboardRole(task.currentRole)}</span>
        <span className="dashboard-task-meta">{formatDashboardStageStatus(task.currentStageStatus)}</span>
      </td>
      <td><code>{task.provider || "--"}</code></td>
      <td>
        <span>{progress}</span>
        {task.retryCount > 0 ? <span className="dashboard-task-meta">重试 {task.retryCount}</span> : null}
      </td>
      <td>
        <span>{formatDashboardDuration(task.elapsedMillis)}</span>
        {task.running ? <span className="dashboard-running">运行中</span> : null}
      </td>
    </tr>
  );
}

function RecentDeliveryRow({ task }: { task: DashboardTaskSummary }) {
  return (
    <Link to={`/admin/rd-tasks/${task.taskId}`} className="dashboard-delivery-row">
      <span className={`dashboard-status-chip ${statusTone(task.status)}`}>{formatDashboardStatus(task.status)}</span>
      <span className="dashboard-delivery-copy">
        <strong title={task.title}>{task.title || task.taskId}</strong>
        <span>{task.taskType === "BUG_FIX" ? "Bug 修复" : "需求交付"} · {formatFullDateTime(new Date(task.updateTimeEpochMillis).toISOString())}</span>
      </span>
      <span className="dashboard-delivery-arrow" aria-hidden="true">查看</span>
    </Link>
  );
}

function HealthRatio({ value }: { value: RdDashboardOverview["successRate"] | undefined }) {
  const ratioValue = value?.available && value.value != null ? value.value : null;
  const available = ratioValue != null;
  const width = available ? `${Math.max(0, Math.min(1, ratioValue)) * 100}%` : "0%";

  return (
    <div className="dashboard-health-ratio">
      <div>
        <span>交付成功率</span>
        <strong>{formatAvailabilityRatio(value)}</strong>
      </div>
      <div className="dashboard-health-meter" aria-label={available ? `交付成功率 ${formatAvailabilityRatio(value)}` : "交付成功率暂无终态样本"}>
        <span style={{ width }} />
      </div>
    </div>
  );
}

function HealthMetric({ label, value, href }: { label: string; value: string; href?: string }) {
  const content = <><span>{label}</span><strong>{value}</strong></>;
  return href ? <Link to={href} className="dashboard-health-metric">{content}</Link> : <div className="dashboard-health-metric">{content}</div>;
}

function DashboardEmpty({ loading, message }: { loading: boolean; message: string }) {
  return <div className="dashboard-empty">{loading ? <Activity className="spin" aria-hidden="true" /> : null}<Empty>{loading ? "正在读取项目交付数据" : message}</Empty></div>;
}

function statusTone(status: string): string {
  if (status.includes("FAILED") || status === "REJECTED" || status === "DEAD_LETTERED") {
    return "is-danger";
  }
  if (status === "WAITING_APPROVAL" || status === "WAITING_POLICY") {
    return "is-warning";
  }
  if (status === "COMMITTED" || status === "MERGED" || status === "COMPLETED") {
    return "is-success";
  }
  return "is-active";
}
