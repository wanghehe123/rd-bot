import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  ClipboardList,
  Filter,
  FlaskConical,
  RefreshCw,
  Search,
  TimerReset,
} from "lucide-react";
import { toast } from "sonner";

import { ProjectScopeSelector } from "@/components/ProjectScopeSelector";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Empty, PageHeader } from "@/components/Ui";
import { useAsyncData } from "@/hooks";
import { useProjectScope } from "@/hooks/useProjectScope";
import { getErrorMessage } from "@/utils/error";
import { getProjectsPage, type RdProjectPage } from "@/services/projectService";
import {
  getExecutionTraceDetail,
  getExecutionTraces,
  type ExecutionTraceDetail,
  type ExecutionTracePageResult,
  type ExecutionTraceRecord
} from "@/services/executionTraceService";
import { createTaskRunEvaluation } from "@/services/evaluationService";

import {
  executionTraceQueryForScope,
  formatTraceDuration,
  traceProgressPercent,
  traceRoleLabel,
  traceStatusLabel
} from "./executionTracePresentation";

const EMPTY_PROJECT_PAGE: RdProjectPage = { records: [], total: 0, page: 1, pageSize: 100, pages: 0 };
const EMPTY_TRACE_PAGE: ExecutionTracePageResult = { records: [], total: 0, page: 1, pageSize: 20, pages: 0 };

const TASK_TYPES = ["BUG_FIX", "REQUIREMENT"];
const TASK_STATUSES = ["EXECUTING", "WAITING_APPROVAL", "VALIDATING", "COMPLETED", "MERGED", "REJECTED", "FAILED_RETRYABLE", "FAILED_NEEDS_HUMAN", "DEAD_LETTERED"];
const ROLES = ["BUG_EVIDENCE_COLLECTOR", "BUG_RAG_RETRIEVER", "BUG_ACCEPTANCE_PLANNER", "BUG_CODING_AGENT", "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"];

