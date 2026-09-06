import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardList,
  Clock3,
  FolderOpen,
  GitBranch,
  ImagePlus,
  MoreHorizontal,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { RelativeTime } from "@/components/RelativeTime";
import { getErrorMessage } from "@/utils/error";

import {
  createRequirementTask,
  completeTaskDraft,
  deleteRdTask,
  getRdTasksPage,
  pauseRdTask,
  resumeRdTask,
  submitRdTask,
  uploadTaskMaterial,
  updateRdTask,
  STATUS_BADGE_CLASS,
  type RdTask,
  type RequirementMaterialPayload
} from "@/services/rdTaskService";
import { getProjectsPage, getProjectTaskTemplate, type RdProject } from "@/services/projectService";
import {
  imageAttachmentKey,
  mergeImageAttachments,
  removeImageAttachment
} from "./imageAttachments";
import {
  clearTaskCreateDraft,
  formatDraftTime,
  isTaskDraftDirty,
  loadTaskCreateDraft,
  saveTaskCreateDraft
} from "./rdTaskDraft";
import { taskListFiltersFromSearchParams } from "./taskListFilters";
import { taskStatusNotice } from "./roleWorkbenchModel";

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "CREATED", label: "已创建" },
  { value: "MATERIAL_COLLECTING", label: "收集材料" },
  { value: "MATERIAL_READY", label: "材料就绪" },
  { value: "CONTEXT_BUILDING", label: "构建上下文" },
  { value: "CONTEXT_READY", label: "上下文就绪" },
  { value: "PLAN_GENERATING", label: "生成计划" },
  { value: "PLAN_GENERATED", label: "计划就绪" },
  { value: "WAITING_POLICY", label: "策略检查" },
  { value: "WAITING_APPROVAL", label: "等待审批" },
  { value: "WAITING_USER_INPUT", label: "等待补充信息" },
  { value: "SEARCHING", label: "检索中" },
  { value: "EXECUTING", label: "执行中" },
  { value: "VALIDATING", label: "验证中" },
  { value: "PR_CREATING", label: "创建 PR" },
  { value: "COMMITTED", label: "已提交" },
  { value: "MERGED", label: "已合并" },
  { value: "REPORTING", label: "报告中" },
  { value: "COMPLETED", label: "已完成" },
  { value: "REJECTED", label: "已打回" },
  { value: "FAILED_RETRYABLE", label: "可重试失败" },
  { value: "FAILED_NEEDS_HUMAN", label: "需人工处理" },
  { value: "CANCELLED", label: "已取消" },
  { value: "DEAD_LETTERED", label: "死信" },
  { value: "RECOVERING", label: "恢复中" }
];

const PRIORITY_OPTIONS = ["P0", "P1", "P2"];

/** Filter options only: BUG_FIX stays queryable for historical tasks, but is no longer creatable. */
const TASK_TYPE_OPTIONS = [
  { value: "BUG_FIX", label: "修 Bug" },
  { value: "REQUIREMENT", label: "做需求" }
];

const CREATE_TASK_TYPE = "REQUIREMENT" as const;

const formatAttachmentSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
};

