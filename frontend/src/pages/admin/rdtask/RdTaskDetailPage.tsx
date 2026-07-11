import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Activity, CheckCircle2, ChevronDown, ChevronLeft, Clock3, FileText, GitPullRequest, Gauge, Image as ImageIcon, Pause, Play, Upload } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { RelativeTime } from "@/components/RelativeTime";
import { getErrorMessage } from "@/utils/error";
import { cn } from "@/lib/utils";

import {
  getRdTask,
  getRdTaskExecutionOverview,
  getRdTaskMaterials,
  getRdTaskTimeline,
  approveRdTask,
  pauseRdTask,
  resumeRdTask,
  STATUS_BADGE_CLASS,
  submitRdTask,
  taskMaterialContentUrl,
  uploadTaskMaterial,
  type RdTask,
  type RdTaskExecutionOverview,
  type RdTaskStageRun,
  type TaskMaterial,
  type RdTaskStatusEvent
} from "@/services/rdTaskService";

const formatDuration = (ms?: number) => {
  if (!ms || ms <= 0) return "首步";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)}min`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
};

const formatMetricDuration = (ms?: number) => {
  if (!ms || ms <= 0) return "0ms";
  return formatDuration(ms);
};

const formatPercent = (value?: number) => `${Math.round(Math.max(0, Math.min(1, value || 0)) * 100)}%`;

const formatMoney = (value?: number) => {
  const amount = Number(value || 0);
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(amount);
};

const formatStageResultPreview = (value?: string) => {
  const text = value?.trim() || "";
  if (!text) return "";
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
};

const EVENT_DOT_TONE: Record<string, string> = {
  CREATED: "bg-blue-500",
  MATERIAL_COLLECTING: "bg-sky-500",
  MATERIAL_READY: "bg-teal-500",
  CONTEXT_BUILDING: "bg-cyan-500",
  CONTEXT_READY: "bg-teal-500",
  PLAN_GENERATING: "bg-blue-500",
  PLAN_GENERATED: "bg-cyan-500",
  WAITING_POLICY: "bg-amber-500",
  WAITING_APPROVAL: "bg-orange-500",
  SEARCHING: "bg-amber-500",
  EXECUTING: "bg-teal-500",
  VALIDATING: "bg-sky-500",
  PR_CREATING: "bg-emerald-500",
  COMMITTED: "bg-emerald-500",
  MERGED: "bg-green-600",
  REPORTING: "bg-slate-500",
  COMPLETED: "bg-green-600",
  REJECTED: "bg-red-500",
  FAILED_RETRYABLE: "bg-rose-500",
  FAILED_NEEDS_HUMAN: "bg-orange-500",
  CANCELLED: "bg-slate-500",
  DEAD_LETTERED: "bg-red-700",
  RECOVERING: "bg-cyan-500",
  PAUSED: "bg-slate-400",
  RESUMED: "bg-cyan-500",
  DELETED: "bg-slate-400"
};

const STAGE_BADGE_CLASS: Record<string, string> = {
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

const STAGE_STATUS_LABEL: Record<string, string> = {
  PENDING: "待开始", CONTEXT_READY: "上下文就绪", DISPATCHING: "调度中", RUNNING: "执行中",
  RESULT_COLLECTING: "收集结果", VERIFYING: "验证中", SUCCEEDED: "已成功",
  FAILED_RETRYABLE: "可重试失败", FAILED_NEEDS_HUMAN: "需人工处理",
  SKIPPED: "已跳过", CANCELLED: "已取消", RECOVERING: "恢复中"
};

const ROLE_LABEL: Record<string, string> = {
  BUG_EVIDENCE_COLLECTOR: "证据收集",
  BUG_RAG_RETRIEVER: "RAG 检索",
  BUG_ACCEPTANCE_PLANNER: "验收规划",
  BUG_CODING_AGENT: "修复执行",
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证"
};

export function RdTaskDetailPage() {
  const { taskId = "" } = useParams();
  const navigate = useNavigate();
  const [task, setTask] = useState<RdTask | null>(null);
  const [events, setEvents] = useState<RdTaskStatusEvent[]>([]);
  const [materials, setMaterials] = useState<TaskMaterial[]>([]);
  const [executionOverview, setExecutionOverview] = useState<RdTaskExecutionOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [approving, setApproving] = useState(false);
  const [uploading, setUploading] = useState(false);

  const uploadScreenshots = async (files: FileList | null) => {
    if (!task || !files?.length) return;
    setUploading(true);
    try {
      for (const file of Array.from(files)) {
        await uploadTaskMaterial(task.taskId, file, { materialType: "SCREENSHOT" });
      }
      setMaterials(await getRdTaskMaterials(task.taskId));
      toast.success("图片材料已上传");
    } catch (error) {
      toast.error(getErrorMessage(error, "上传图片失败"));
    } finally {
      setUploading(false);
    }
  };

  const load = async (silent = false) => {
    if (!silent) {
      setLoading(true);
    }
    try {
      const [detail, timeline, materialList, overview] = await Promise.all([
        getRdTask(taskId),
        getRdTaskTimeline(taskId),
        getRdTaskMaterials(taskId),
        getRdTaskExecutionOverview(taskId)
      ]);
      setTask(detail);
      setEvents(timeline || []);
      setMaterials(materialList || []);
      setExecutionOverview(overview);
    } catch (error) {
      if (!silent) {
        toast.error(getErrorMessage(error, "加载任务详情失败"));
      }
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    load();
  }, [taskId]);

  useEffect(() => {
    if (!task || !shouldPollTask(task)) {
      return;
    }
    const timer = window.setInterval(() => {
      load(true);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [taskId, task?.status, task?.paused]);

  const handleTogglePause = async () => {
    if (!task) return;
    try {
      const updated = task.paused
        ? await resumeRdTask(task.taskId)
        : await pauseRdTask(task.taskId);
      setTask(updated);
      toast.success(updated.paused ? "已暂停" : "已恢复并重新触发");
      const [timeline, overview] = await Promise.all([
        getRdTaskTimeline(task.taskId),
        getRdTaskExecutionOverview(task.taskId)
      ]);
      setEvents(timeline || []);
      setExecutionOverview(overview);
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  const handleSubmitTask = async () => {
    if (!task) return;
    setSubmitting(true);
    try {
      const updated = await submitRdTask(task.taskId);
      setTask(updated);
      const [timeline, materialList, overview] = await Promise.all([
        getRdTaskTimeline(task.taskId),
        getRdTaskMaterials(task.taskId),
        getRdTaskExecutionOverview(task.taskId)
      ]);
      setEvents(timeline || []);
      setMaterials(materialList || []);
      setExecutionOverview(overview);
      toast.success(updated.pullRequestUrl ? "执行完成，已生成 PR" : "执行已提交");
    } catch (error) {
      toast.error(getErrorMessage(error, "执行失败"));
    } finally {
      setSubmitting(false);
    }
  };

  const handleApproveTask = async () => {
    if (!task) return;
    setApproving(true);
    try {
      const updated = await approveRdTask(task.taskId, "管理台审批通过，继续执行需求交付");
      setTask(updated);
      const [timeline, materialList, overview] = await Promise.all([
        getRdTaskTimeline(task.taskId),
        getRdTaskMaterials(task.taskId),
        getRdTaskExecutionOverview(task.taskId)
      ]);
      setEvents(timeline || []);
      setMaterials(materialList || []);
      setExecutionOverview(overview);
      toast.success(updated.pullRequestUrl ? "审批通过，已生成 PR" : "审批通过，已继续执行");
    } catch (error) {
      toast.error(getErrorMessage(error, "审批失败"));
    } finally {
      setApproving(false);
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <div className="py-12 text-center text-muted-foreground">加载中...</div>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="admin-page">
        <div className="py-12 text-center text-muted-foreground">任务不存在</div>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
          <div>
            <h1 className="admin-page-title">任务详情</h1>
            <p className="admin-page-subtitle">{task.title || task.taskId}</p>
          </div>
          <div className="admin-page-actions">
            <Button variant="outline" onClick={() => navigate("/admin/rd-tasks")}>
              <ChevronLeft className="mr-2 h-4 w-4" />
              返回列表
            </Button>
            <Button variant="outline" onClick={handleTogglePause}>
              {task.paused ? (
                <>
                  <Play className="mr-2 h-4 w-4" />
                  重启
                </>
              ) : (
                <>
                  <Pause className="mr-2 h-4 w-4" />
                  暂停
                </>
              )}
            </Button>
            {task.taskType === "REQUIREMENT" && canSubmitRequirement(task) ? (
              <Button onClick={handleSubmitTask} disabled={submitting}>
                <Play className="mr-2 h-4 w-4" />
                {submitting ? "执行中..." : "执行需求"}
              </Button>
            ) : null}
            {task.taskType === "REQUIREMENT" && canApproveRequirement(task) ? (
              <Button onClick={handleApproveTask} disabled={approving || submitting}>
                <CheckCircle2 className="mr-2 h-4 w-4" />
                {approving ? "审批中..." : "审批通过并继续"}
              </Button>
            ) : null}
            {task.taskType === "BUG_FIX" && canSubmitBugFix(task) ? (
              <Button onClick={handleSubmitTask} disabled={submitting}>
                <Play className="mr-2 h-4 w-4" />
                {submitting ? "执行中..." : "执行修复"}
              </Button>
            ) : null}
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>基本信息</CardTitle>
            <CardDescription>任务元数据与当前状态</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2 md:grid-cols-3">
              <InfoField label="任务 ID" value={task.taskId} mono />
              <InfoField label="任务类型" value={task.taskType} />
              <InfoField label="项目" value={task.projectName || task.projectKey || "-"} />
              <InfoField label="优先级" value={task.priority} />
              <div>
                <div className="mb-1 text-xs text-muted-foreground">状态</div>
                <div className="flex items-center gap-2">
                  <Badge variant="outline" className={STATUS_BADGE_CLASS[task.status] || ""}>{task.status}</Badge>
                  {task.paused ? (
                    <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">已暂停</Badge>
                  ) : null}
                </div>
              </div>
              <InfoField label="工单 ID" value={task.ticketId || "-"} />
              <InfoField label="工单标题" value={task.ticketTitle || "-"} />
              <div>
                <div className="mb-1 text-xs text-muted-foreground">创建时间</div>
                <RelativeTime value={new Date(task.createTimeEpochMillis).toISOString()} />
              </div>
              <div>
                <div className="mb-1 text-xs text-muted-foreground">更新时间</div>
                <RelativeTime value={new Date(task.updateTimeEpochMillis).toISOString()} />
              </div>
              <InfoField
                label="PR 链接"
                value={
                  task.pullRequestUrl ? (
                    <a
                      href={task.pullRequestUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="break-all text-primary underline"
                    >
                      {task.pullRequestUrl}
                    </a>
                  ) : (
                    "-"
                  )
                }
              />
            </div>
            {task.errorMessage ? (
              <div className="mt-4 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                <div className="mb-1 font-medium">错误 / 打回原因</div>
                {task.errorMessage}
              </div>
            ) : null}
          </CardContent>
        </Card>

        {executionOverview ? <ExecutionOverviewCard overview={executionOverview} /> : null}

        {task.taskType === "REQUIREMENT" ? (
          <Card>
            <CardHeader>
              <CardTitle>需求交付信息</CardTitle>
              <CardDescription>需求任务的仓库、分支与验收输入</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-1 gap-4 text-sm md:grid-cols-2">
                <InfoField
                  label="仓库"
                  value={
                    task.repositoryUrl ? (
                      <a
                        href={task.repositoryUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="break-all text-primary underline"
                      >
                        {task.repositoryUrl}
                      </a>
                    ) : (
                      "-"
                    )
                  }
                />
                <InfoField label="基准分支" value={task.baseBranch || "-"} mono />
                <InfoField label="工作分支" value={task.workBranch || "-"} mono />
                <InfoField label="来源" value={task.sourceType || "ADMIN"} />
              </div>
              <div>
                <div className="mb-1 text-xs text-muted-foreground">预期结果</div>
                <div className="whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-sm text-slate-700">
                  {task.expectedResult || "-"}
                </div>
              </div>
              <div>
                <div className="mb-1 text-xs text-muted-foreground">验收标准</div>
                <div className="space-y-2 rounded-lg bg-slate-50 p-3 text-sm text-slate-700">
                  {parseCriteria(task.acceptanceCriteriaJson).length > 0 ? (
                    parseCriteria(task.acceptanceCriteriaJson).map((item, index) => (
                      <div key={`${item}-${index}`}>{index + 1}. {item}</div>
                    ))
                  ) : (
                    "-"
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        ) : null}

        {hasExecutionEvidence(task) ? (
          <Card>
            <CardHeader>
              <CardTitle>执行结果与 PR</CardTitle>
              <CardDescription>自动编码、验证和代码评审请求的输出摘要</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-1 gap-4 text-sm md:grid-cols-3">
                <InfoField label="执行摘要" value={task.executionEvidence?.summary || "-"} />
                <InfoField label="测试状态" value={task.executionEvidence?.testStatus || "-"} />
                <InfoField label="风险等级" value={task.executionEvidence?.riskLevel || "-"} />
              </div>
              {task.pullRequestUrl || task.executionEvidence?.pullRequestUrl ? (
                <a
                  href={task.pullRequestUrl || task.executionEvidence?.pullRequestUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-2 text-sm font-medium text-primary underline"
                >
                  <GitPullRequest className="h-4 w-4" />
                  打开 PR
                </a>
              ) : null}
              {task.executionEvidence?.prBody ? (
                <div>
                  <div className="mb-1 text-xs text-muted-foreground">改动介绍</div>
                  <pre className="max-h-[260px] overflow-auto whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-xs leading-relaxed text-slate-700">
                    {task.executionEvidence.prBody}
                  </pre>
                </div>
              ) : null}
              {task.executionEvidence?.changedFiles?.length ? (
                <div>
                  <div className="mb-1 text-xs text-muted-foreground">变更文件</div>
                  <div className="flex flex-wrap gap-2">
                    {task.executionEvidence.changedFiles.map((file) => (
                      <code key={file} className="rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-700">
                        {file}
                      </code>
                    ))}
                  </div>
                </div>
              ) : null}
              {task.executionEvidence?.testCommands?.length ? (
                <div>
                  <div className="mb-1 text-xs text-muted-foreground">测试命令</div>
                  <div className="space-y-2">
                    {task.executionEvidence.testCommands.map((command) => (
                      <code key={command} className="block rounded-md bg-slate-950 px-3 py-2 text-xs text-slate-100">
                        {command}
                      </code>
                    ))}
                  </div>
                </div>
              ) : null}
            </CardContent>
          </Card>
        ) : null}

        <Card>
          <CardHeader className="flex flex-row items-start justify-between gap-3">
            <div>
              <CardTitle>任务材料</CardTitle>
              <CardDescription>输入文档、截图与安全预览</CardDescription>
            </div>
            <Button asChild variant="outline" size="sm" disabled={uploading}>
              <label className="cursor-pointer">
                <Upload className="mr-2 h-4 w-4" />
                {uploading ? "上传中" : "补充图片"}
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif"
                  multiple
                  className="sr-only"
                  onChange={(event) => void uploadScreenshots(event.target.files)}
                />
              </label>
            </Button>
          </CardHeader>
          <CardContent className="space-y-3">
            {materials.length === 0 ? (
              <div className="py-6 text-center text-sm text-muted-foreground">暂无材料</div>
            ) : materials.map((material) => (
              <div key={material.materialId} className="rounded-md border border-slate-200 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 truncate text-sm font-medium text-slate-800">
                      {material.mimeType.startsWith("image/") ? <ImageIcon className="h-4 w-4 shrink-0" /> : null}
                      {material.title || material.materialId}
                    </div>
                    <div className="mt-1 flex flex-wrap gap-2 text-xs text-muted-foreground">
                      <span>{material.sourceType}</span><span>{material.materialType}</span>
                      <span className="break-all">{material.contentHash}</span>
                    </div>
                  </div>
                </div>
                {material.mimeType.startsWith("image/") ? (
                  <img
                    src={taskMaterialContentUrl(task.taskId, material.materialId)}
                    alt={material.title || "任务截图"}
                    className="mt-3 max-h-[360px] w-auto max-w-full rounded-md border border-slate-200 object-contain"
                    loading="lazy"
                  />
                ) : (
                  <pre className="mt-3 max-h-[220px] overflow-auto whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-xs leading-relaxed text-slate-600">
                    {material.contentPreview || "-"}
                  </pre>
                )}
                <a
                  href={taskMaterialContentUrl(task.taskId, material.materialId)}
                  download
                  className="mt-3 inline-flex text-xs font-medium text-primary underline"
                >下载原文件</a>
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>全链路时间线</CardTitle>
            <CardDescription>任务经历的状态与每步耗时</CardDescription>
          </CardHeader>
          <CardContent>
            {events.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">暂无状态事件</div>
            ) : (
              <ol className="relative space-y-6 border-l border-slate-200 pl-6">
                {events.map((event, index) => (
                  <li key={`${event.id}-${index}`} className="relative">
                    <span
                      className={cn(
                        "absolute -left-[31px] top-1 h-3 w-3 rounded-full ring-4 ring-white",
                        EVENT_DOT_TONE[event.status] || "bg-slate-300"
                      )}
                    />
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="outline" className={STATUS_BADGE_CLASS[event.status] || ""}>
                        {event.status}
                      </Badge>
                      <span className="text-xs text-muted-foreground">
                        {event.trigger} · {formatDuration(event.durationMillis)}
                      </span>
                    </div>
                    <div className="mt-1 text-sm text-slate-700">
                      {event.title || event.message || "无说明"}
                    </div>
                    {event.message ? (
                      <div className="mt-0.5 text-xs text-muted-foreground">{event.message}</div>
                    ) : null}
                    <div className="mt-1 text-xs text-muted-foreground">
                      <RelativeTime value={new Date(event.enteredAtEpochMillis).toISOString()} />
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </CardContent>
        </Card>

        {task.promptSnapshot ? (
          <Card>
            <CardHeader>
              <CardTitle>Prompt 快照</CardTitle>
            </CardHeader>
            <CardContent>
              <pre className="overflow-auto rounded-lg bg-slate-50 p-4 text-xs leading-relaxed text-slate-600">
                {task.promptSnapshot}
              </pre>
            </CardContent>
          </Card>
        ) : null}
      </div>
  );
}

function parseCriteria(value?: string) {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map((item) => String(item)).filter(Boolean) : [];
  } catch {
    return value.split("\n").map((line) => line.trim()).filter(Boolean);
  }
}

function ExecutionOverviewCard({ overview }: { overview: RdTaskExecutionOverview }) {
  const progressRatio = overview.progressTotal > 0
    ? overview.progressCompleted / overview.progressTotal
    : 0;
  const currentRole = overview.currentRole ? ROLE_LABEL[overview.currentRole] || overview.currentRole : "-";
  const currentStatus = overview.currentStageStatus
    ? STAGE_STATUS_LABEL[overview.currentStageStatus] || overview.currentStageStatus : "-";
  const budget = overview.budget;

  return (
    <Card>
      <CardHeader>
        <CardTitle>执行概览</CardTitle>
        <CardDescription>阶段进度、耗时与预算</CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
          <MetricBlock
            icon={<Activity className="h-4 w-4" />}
            label="当前阶段"
            value={currentRole}
            detail={currentStatus}
          />
          <MetricBlock
            icon={<Clock3 className="h-4 w-4" />}
            label="总耗时"
            value={formatMetricDuration(overview.elapsedMillis)}
            detail={`${overview.progressCompleted}/${overview.progressTotal}`}
          />
          <MetricBlock
            icon={<Gauge className="h-4 w-4" />}
            label="上下文预算"
            value={`${budget.contextUsedChars}/${budget.contextMaxChars || 0}`}
            detail={formatPercent(budget.contextUsageRatio)}
          />
          <MetricBlock
            icon={<Gauge className="h-4 w-4" />}
            label="模型预算"
            value={budget.costAvailable ? formatMoney(budget.estimatedSpendCny) : "待采集"}
            detail={`阈值 ${formatMoney(budget.budgetAlertCny)}`}
          />
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground">
            <span>整体进度</span>
            <span>{formatPercent(progressRatio)}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-slate-100">
            <div
              className="h-full rounded-full bg-teal-600 transition-all"
              style={{ width: formatPercent(progressRatio) }}
            />
          </div>
        </div>

        <div className="overflow-x-auto rounded-md border border-slate-200">
          <div className="min-w-[900px]">
            <div className="grid grid-cols-[1.15fr_1fr_1.2fr_0.75fr_0.8fr] gap-3 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-500">
              <span>角色</span>
              <span>状态</span>
              <span>Provider</span>
              <span>耗时</span>
              <span>结果</span>
            </div>
            {overview.stageRuns.length > 0 ? (
              overview.stageRuns.map((stage) => <StageRunRow key={stage.stageRunId} stage={stage} />)
            ) : (
              <div className="px-3 py-6 text-center text-sm text-muted-foreground">暂无阶段记录</div>
            )}
          </div>
        </div>

        {overview.runningExecutions.length > 0 ? (
          <div className="space-y-2">
            <div className="text-xs font-medium text-slate-500">运行中执行</div>
            {overview.runningExecutions.map((execution) => (
              <div
                key={`${execution.taskId}-${execution.containerName}`}
                className="grid grid-cols-1 gap-2 rounded-md border border-teal-100 bg-teal-50/50 px-3 py-2 text-sm md:grid-cols-[1.2fr_0.8fr_0.8fr]"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium text-slate-800">{execution.containerName}</div>
                  <div className="truncate text-xs text-muted-foreground">{execution.outputDirectory}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">Provider</div>
                  <div className="font-mono text-xs text-slate-700">{execution.provider || "-"}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">运行耗时</div>
                  <div className="font-mono text-xs text-slate-700">
                    {formatMetricDuration(execution.elapsedMillis)}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function MetricBlock({
  icon,
  label,
  value,
  detail
}: {
  icon: React.ReactNode;
  label: string;
  value: React.ReactNode;
  detail: React.ReactNode;
}) {
  return (
    <div className="min-h-[88px] rounded-md border border-slate-200 bg-white px-3 py-3">
      <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
        {icon}
        <span>{label}</span>
      </div>
      <div className="truncate text-lg font-semibold text-slate-900">{value}</div>
      <div className="mt-1 truncate text-xs text-muted-foreground">{detail}</div>
    </div>
  );
}

function StageRunRow({ stage }: { stage: RdTaskStageRun }) {
  const [expanded, setExpanded] = useState(false);
  const firstAttempt = stage.providerAttempts?.[0] || {};
  const attemptStatus = firstAttempt.status ? String(firstAttempt.status) : "";
  const provider = stage.providerName || (firstAttempt.provider ? String(firstAttempt.provider) : "-");
  const resultPreview = formatStageResultPreview(stage.resultPreview);
  const resultAvailable = Boolean(stage.resultAvailable && resultPreview);

  return (
    <div className="grid grid-cols-[1.15fr_1fr_1.2fr_0.75fr_0.8fr] gap-3 border-t border-slate-100 px-3 py-3 text-sm">
      <div className="min-w-0">
        <div className="truncate font-medium text-slate-800">{ROLE_LABEL[stage.role] || stage.role}</div>
        <div className="mt-1 font-mono text-xs text-muted-foreground">attempt {stage.attemptNo}</div>
      </div>
      <div className="min-w-0">
        <Badge variant="outline" className={STAGE_BADGE_CLASS[stage.status] || ""}>
          {STAGE_STATUS_LABEL[stage.status] || stage.status}
        </Badge>
        {attemptStatus ? (
          <div className="mt-1 truncate text-xs text-muted-foreground">{attemptStatus}</div>
        ) : null}
        {stage.errorMessage ? (
          <div className="mt-1 line-clamp-2 break-words text-xs text-red-700" title={stage.errorMessage}>
            {stage.errorCategory ? `${stage.errorCategory}: ` : ""}{stage.errorMessage}
          </div>
        ) : null}
      </div>
      <div className="min-w-0">
        <div className="truncate font-mono text-xs text-slate-700">{provider}</div>
        {stage.contextPackageId ? (
          <div className="mt-1 truncate font-mono text-xs text-muted-foreground">{stage.contextPackageId}</div>
        ) : null}
      </div>
      <div className="min-w-0">
        <div className="font-mono text-xs text-slate-700">{formatMetricDuration(stage.elapsedMillis)}</div>
        {stage.running ? (
          <div className="mt-1 text-xs text-teal-700">运行中</div>
        ) : null}
      </div>
      <div className="min-w-0">
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="h-7 rounded-md px-2"
          disabled={!resultAvailable}
          title={resultAvailable ? "查看阶段结果" : "暂无阶段结果"}
          onClick={() => setExpanded((value) => !value)}
        >
          <FileText className="h-3.5 w-3.5" />
          {expanded ? "收起" : "查看"}
          <ChevronDown className={cn("h-3.5 w-3.5 transition-transform", expanded && "rotate-180")} />
        </Button>
      </div>
      {expanded && resultAvailable ? (
        <div className="col-span-5 rounded-md border border-slate-200 bg-slate-50/70 p-3">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <Badge variant="outline" className="border-slate-200 bg-white text-slate-700">
              {stage.resultSummary || "阶段结果"}
            </Badge>
            {stage.resultArtifactId ? (
              <code className="break-all text-xs text-muted-foreground">{stage.resultArtifactId}</code>
            ) : null}
          </div>
          <pre className="max-h-[360px] overflow-auto whitespace-pre-wrap rounded-md bg-white p-3 text-xs leading-relaxed text-slate-700">
            {resultPreview}
          </pre>
        </div>
      ) : null}
    </div>
  );
}

function hasExecutionEvidence(task: RdTask) {
  const evidence = task.executionEvidence;
  return Boolean(
    task.pullRequestUrl ||
      evidence?.summary ||
      evidence?.prBody ||
      evidence?.changedFiles?.length ||
      evidence?.testCommands?.length ||
      evidence?.testStatus ||
      evidence?.riskLevel
  );
}

function shouldPollTask(task: RdTask) {
  return !task.paused && ![
    "COMPLETED",
    "MERGED",
    "REJECTED",
    "FAILED_NEEDS_HUMAN",
    "CANCELLED",
    "DEAD_LETTERED",
    "DELETED"
  ].includes(task.status);
}

function canSubmitRequirement(task: RdTask) {
  return !task.paused && ![
    "EXECUTING",
    "VALIDATING",
    "PR_CREATING",
    "WAITING_APPROVAL",
    "COMMITTED",
    "MERGED",
    "COMPLETED",
    "DELETED"
  ].includes(task.status);
}

function canApproveRequirement(task: RdTask) {
  return !task.paused && task.status === "WAITING_APPROVAL";
}

function canSubmitBugFix(task: RdTask) {
  return !task.paused && ![
    "SEARCHING",
    "EXECUTING",
    "VALIDATING",
    "PR_CREATING",
    "COMMITTED",
    "MERGED",
    "COMPLETED",
    "CANCELLED",
    "DEAD_LETTERED",
    "DELETED"
  ].includes(task.status);
}

function InfoField({
  label,
  value,
  mono
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
}) {
  return (
    <div>
      <div className="mb-1 text-xs text-muted-foreground">{label}</div>
      <div className={cn("text-sm text-slate-800", mono && "font-mono break-all")}>{value}</div>
    </div>
  );
}