export function ExecutionTracePage() {
  const projectsState = useAsyncData(() => getProjectsPage({ page: 1, pageSize: 100 }), [], EMPTY_PROJECT_PAGE);
  const projectScope = useProjectScope(projectsState.data.records, { allowAll: true });
  const [taskType, setTaskType] = useState("all");
  const [status, setStatus] = useState("all");
  const [role, setRole] = useState("all");
  const [provider, setProvider] = useState("");
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);

  useEffect(() => setPage(1), [projectScope.projectId]);

  const traceState = useAsyncData(
    () => getExecutionTraces(executionTraceQueryForScope(projectScope.projectId, {
      taskType: taskType === "all" ? undefined : taskType,
      status: status === "all" ? undefined : status,
      role: role === "all" ? undefined : role,
      provider,
      keyword,
      page,
      pageSize: 20
    })),
    [projectScope.projectId, taskType, status, role, provider, keyword, page],
    EMPTY_TRACE_PAGE
  );

  const applySearch = () => {
    setKeyword(keywordInput.trim());
    setPage(1);
  };
  const refresh = () => {
    void projectsState.refresh();
    void traceState.refresh();
  };

  return (
    <div className="admin-page execution-trace-page">
      <PageHeader
        title="执行追踪"
        description="以交付任务为根，查看角色阶段、Provider、重试与状态推进。"
        action={
          <div className="trace-header-actions">
            <ProjectScopeSelector
              projects={projectsState.data.records}
              projectId={projectScope.projectId}
              onProjectChange={projectScope.setProjectId}
              allowAll
              loading={projectsState.loading}
              unavailable={Boolean(projectsState.error)}
            />
            <Button variant="outline" size="icon" onClick={refresh} aria-label="刷新执行追踪" title="刷新执行追踪">
              <RefreshCw className={traceState.loading ? "spin" : undefined} aria-hidden="true" />
            </Button>
          </div>
        }
      />

      <section className="trace-filter-strip" aria-label="执行追踪筛选">
        <div className="trace-search-field"><Search aria-hidden="true" /><Input value={keywordInput} onChange={(event) => setKeywordInput(event.target.value)} onKeyDown={(event) => event.key === "Enter" && applySearch()} placeholder="搜索任务标题或 ID" aria-label="搜索执行追踪" /></div>
        <Select value={taskType} onValueChange={(value) => { setTaskType(value); setPage(1); }}><SelectTrigger aria-label="筛选任务类型"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="all">全部类型</SelectItem>{TASK_TYPES.map((item) => <SelectItem key={item} value={item}>{item === "BUG_FIX" ? "Bug 修复" : "需求交付"}</SelectItem>)}</SelectContent></Select>
        <Select value={status} onValueChange={(value) => { setStatus(value); setPage(1); }}><SelectTrigger aria-label="筛选任务状态"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="all">全部状态</SelectItem>{TASK_STATUSES.map((item) => <SelectItem key={item} value={item}>{traceStatusLabel(item)}</SelectItem>)}</SelectContent></Select>
        <Select value={role} onValueChange={(value) => { setRole(value); setPage(1); }}><SelectTrigger aria-label="筛选当前角色"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="all">全部角色</SelectItem>{ROLES.map((item) => <SelectItem key={item} value={item}>{traceRoleLabel(item)}</SelectItem>)}</SelectContent></Select>
        <Input className="trace-provider-input" value={provider} onChange={(event) => { setProvider(event.target.value); setPage(1); }} placeholder="Provider" aria-label="筛选 Provider" />
        <Button variant="outline" onClick={applySearch}><Filter aria-hidden="true" />筛选</Button>
      </section>

      {projectsState.error || traceState.error ? <div className="trace-inline-notice" role="status">{projectsState.error || traceState.error}</div> : null}
      <Card className="trace-table-card">
        <CardHeader>
          <CardTitle>任务执行流</CardTitle>
          <CardDescription>当前列表已批量聚合阶段记录；点击任务查看完整阶段与审计链路。</CardDescription>
        </CardHeader>
        <CardContent>
          {traceState.data.records.length ? (
            <Table className="min-w-[1080px]">
              <TableHeader><TableRow><TableHead>任务</TableHead><TableHead>项目</TableHead><TableHead>状态</TableHead><TableHead>当前阶段</TableHead><TableHead>Provider</TableHead><TableHead>进度</TableHead><TableHead>耗时</TableHead><TableHead>重试</TableHead><TableHead className="text-right">查看</TableHead></TableRow></TableHeader>
              <TableBody>{traceState.data.records.map((trace) => <TraceRow key={trace.taskId} trace={trace} />)}</TableBody>
            </Table>
          ) : <Empty>{traceState.loading ? "正在加载执行追踪..." : "当前筛选范围没有执行记录。"}</Empty>}
          <TracePagination page={traceState.data.page} pages={traceState.data.pages} total={traceState.data.total} onPageChange={setPage} />
        </CardContent>
      </Card>
    </div>
  );
}

function TraceRow({ trace }: { trace: ExecutionTraceRecord }) {
  const percent = traceProgressPercent(trace.progressCompleted, trace.progressTotal);
  return (
    <TableRow>
      <TableCell><Link to={`/admin/traces/${trace.taskId}`} className="trace-task-link"><strong>{trace.title || trace.taskId}</strong><span>{trace.taskType === "BUG_FIX" ? "Bug 修复" : "需求交付"} · {trace.taskId}</span></Link></TableCell>
      <TableCell>{trace.projectName || trace.projectId || "未绑定"}</TableCell>
      <TableCell><span className={`trace-status-tag ${trace.blocked ? "is-blocked" : ""}`}>{trace.blocked ? "需要处置" : traceStatusLabel(trace.taskStatus)}</span></TableCell>
      <TableCell><span className="trace-stage-label">{traceRoleLabel(trace.currentRole)}</span><small>{trace.currentStageStatus || "待开始"}</small></TableCell>
      <TableCell><code>{trace.providerName || "--"}</code></TableCell>
      <TableCell><div className="trace-progress"><span><i style={{ width: `${percent}%` }} /></span><em>{trace.progressCompleted}/{trace.progressTotal}</em></div></TableCell>
      <TableCell>{formatTraceDuration(trace.elapsedMillis)}</TableCell>
      <TableCell>{trace.retryCount ? <span className="trace-retry-count"><TimerReset aria-hidden="true" />{trace.retryCount}</span> : "--"}</TableCell>
      <TableCell className="text-right"><Button asChild variant="ghost" size="icon" aria-label={`查看${trace.title || trace.taskId}`} title="查看执行详情"><Link to={`/admin/traces/${trace.taskId}`}><ChevronRight aria-hidden="true" /></Link></Button></TableCell>
    </TableRow>
  );
}