function PendingImageCard({ file, onRemove }: { file: File; onRemove: () => void }) {
  const [previewUrl, setPreviewUrl] = useState("");

  useEffect(() => {
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  return (
    <figure className="min-w-0 overflow-hidden rounded-md border border-slate-200 bg-white">
      <div className="relative aspect-[4/3] bg-slate-100">
        {previewUrl ? (
          <img src={previewUrl} alt={file.name} className="h-full w-full object-contain" />
        ) : null}
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="absolute right-1.5 top-1.5 h-7 w-7 bg-white/95 text-slate-600 shadow-sm hover:text-destructive"
          aria-label={`删除图片 ${file.name}`}
          title="删除图片"
          onClick={onRemove}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>
      <figcaption className="p-2">
        <div className="truncate text-xs font-medium text-slate-800" title={file.name}>{file.name}</div>
        <div className="mt-0.5 text-[11px] tabular-nums text-muted-foreground">{formatAttachmentSize(file.size)}</div>
      </figcaption>
    </figure>
  );
}

const formatDuration = (ms?: number) => {
  if (!ms || ms <= 0) return "-";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)}min`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
};

const truncate = (value?: string | null, max = 40) => {
  if (!value) return "-";
  return value.length <= max ? value : `${value.slice(0, max)}...`;
};

export function RdTaskListPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const urlFilters = taskListFiltersFromSearchParams(searchParams);
  const [records, setRecords] = useState<RdTask[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [loading, setLoading] = useState(false);

  const [statusFilter, setStatusFilter] = useState<string | undefined>(urlFilters.status);
  const [taskTypeFilter, setTaskTypeFilter] = useState<string | undefined>(urlFilters.taskType);
  const [projectIdFilter, setProjectIdFilter] = useState<string | undefined>(urlFilters.projectId);
  const [filterProjectOptions, setFilterProjectOptions] = useState<RdProject[]>([]);
  const [filterProjectsLoading, setFilterProjectsLoading] = useState(false);
  const [keyword, setKeyword] = useState(urlFilters.keyword || "");
  const [searchInput, setSearchInput] = useState(urlFilters.keyword || "");

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<RdTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RdTask | null>(null);

  useEffect(() => {
    if (searchParams.get("create") !== "true") return;
    setCreateOpen(true);
    const nextSearchParams = new URLSearchParams(searchParams);
    nextSearchParams.delete("create");
    setSearchParams(nextSearchParams, { replace: true });
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    let active = true;
    setFilterProjectsLoading(true);
    getProjectsPage({ enabled: true, page: 1, pageSize: 200 })
      .then((data) => {
        if (active) setFilterProjectOptions(data.records || []);
      })
      .catch((error) => {
        if (!active) return;
        setFilterProjectOptions([]);
        toast.error(getErrorMessage(error, "加载项目筛选项失败"));
      })
      .finally(() => {
        if (active) setFilterProjectsLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const loadTasks = async (
    nextPage = page,
    nextStatus = statusFilter,
    nextKeyword = keyword,
    nextTaskType = taskTypeFilter,
    nextProjectId = projectIdFilter
  ) => {
    setLoading(true);
    try {
      const data = await getRdTasksPage({
        taskType: nextTaskType,
        status: nextStatus,
        projectId: nextProjectId,
        keyword: nextKeyword,
        page: nextPage,
        pageSize: PAGE_SIZE
      });
      setRecords(data.records || []);
      setPage(data.page);
      setTotal(data.total);
      setPages(data.pages);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载任务列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setStatusFilter(urlFilters.status);
    setTaskTypeFilter(urlFilters.taskType);
    setProjectIdFilter(urlFilters.projectId);
    setKeyword(urlFilters.keyword || "");
    setSearchInput(urlFilters.keyword || "");
    loadTasks(1, urlFilters.status, urlFilters.keyword || "", urlFilters.taskType, urlFilters.projectId);
  }, [urlFilters.keyword, urlFilters.projectId, urlFilters.status, urlFilters.taskType]);

  const updateUrlFilters = (updates: Record<string, string | undefined>) => {
    const nextSearchParams = new URLSearchParams(searchParams);
    Object.entries(updates).forEach(([key, value]) => {
      if (value?.trim()) {
        nextSearchParams.set(key, value.trim());
      } else {
        nextSearchParams.delete(key);
      }
    });
    setSearchParams(nextSearchParams);
  };

  const handleSearch = () => {
    updateUrlFilters({ keyword: searchInput });
  };

  const handleStatusChange = (value: string) => {
    const next = value === "all" ? undefined : value;
    updateUrlFilters({ status: next });
  };

  const handleTaskTypeChange = (value: string) => {
    const next = value === "all" ? undefined : value;
    updateUrlFilters({ taskType: next });
  };

  const handleProjectChange = (value: string) => {
    updateUrlFilters({ projectId: value === "all" ? undefined : value });
  };

  const handleRefresh = () => {
    loadTasks(1, statusFilter, keyword, taskTypeFilter, projectIdFilter);
  };

  const handleTogglePause = async (task: RdTask) => {
    try {
      if (task.paused) {
        await resumeRdTask(task.taskId);
        toast.success("已恢复并重新触发");
      } else {
        await pauseRdTask(task.taskId);
        toast.success("已暂停");
      }
      loadTasks(page, statusFilter, keyword, taskTypeFilter, projectIdFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteRdTask(deleteTarget.taskId);
      toast.success("已删除");
      setDeleteTarget(null);
      loadTasks(page, statusFilter, keyword, taskTypeFilter, projectIdFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  const metrics = useMemo(() => {
    let inProgress = 0;
    let waiting = 0;
    let failed = 0;
    let completed = 0;
    for (const t of records) {
      if (["EXECUTING", "SEARCHING", "VALIDATING", "PR_CREATING", "MATERIAL_COLLECTING", "CONTEXT_BUILDING", "PLAN_GENERATING", "REPORTING", "RECOVERING", "RUNNING"].includes(t.status)) {
        inProgress++;
      } else if (["WAITING_APPROVAL", "WAITING_USER_INPUT", "WAITING_POLICY", "FAILED_NEEDS_HUMAN"].includes(t.status)) {
        waiting++;
      } else if (["FAILED_RETRYABLE", "DEAD_LETTERED", "REJECTED"].includes(t.status)) {
        failed++;
      } else if (["COMPLETED", "MERGED", "COMMITTED"].includes(t.status)) {
        completed++;
      }
    }
    return { inProgress, waiting, failed, completed };
  }, [records]);

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">任务管理</h1>
          <p className="admin-page-subtitle">管理 RD 任务：检索、执行、提交、评审全流程</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder="搜索标题 / 工单"
            className="w-[220px]"
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
          />
          <Select
            value={statusFilter || "all"}
            onValueChange={handleStatusChange}
          >
            <SelectTrigger className="w-[160px]">
              <SelectValue placeholder="状态" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              {STATUS_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select
            value={taskTypeFilter || "all"}
            onValueChange={handleTaskTypeChange}
          >
            <SelectTrigger className="w-[140px]">
              <SelectValue placeholder="类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部类型</SelectItem>
              {TASK_TYPE_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select
            value={projectIdFilter || "all"}
            onValueChange={handleProjectChange}
            disabled={filterProjectsLoading}
          >
            <SelectTrigger className="w-[200px]" aria-label="按项目筛选">
              <SelectValue placeholder={filterProjectsLoading ? "加载项目中..." : "全部项目"} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部项目</SelectItem>
              {filterProjectOptions.map((project) => (
                <SelectItem key={project.projectId} value={project.projectId}>
                  {project.name} · {project.projectKey}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => setCreateOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            新建任务
          </Button>
        </div>
      </div>

      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-slate-200 bg-white p-3.5 shadow-sm transition hover:border-primary/40 hover:shadow"
          onClick={() => handleStatusChange("all")}
          onKeyDown={(e) => e.key === "Enter" && handleStatusChange("all")}
        >
          <div>
            <div className="text-xs text-muted-foreground">当前页总数</div>
            <div className="text-lg font-bold text-slate-900">{total} <span className="text-xs font-normal text-slate-400">条</span></div>
          </div>
          <ClipboardList className="h-5 w-5 text-slate-400" />
        </div>
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-teal-200 bg-teal-50/40 p-3.5 shadow-sm transition hover:border-teal-400 hover:shadow"
          onClick={() => handleStatusChange("EXECUTING")}
          onKeyDown={(e) => e.key === "Enter" && handleStatusChange("EXECUTING")}
        >
          <div>
            <div className="text-xs font-medium text-teal-800">进行中</div>
            <div className="text-lg font-bold text-teal-900">{metrics.inProgress}</div>
          </div>
          <Play className="h-5 w-5 text-teal-600" />
        </div>
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-amber-200 bg-amber-50/40 p-3.5 shadow-sm transition hover:border-amber-400 hover:shadow"
          onClick={() => handleStatusChange("WAITING_APPROVAL")}
          onKeyDown={(e) => e.key === "Enter" && handleStatusChange("WAITING_APPROVAL")}
        >
          <div>
            <div className="text-xs font-medium text-amber-800">等待人工 / 审批</div>
            <div className="text-lg font-bold text-amber-900">{metrics.waiting}</div>
          </div>
          <Clock3 className="h-5 w-5 text-amber-600" />
        </div>
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-rose-200 bg-rose-50/40 p-3.5 shadow-sm transition hover:border-rose-400 hover:shadow"
          onClick={() => handleStatusChange("FAILED_RETRYABLE")}
          onKeyDown={(e) => e.key === "Enter" && handleStatusChange("FAILED_RETRYABLE")}
        >
          <div>
            <div className="text-xs font-medium text-rose-800">异常 / 待重试</div>
            <div className="text-lg font-bold text-rose-900">{metrics.failed}</div>
          </div>
          <AlertTriangle className="h-5 w-5 text-rose-600" />
        </div>
      </div>

      <Card>
        <CardContent className="min-w-0 pt-6">
          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">
              暂无任务，点击「新建任务」创建
            </div>
          ) : (
            <div className="overflow-x-auto">
              <Table className="min-w-[1080px] table-fixed">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[320px]">任务与所属项目</TableHead>
                    <TableHead className="w-[90px]">类型</TableHead>
                    <TableHead className="w-[70px]">优先级</TableHead>
                    <TableHead className="w-[120px]">状态</TableHead>
                    <TableHead className="w-[110px]">更新时间</TableHead>
                    <TableHead className="w-[150px] text-left">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {records.map((task) => (
                    <TableRow key={task.taskId} className="hover:bg-slate-50/80">
                      <TableCell className="font-medium">
                        <div className="min-w-0">
                          <button
                            type="button"
                            className="admin-link block max-w-full truncate text-left text-sm font-semibold text-slate-900 hover:text-primary"
                            title={task.title}
                            onClick={() => navigate(`/admin/rd-tasks/${task.taskId}`)}
                          >
                            {task.title || "-"}
                          </button>
                          <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-0.5 text-xs text-muted-foreground">
                            <span className="inline-flex items-center gap-1 font-medium text-slate-700" title={task.projectName || task.projectKey || "-"}>
                              <FolderOpen className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                              <span className="max-w-[130px] truncate">{task.projectName || task.projectKey || "-"}</span>
                            </span>
                            <span className="text-slate-300">/</span>
                            <span className="inline-flex items-center gap-1 font-mono text-[11px] text-slate-600" title={task.taskType === "REQUIREMENT" ? (task.baseBranch || "-") : (task.ticketId || "-")}>
                              {task.taskType === "REQUIREMENT" ? <GitBranch className="h-3 w-3 shrink-0 text-muted-foreground" /> : <ClipboardList className="h-3 w-3 shrink-0 text-muted-foreground" />}
                              <span className="max-w-[110px] truncate">{task.taskType === "REQUIREMENT" ? (task.baseBranch || "-") : (task.ticketId || "-")}</span>
                            </span>
                          </div>
                          {(() => {
                            const notice = taskStatusNotice(task);
                            if (notice.kind === "recovery") {
                              return (
                                <div className="mt-1.5 flex items-center gap-1.5 rounded border border-sky-200 bg-sky-50/80 px-2 py-0.5 text-xs text-sky-900" title={notice.message}>
                                  <RefreshCw className="h-3 w-3 shrink-0 text-sky-600" />
                                  <span className="truncate">{truncate(notice.message, 45)}</span>
                                </div>
                              );
                            }
                            if (notice.kind === "blocker") {
                              return (
                                <div className="mt-1.5 flex items-center gap-1.5 rounded border border-rose-200 bg-rose-50/80 px-2 py-0.5 text-xs text-rose-800" title={notice.message}>
                                  <AlertTriangle className="h-3 w-3 shrink-0 text-rose-600" />
                                  <span className="truncate">{truncate(notice.message, 45)}</span>
                                </div>
                              );
                            }
                            return null;
                          })()}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className={task.taskType === "REQUIREMENT" ? "border-sky-200 bg-sky-50 text-sky-700" : "border-amber-200 bg-amber-50 text-amber-700"}>
                          {task.taskType === "REQUIREMENT" ? "需求" : "Bug"}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="font-mono text-xs">{task.priority}</Badge>
                      </TableCell>
                      <TableCell>
                        <div className="space-y-1">
                          <Badge variant="outline" className={STATUS_BADGE_CLASS[task.status] || ""}>
                            {task.status}
                          </Badge>
                          {task.paused ? (
                            <div className="inline-block rounded border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700">
                              已暂停
                            </div>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        <RelativeTime value={new Date(task.updateTimeEpochMillis).toISOString()} />
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-1.5">
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-8 gap-1 px-2.5 text-xs font-medium text-slate-700 hover:border-primary/40 hover:text-primary"
                            onClick={() => navigate(`/admin/rd-tasks/${task.taskId}`)}
                          >
                            <ClipboardList className="h-3.5 w-3.5" />
                            <span>详情</span>
                          </Button>
                          {task.paused ? (
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-8 gap-1 border-amber-300 bg-amber-50 px-2 text-xs text-amber-800 hover:bg-amber-100"
                              onClick={() => handleTogglePause(task)}
                              title="恢复并重新触发"
                            >
                              <Play className="h-3 w-3" />
                              <span>重启</span>
                            </Button>
                          ) : null}
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <Button
                                size="sm"
                                variant="outline"
                                className="h-8 w-8 p-0 text-slate-500 hover:text-slate-900"
                                aria-label="更多操作"
                                title="更多操作"
                              >
                                <MoreHorizontal className="h-4 w-4" />
                              </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end" className="w-36">
                              <DropdownMenuItem onClick={() => setEditTarget(task)}>
                                <Pencil className="mr-2 h-4 w-4 text-muted-foreground" />
                                <span>编辑任务</span>
                              </DropdownMenuItem>
                              {!task.paused ? (
                                <DropdownMenuItem onClick={() => handleTogglePause(task)}>
                                  <Pause className="mr-2 h-4 w-4 text-muted-foreground" />
                                  <span>暂停任务</span>
                                </DropdownMenuItem>
                              ) : null}
                              <DropdownMenuSeparator />
                              <DropdownMenuItem
                                className="text-destructive focus:bg-destructive/10 focus:text-destructive"
                                onClick={() => setDeleteTarget(task)}
                              >
                                <Trash2 className="mr-2 h-4 w-4" />
                                <span>删除任务</span>
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
            <span>共 {total} 条</span>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => loadTasks(Math.max(1, page - 1), statusFilter, keyword, taskTypeFilter, projectIdFilter)}
                disabled={page <= 1}
              >
                上一页
              </Button>
              <span>
                {page} / {pages || 1}
              </span>
              <Button
                variant="outline"
                size="sm"
                onClick={() => loadTasks(Math.min(pages || 1, page + 1), statusFilter, keyword, taskTypeFilter, projectIdFilter)}
                disabled={page >= pages}
              >
                下一页
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <RdTaskEditDialog
          open={createOpen}
          mode="create"
          onOpenChange={setCreateOpen}
          onSuccess={() => loadTasks(1, statusFilter, keyword, taskTypeFilter, projectIdFilter)}
        />
        <RdTaskEditDialog
          open={!!editTarget}
          mode="edit"
          task={editTarget}
          onOpenChange={(open) => setEditTarget(open ? editTarget : null)}
          onSuccess={() => loadTasks(page, statusFilter, keyword, taskTypeFilter, projectIdFilter)}
        />

        <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>确认删除任务？</AlertDialogTitle>
              <AlertDialogDescription>
                任务 [{deleteTarget?.title}] 将被逻辑删除，列表不再显示，状态事件一并清理。
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>取消</AlertDialogCancel>
              <AlertDialogAction
                onClick={handleDelete}
                className="bg-destructive text-destructive-foreground"
              >
                删除
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
  );
}

interface RdTaskEditDialogProps {
  open: boolean;
  mode: "create" | "edit";
  task?: RdTask | null;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

function RdTaskEditDialog({ open, mode, task, onOpenChange, onSuccess }: RdTaskEditDialogProps) {
  const [title, setTitle] = useState("");
  const [ticketId, setTicketId] = useState("");
  const [ticketTitle, setTicketTitle] = useState("");
  const [priority, setPriority] = useState("P2");
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [projectOptions, setProjectOptions] = useState<RdProject[]>([]);
  const [projectLoading, setProjectLoading] = useState(false);
  const [baseBranch, setBaseBranch] = useState("main");
  const [expectedResult, setExpectedResult] = useState("");
  const [acceptanceCriteriaText, setAcceptanceCriteriaText] = useState("");
  const [tokenBudgetOverride, setTokenBudgetOverride] = useState("0");
  const [materialSourceType, setMaterialSourceType] = useState<"MANUAL_TEXT" | "FEISHU_DOC" | "LOCAL_UPLOAD">("MANUAL_TEXT");
  const [manualRequirementText, setManualRequirementText] = useState("");
  const [feishuDocumentUrl, setFeishuDocumentUrl] = useState("");
  const [localRequirementFile, setLocalRequirementFile] = useState<File | null>(null);
  const [autoExecute, setAutoExecute] = useState(true);
  const [attachmentFiles, setAttachmentFiles] = useState<File[]>([]);
  const [drafting, setDrafting] = useState(false);
  const [applyingTemplate, setApplyingTemplate] = useState(false);
  const [saving, setSaving] = useState(false);
  const [restoredDraftTime, setRestoredDraftTime] = useState<number | null>(null);
  const formContextKey = `${open}:${selectedProjectId}`;
  const formContextRef = useRef({ key: formContextKey, version: 0, open, selectedProjectId });
  if (formContextRef.current.key !== formContextKey) {
    formContextRef.current = {
      key: formContextKey,
      version: formContextRef.current.version + 1,
      open,
      selectedProjectId
    };
  }

  const selectedProject = projectOptions.find((project) => project.projectId === selectedProjectId) || null;

  const selectAttachmentFiles = (incoming: File[]) => {
    const merged = mergeImageAttachments(attachmentFiles, incoming);
    setAttachmentFiles(merged.files);
    if (merged.rejected.length === 0) return;

    const reasons = new Set(merged.rejected.map((item) => item.reason));
    const messages = [
      reasons.has("TYPE") ? "仅支持 PNG、JPEG、WebP、GIF" : "",
      reasons.has("SIZE") ? "单张图片不能超过 10 MiB" : "",
      reasons.has("COUNT") ? "最多添加 10 张图片" : ""
    ].filter(Boolean);
    toast.error(`部分图片未添加：${messages.join("；")}`);
  };

  useEffect(() => {
    if (!open) return;
    if (mode === "edit" && task) {
      setTitle(task.title || "");
      setTicketId(task.ticketId || "");
      setTicketTitle(task.ticketTitle || "");
      setPriority(task.priority || "P2");
      setRestoredDraftTime(null);
    } else {
      const savedDraft = loadTaskCreateDraft();
      if (savedDraft) {
        setTitle(savedDraft.title || "");
        setTicketId("");
        setTicketTitle("");
        setPriority(savedDraft.priority || "P2");
        setSelectedProjectId(savedDraft.selectedProjectId || "");
        setBaseBranch(savedDraft.baseBranch || "main");
        setExpectedResult(savedDraft.expectedResult || "");
        setAcceptanceCriteriaText(savedDraft.acceptanceCriteriaText || "");
        setTokenBudgetOverride(savedDraft.tokenBudgetOverride || "0");
        setMaterialSourceType(savedDraft.materialSourceType || "MANUAL_TEXT");
        setManualRequirementText(savedDraft.manualRequirementText || "");
        setFeishuDocumentUrl(savedDraft.feishuDocumentUrl || "");
        setLocalRequirementFile(null);
        setAutoExecute(savedDraft.autoExecute ?? true);
        setAttachmentFiles([]);
        setRestoredDraftTime(savedDraft.savedAt || null);
      } else {
        setTitle("");
        setTicketId("");
        setTicketTitle("");
        setPriority("P2");
        setSelectedProjectId("");
        setBaseBranch("main");
        setExpectedResult("");
        setAcceptanceCriteriaText("");
        setTokenBudgetOverride("0");
        setMaterialSourceType("MANUAL_TEXT");
        setManualRequirementText("");
        setFeishuDocumentUrl("");
        setLocalRequirementFile(null);
        setAutoExecute(true);
        setAttachmentFiles([]);
        setRestoredDraftTime(null);
      }
    }
  }, [open, mode, task]);

  useEffect(() => {
    if (!open || mode !== "create") return;
    saveTaskCreateDraft({
      taskKind: CREATE_TASK_TYPE,
      title,
      selectedProjectId,
      priority,
      baseBranch,
      materialSourceType,
      manualRequirementText,
      feishuDocumentUrl,
      expectedResult,
      acceptanceCriteriaText,
      tokenBudgetOverride,
      autoExecute
    });
  }, [
    open,
    mode,
    title,
    selectedProjectId,
    priority,
    baseBranch,
    materialSourceType,
    manualRequirementText,
    feishuDocumentUrl,
    expectedResult,
    acceptanceCriteriaText,
    tokenBudgetOverride,
    autoExecute
  ]);

  const handleDiscardDraft = () => {
    clearTaskCreateDraft();
    setTitle("");
    setTicketId("");
    setTicketTitle("");
    setPriority("P2");
    setSelectedProjectId("");
    setBaseBranch("main");
    setExpectedResult("");
    setAcceptanceCriteriaText("");
    setTokenBudgetOverride("0");
    setMaterialSourceType("MANUAL_TEXT");
    setManualRequirementText("");
    setFeishuDocumentUrl("");
    setLocalRequirementFile(null);
    setAutoExecute(true);
    setAttachmentFiles([]);
    setRestoredDraftTime(null);
    toast.info("已清空草稿并重置表单");
  };

  const handleClose = () => {
    if (
      mode === "create" &&
      isTaskDraftDirty({
        title,
        manualRequirementText,
        feishuDocumentUrl,
        expectedResult,
        acceptanceCriteriaText
      })
    ) {
      toast.info("草稿已自动保存，再次打开即可继续编辑", { duration: 3000 });
    }
    onOpenChange(false);
  };

  useEffect(() => {
    if (!open || mode !== "create") return;
    let active = true;
    setProjectLoading(true);
    getProjectsPage({ enabled: true, page: 1, pageSize: 200 })
      .then((data) => {
        if (!active) return;
        setProjectOptions(data.records || []);
      })
      .catch((error) => {
        if (!active) return;
        setProjectOptions([]);
        toast.error(getErrorMessage(error, "加载项目列表失败"));
      })
      .finally(() => {
        if (active) setProjectLoading(false);
      });
    return () => {
      active = false;
    };
  }, [open, mode]);

  useEffect(() => {
    if (!selectedProject) return;
    setBaseBranch(selectedProject.defaultBranch || "main");
  }, [selectedProject?.projectId]);

  const applyProjectTemplate = async () => {
    if (!selectedProjectId) {
      toast.error("请先选择项目");
      return;
    }
    const requestedContext = { selectedProjectId, version: formContextRef.current.version };
    setApplyingTemplate(true);
    try {
      const template = await getProjectTaskTemplate(selectedProjectId, CREATE_TASK_TYPE);
      const currentContext = formContextRef.current;
      if (!currentContext.open
          || currentContext.version !== requestedContext.version
          || currentContext.selectedProjectId !== requestedContext.selectedProjectId) {
        toast.info("表单上下文已变化，请重新应用模板");
        return;
      }
      setManualRequirementText((current) => current.trim() ? current : template.requirementBody || "");
      setExpectedResult((current) => current.trim() ? current : template.expectedResult || "");
      setAcceptanceCriteriaText((current) => current.trim() ? current : (template.acceptanceCriteria || []).join("\n"));
      toast.success("已应用项目模板，现有内容未被覆盖");
    } catch (error) {
      toast.error(getErrorMessage(error, "读取项目模板失败"));
    } finally {
      setApplyingTemplate(false);
    }
  };

  const completeWithAi = async () => {
    const requestedContext = { selectedProjectId, version: formContextRef.current.version };
    setDrafting(true);
    try {
      const result = await completeTaskDraft({
        taskType: CREATE_TASK_TYPE,
        projectId: selectedProjectId,
        currentValues: {
          title, requirementBody: manualRequirementText,
          expectedResult, acceptanceCriteria: acceptanceCriteriaText
        },
        materialSummaries: attachmentFiles.map((file) => `${file.name} ${file.type} ${file.size}`)
      });
      const currentContext = formContextRef.current;
      if (!currentContext.open
          || currentContext.version !== requestedContext.version
          || currentContext.selectedProjectId !== requestedContext.selectedProjectId) {
        toast.info("表单上下文已变化，请重新执行 AI 补全");
        return;
      }
      if (!result.available) {
        toast.error(result.reason || "AI 补全当前不可用");
        return;
      }
      setManualRequirementText((current) => current.trim() ? current : result.requirementBody);
      setExpectedResult((current) => current.trim() ? current : result.expectedResult);
      setAcceptanceCriteriaText((current) => current.trim() ? current : result.acceptanceCriteria.join("\n"));
      toast.success("AI 草稿已填入，请确认后再保存");
    } catch (error) {
      toast.error(getErrorMessage(error, "AI 补全失败"));
    } finally {
      setDrafting(false);
    }
  };

  const handleSubmit = async () => {
    const trimmed = title.trim();
    if (!trimmed) {
      toast.error("请输入任务标题");
      return;
    }
    setSaving(true);
    try {
      if (mode === "create") {
        const materialContent = manualRequirementText.trim();
        const materialUrl = feishuDocumentUrl.trim();
        const localFile = localRequirementFile;
        if (!selectedProjectId) {
          toast.error("请选择项目");
          return;
        }
        if (!baseBranch.trim()) {
          toast.error("请输入基准分支");
          return;
        }
        if (!expectedResult.trim()) {
          toast.error("请输入预期结果");
          return;
        }
        const tokenBudget = Number(tokenBudgetOverride || "0");
        if (!Number.isSafeInteger(tokenBudget) || tokenBudget < 0) {
          toast.error("Token 预算覆盖额度必须是非负整数");
          return;
        }
        if (materialSourceType === "MANUAL_TEXT" && !materialContent) {
          toast.error("请输入需求正文");
          return;
        }
        if (materialSourceType === "FEISHU_DOC" && !materialUrl) {
          toast.error("请输入飞书文档 URL");
          return;
        }
        if (materialSourceType === "LOCAL_UPLOAD" && !localFile) {
          toast.error("请选择需求文件");
          return;
        }
        let material: RequirementMaterialPayload;
        if (materialSourceType === "MANUAL_TEXT") {
          material = {
            sourceType: "MANUAL_TEXT",
            materialType: "REQUIREMENT_DOC",
            title: "需求正文",
            content: materialContent,
            mimeType: "text/markdown"
          };
        } else if (materialSourceType === "FEISHU_DOC") {
          material = {
            sourceType: "FEISHU_DOC",
            materialType: "REQUIREMENT_DOC",
            title: "飞书需求文档",
            sourceUri: materialUrl
          };
        } else {
          const fileContent = await localFile!.text();
          if (!fileContent.trim()) {
            toast.error("需求文件内容为空");
            return;
          }
          material = {
            sourceType: "LOCAL_UPLOAD",
            materialType: "REQUIREMENT_DOC",
            title: localFile!.name || "本地需求文件",
            sourceUri: `local-upload://${localFile!.name || "requirement"}`,
            content: fileContent,
            mimeType: localFile!.type || "text/plain"
          };
        }
        const created = await createRequirementTask({
          title: trimmed,
          priority,
          projectId: selectedProjectId,
          baseBranch: baseBranch.trim(),
          expectedResult: expectedResult.trim(),
          acceptanceCriteria: acceptanceCriteriaText
            .split("\n")
            .map((line) => line.trim())
            .filter(Boolean),
          materials: [material],
          autoExecute: autoExecute && attachmentFiles.length === 0,
          tokenBudgetOverride: tokenBudget
        });
        for (const file of attachmentFiles) {
          await uploadTaskMaterial(created.taskId, file, { materialType: "REFERENCE_IMAGE" });
        }
        if (autoExecute && attachmentFiles.length > 0) await submitRdTask(created.taskId);
        clearTaskCreateDraft();
        setRestoredDraftTime(null);
        toast.success("需求任务创建成功");
      } else if (task) {
        await updateRdTask(task.taskId, {
          title: trimmed,
          priority,
          ticketTitle: ticketTitle.trim()
        });
        toast.success("更新成功");
      }
      onOpenChange(false);
      onSuccess();
    } catch (error) {
      toast.error(getErrorMessage(error, mode === "create" ? "创建失败" : "更新失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col overflow-hidden sm:max-w-[860px]" onOpenAutoFocus={(event) => event.preventDefault()}>
        <DialogHeader>
          <DialogTitle>{mode === "create" ? "新建需求任务" : "编辑任务"}</DialogTitle>
          <DialogDescription>
            {mode === "create" ? "创建可进入启动链路的需求任务，初始状态为 CREATED" : "修改任务标题 / 优先级 / 工单"}
          </DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
          {mode === "create" && restoredDraftTime ? (
            <div className="flex items-center justify-between rounded-lg border border-blue-200 bg-blue-50/80 px-3.5 py-2 text-xs text-blue-900 shadow-sm">
              <div className="flex items-center gap-2 min-w-0">
                <Sparkles className="h-4 w-4 text-blue-600 shrink-0" />
                <span className="truncate">已自动恢复上次未提交的草稿（保存于 {formatDraftTime(restoredDraftTime)}）</span>
              </div>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs text-blue-700 hover:bg-blue-100 hover:text-blue-900 shrink-0 ml-2"
                onClick={handleDiscardDraft}
              >
                <Trash2 className="mr-1 h-3.5 w-3.5" />
                清空草稿
              </Button>
            </div>
          ) : null}
          {mode === "create" ? (
            <div className="space-y-3 border-y border-slate-200 py-3">
              <div className="flex flex-wrap items-center gap-2">
                <Button type="button" variant="outline" size="sm" onClick={() => void applyProjectTemplate()} disabled={applyingTemplate}>
                  <ClipboardList className="mr-2 h-4 w-4" />{applyingTemplate ? "应用中" : "应用模板"}
                </Button>
                <Button type="button" variant="outline" size="sm" onClick={() => void completeWithAi()} disabled={drafting}>
                  <Sparkles className="mr-2 h-4 w-4" />{drafting ? "补全中" : "AI 补全"}
                </Button>
                <Button asChild type="button" variant="outline" size="sm">
                  <label className="cursor-pointer">
                    <ImagePlus className="mr-2 h-4 w-4" />添加图片
                    <input
                      type="file"
                      multiple
                      accept="image/png,image/jpeg,image/webp,image/gif"
                      className="sr-only"
                      onChange={(event) => {
                        selectAttachmentFiles(Array.from(event.currentTarget.files || []));
                        event.currentTarget.value = "";
                      }}
                    />
                  </label>
                </Button>
                {attachmentFiles.length > 0 ? (
                  <span className="text-xs text-muted-foreground">已选 {attachmentFiles.length}/10 张，单张不超过 10 MiB</span>
                ) : null}
              </div>
              {attachmentFiles.length > 0 ? (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-5">
                  {attachmentFiles.map((file) => {
                    const key = imageAttachmentKey(file);
                    return (
                      <PendingImageCard
                        key={key}
                        file={file}
                        onRemove={() => setAttachmentFiles((current) => removeImageAttachment(current, key))}
                      />
                    );
                  })}
                </div>
              ) : null}
            </div>
          ) : null}
          <div>
            <label className="mb-2 block text-sm font-medium">标题</label>
            <Input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="例如：增加订单催单功能"
            />
          </div>
          {mode === "create" ? (
            <div>
              <label className="mb-2 block text-sm font-medium">项目</label>
              <Select
                value={selectedProjectId || undefined}
                onValueChange={setSelectedProjectId}
                disabled={projectLoading || projectOptions.length === 0}
              >
                <SelectTrigger>
                  <SelectValue placeholder={projectLoading ? "加载项目中..." : "选择项目"} />
                </SelectTrigger>
                <SelectContent>
                  {projectOptions.map((project) => (
                    <SelectItem key={project.projectId} value={project.projectId}>
                      {project.name} · {project.projectKey}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {projectOptions.length === 0 && !projectLoading ? (
                <div className="mt-2 text-xs text-destructive">暂无启用项目，请先在项目管理中创建</div>
              ) : selectedProject ? (
                <div className="mt-2 truncate text-xs text-muted-foreground" title={selectedProject.repositoryUrl}>
                  {selectedProject.repositoryUrl}
                </div>
              ) : null}
            </div>
          ) : null}
          <div className="grid gap-4 sm:grid-cols-2">
            {mode === "create" ? (
              <div>
                <label className="mb-2 block text-sm font-medium">基准分支</label>
                <Input
                  value={baseBranch}
                  onChange={(event) => setBaseBranch(event.target.value)}
                  placeholder="main"
                />
              </div>
            ) : (
              <div>
                <label className="mb-2 block text-sm font-medium">工单 ID</label>
                <Input value={ticketId} placeholder="自动生成" disabled />
              </div>
            )}
            <div>
              <label className="mb-2 block text-sm font-medium">优先级</label>
              <Select value={priority} onValueChange={setPriority}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRIORITY_OPTIONS.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          {mode === "create" ? (
            <>
              <div>
                <label className="mb-2 block text-sm font-medium">需求材料</label>
                <Select
                  value={materialSourceType}
                  onValueChange={(value) => setMaterialSourceType(value as "MANUAL_TEXT" | "FEISHU_DOC" | "LOCAL_UPLOAD")}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="MANUAL_TEXT">手填正文</SelectItem>
                    <SelectItem value="LOCAL_UPLOAD">本地文件</SelectItem>
                    <SelectItem value="FEISHU_DOC">飞书文档</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              {materialSourceType === "MANUAL_TEXT" ? (
                <div>
                  <label className="mb-2 block text-sm font-medium">需求正文</label>
                  <Textarea
                    value={manualRequirementText}
                    onChange={(event) => setManualRequirementText(event.target.value)}
                    className="min-h-[120px] resize-y"
                    placeholder="写入需求背景、页面/API 改动、边界条件"
                  />
                </div>
              ) : materialSourceType === "FEISHU_DOC" ? (
                <div>
                  <label className="mb-2 block text-sm font-medium">飞书文档 URL</label>
                  <Input
                    value={feishuDocumentUrl}
                    onChange={(event) => setFeishuDocumentUrl(event.target.value)}
                    placeholder="https://example.feishu.cn/docx/..."
                  />
                </div>
              ) : (
                <div>
                  <label className="mb-2 block text-sm font-medium">需求文件</label>
                  <Input
                    type="file"
                    accept=".md,.markdown,.txt,.json,.yaml,.yml"
                    onChange={(event) => setLocalRequirementFile(event.target.files?.[0] ?? null)}
                  />
                  {localRequirementFile ? (
                    <div className="mt-2 truncate text-xs text-muted-foreground">
                      {localRequirementFile.name}
                    </div>
                  ) : null}
                </div>
              )}
              <div>
                <label className="mb-2 block text-sm font-medium">预期结果</label>
                <Textarea
                  value={expectedResult}
                  onChange={(event) => setExpectedResult(event.target.value)}
                  className="min-h-[90px] resize-y"
                  placeholder="描述完成后的用户可见结果和交付边界"
                />
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium">验收标准</label>
                <Textarea
                  value={acceptanceCriteriaText}
                  onChange={(event) => setAcceptanceCriteriaText(event.target.value)}
                  className="min-h-[90px] resize-y"
                  placeholder="每行一条，例如：前端构建通过"
                />
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium">Token 预算覆盖额度</label>
                <Input
                  type="number"
                  min="0"
                  step="1"
                  inputMode="numeric"
                  value={tokenBudgetOverride}
                  onChange={(event) => setTokenBudgetOverride(event.target.value)}
                />
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={autoExecute}
                  onChange={(event) => setAutoExecute(event.target.checked)}
                  className="h-4 w-4 rounded border-slate-300"
                />
                创建后自动执行
              </label>
            </>
          ) : (
            <>
              <div>
                <label className="mb-2 block text-sm font-medium">工单标题</label>
                <Input
                  value={ticketTitle}
                  onChange={(event) => setTicketTitle(event.target.value)}
                  placeholder="工单摘要"
                />
              </div>
            </>
          )}
        </div>
        <DialogFooter className="shrink-0 border-t border-slate-200 pt-4">
          <Button variant="outline" onClick={handleClose} disabled={saving}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={saving}>
            {saving ? "保存中..." : "保存"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