function TracePagination({ page, pages, total, onPageChange }: { page: number; pages: number; total: number; onPageChange: (page: number) => void }) {
  if (pages <= 1 && total <= 20) return null;
  return <div className="trace-pagination"><span>共 {total.toLocaleString("zh-CN")} 条</span><div><Button variant="outline" size="icon" disabled={page <= 1} onClick={() => onPageChange(page - 1)} aria-label="上一页"><ChevronLeft aria-hidden="true" /></Button><span>{page}/{Math.max(1, pages)}</span><Button variant="outline" size="icon" disabled={pages === 0 || page >= pages} onClick={() => onPageChange(page + 1)} aria-label="下一页"><ChevronRight aria-hidden="true" /></Button></div></div>;
}

export function ExecutionTraceDetailPage() {
  const { taskId = "" } = useParams();
  const navigate = useNavigate();
  const [evaluating, setEvaluating] = useState(false);
  const detailState = useAsyncData(() => getExecutionTraceDetail(taskId), [taskId], null);
  const detail = detailState.data;

  const evaluateCurrentRun = async () => {
    if (!taskId || evaluating) return;
    setEvaluating(true);
    try {
      const run = await createTaskRunEvaluation(taskId);
      toast.success(`执行评测 ${run.runId} 已进入队列`);
      navigate(`/admin/evaluations?runId=${run.runId}`);
    } catch (error) {
      toast.error(getErrorMessage(error, "创建执行评测失败"));
    } finally {
      setEvaluating(false);
    }
  };

  return (
    <div className="admin-page execution-trace-detail-page">
      <PageHeader
        title="执行详情"
        description={detail?.task.title || taskId}
        action={<div className="trace-detail-actions"><Button variant="outline" onClick={() => void evaluateCurrentRun()} disabled={!detail || evaluating}><FlaskConical className={evaluating ? "spin" : undefined} aria-hidden="true" />{evaluating ? "创建评测中" : "评测本次执行"}</Button><Button asChild variant="outline"><Link to="/admin/traces"><ArrowLeft aria-hidden="true" />返回执行追踪</Link></Button><Button asChild variant="outline"><Link to={`/admin/rd-tasks/${taskId}`}><ClipboardList aria-hidden="true" />任务详情</Link></Button><Button variant="outline" size="icon" onClick={() => void detailState.refresh()} aria-label="刷新执行详情" title="刷新执行详情"><RefreshCw className={detailState.loading ? "spin" : undefined} aria-hidden="true" /></Button></div>}
      />
      {detailState.error ? <div className="trace-inline-notice" role="status">{detailState.error}</div> : null}
      {!detail ? <Empty>{detailState.loading ? "正在加载任务执行详情..." : "未找到执行详情。"}</Empty> : <TraceDetailContent detail={detail} />}
    </div>
  );
}

function TraceDetailContent({ detail }: { detail: ExecutionTraceDetail }) {
  return <>
    <section className="trace-detail-summary" aria-label="任务执行概览">
      <div><span>主状态</span><strong>{traceStatusLabel(detail.task.status)}</strong></div>
      <div><span>当前角色</span><strong>{traceRoleLabel(detail.overview.currentRole)}</strong></div>
      <div><span>执行进度</span><strong>{detail.overview.progressCompleted}/{detail.overview.progressTotal}</strong></div>
      <div><span>累计耗时</span><strong>{formatTraceDuration(detail.overview.elapsedMillis)}</strong></div>
      <div><span>重试记录</span><strong>{detail.overview.stageRuns.reduce((total, run) => total + Math.max(0, run.attemptNo - 1), 0)}</strong></div>
    </section>

    <Card className="trace-detail-card">
      <CardHeader><CardTitle>角色阶段与 Provider 尝试</CardTitle><CardDescription>每个角色保留历史 attempt；阶段产物只展示受控 ID，不在此页面执行任务操作。</CardDescription></CardHeader>
      <CardContent><div className="trace-stage-timeline">{detail.overview.stageRuns.length ? detail.overview.stageRuns.map((stage) => <StageRun key={stage.stageRunId} stage={stage} />) : <Empty>尚未生成角色阶段记录。</Empty>}</div></CardContent>
    </Card>

    <div className="trace-detail-grid">
      <Card className="trace-detail-card"><CardHeader><CardTitle>状态时间线</CardTitle><CardDescription>任务主状态推进记录。</CardDescription></CardHeader><CardContent><div className="trace-event-list">{detail.timeline.length ? detail.timeline.map((event) => <div key={event.id} className="trace-event-row"><span>{traceStatusLabel(event.status)}</span><p>{event.message || event.title}</p><time>{new Date(event.enteredAtEpochMillis).toLocaleString("zh-CN")}</time></div>) : <Empty>暂无状态时间线。</Empty>}</div></CardContent></Card>
      <Card className="trace-detail-card"><CardHeader><CardTitle>恢复与审计</CardTitle><CardDescription>按当前任务 ID 查询，不拉取无关审计事件。</CardDescription></CardHeader><CardContent><div className="trace-event-list">{detail.auditEvents.length ? detail.auditEvents.map((event, index) => <div key={`${event.type}-${event.createdAtEpochMillis}-${index}`} className="trace-event-row"><span>{event.type}</span><p>{event.summary}</p><time>{new Date(event.createdAtEpochMillis).toLocaleString("zh-CN")}</time></div>) : <Empty>暂无恢复或审计事件。</Empty>}</div></CardContent></Card>
      <Card className="trace-detail-card"><CardHeader><CardTitle>材料与交付产物</CardTitle><CardDescription>材料、测试结论和 PR 仍以任务详情作为权威入口。</CardDescription></CardHeader><CardContent><div className="trace-event-list">{detail.materials.length ? detail.materials.map((material) => <div key={material.materialId} className="trace-event-row"><span>{material.materialType}</span><p>{material.title || material.sourceUri || material.materialId}</p><time>{material.mimeType || material.sourceType}</time></div>) : <Empty>暂无已归档材料。</Empty>}{detail.task.executionEvidence?.testStatus ? <div className="trace-event-row"><span>TEST</span><p>{detail.task.executionEvidence.testStatus}</p><time>{detail.task.executionEvidence.pullRequestUrl || "未创建 PR"}</time></div> : null}</div></CardContent></Card>
    </div>
  </>;
}

function StageRun({ stage }: { stage: Awaited<ReturnType<typeof getExecutionTraceDetail>>["overview"]["stageRuns"][number] }) {
  return <article className={`trace-stage-run ${stage.status.includes("FAILED") ? "is-failed" : stage.status === "SUCCEEDED" ? "is-succeeded" : ""}`}><div className="trace-stage-run-head"><div><strong>{traceRoleLabel(stage.role)}</strong><span>Attempt {stage.attemptNo} · {stage.status}</span></div><time>{formatTraceDuration(stage.elapsedMillis)}</time></div><dl><div><dt>Provider</dt><dd>{stage.providerName || "--"}</dd></div><div><dt>上下文</dt><dd>{stage.contextPackageId || "--"}</dd></div><div><dt>产物</dt><dd>{stage.resultArtifactId || stage.promptArtifactId || "--"}</dd></div></dl>{stage.errorMessage ? <p className="trace-stage-error"><CircleAlert aria-hidden="true" />{stage.errorCategory || "执行错误"}：{stage.errorMessage}</p> : null}{stage.providerAttempts.length ? <details className="trace-provider-attempts"><summary>Provider 尝试 {stage.providerAttempts.length}</summary><pre>{JSON.stringify(stage.providerAttempts, null, 2)}</pre></details> : null}</article>;
}
