import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  AlertTriangle,
  ClipboardList,
  FileText,
  GitBranch,
  ListChecks,
  Pencil,
  Play,
  Pause,
  Plus,
  RefreshCw,
  Terminal,
  Trash2
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
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
  createRdTask,
  deleteRdTask,
  getRdTasksPage,
  pauseRdTask,
  resumeRdTask,
  updateRdTask,
  STATUS_BADGE_CLASS,
  type RdTask,
  type RequirementMaterialPayload
} from "@/services/rdTaskService";
import { getProjectsPage, type RdProject } from "@/services/projectService";

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

const TASK_TYPE_OPTIONS = [
  { value: "BUG_FIX", label: "修 Bug" },
  { value: "REQUIREMENT", label: "做需求" }
];

const REPAIR_QUEUE_TOPIC = "RD_BOT_REPAIR_TICKET";

interface BugFixPromptInput {
  title: string;
  ticketTitle: string;
  ticketId: string;
  priority: string;
  project: RdProject | null;
  baseBranch: string;
  actualBehavior: string;
  expectedBehavior: string;
  reproductionSteps: string;
  errorLog: string;
  affectedScope: string;
  acceptanceCriteriaText: string;
  extraContext: string;
}

const createAutoTicketId = () => {
  const randomId = globalThis.crypto?.randomUUID?.();
  if (randomId) {
    return `ticket-${randomId}`;
  }
  return `ticket-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
};

const splitNonEmptyLines = (value: string) =>
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);

const bulletLines = (value: string) => {
  const lines = splitNonEmptyLines(value);
  return lines.length > 0 ? lines.map((line) => `- ${line}`).join("\n") : "- 未填写";
};

const buildBugFixPromptSnapshot = (input: BugFixPromptInput) => {
  const projectText = input.project
    ? [
        `- 项目：${input.project.name} (${input.project.projectKey})`,
        `- 仓库：${input.project.repositoryUrl}`,
        `- 基准分支：${input.baseBranch || input.project.defaultBranch || "main"}`
      ].join("\n")
    : "- 项目：未选择";
  const sections = [
    "# Bug 修复启动上下文",
    [
      `- 工单 ID：${input.ticketId}`,
      `- 优先级：${input.priority}`,
      `- 任务标题：${input.title}`,
      `- 问题摘要：${input.ticketTitle}`
    ].join("\n"),
    `## 项目与仓库\n${projectText}`,
    `## 实际现象\n${input.actualBehavior.trim()}`,
    `## 期望表现\n${input.expectedBehavior.trim()}`,
    `## 复现步骤\n${bulletLines(input.reproductionSteps)}`,
    `## 错误日志或异常栈\n\`\`\`text\n${input.errorLog.trim()}\n\`\`\``,
    input.affectedScope.trim() ? `## 影响范围\n${input.affectedScope.trim()}` : "",
    `## 验收标准\n${bulletLines(input.acceptanceCriteriaText)}`,
    input.extraContext.trim() ? `## 补充上下文\n${input.extraContext.trim()}` : "",
    `## 启动依赖\n- 修复队列 Topic：${REPAIR_QUEUE_TOPIC}`
  ];
  return sections.filter(Boolean).join("\n\n");
};

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
  const [records, setRecords] = useState<RdTask[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [loading, setLoading] = useState(false);

  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [taskTypeFilter, setTaskTypeFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState("");
  const [searchInput, setSearchInput] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<RdTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RdTask | null>(null);

  const loadTasks = async (
    nextPage = page,
    nextStatus = statusFilter,
    nextKeyword = keyword,
    nextTaskType = taskTypeFilter
  ) => {
    setLoading(true);
    try {
      const data = await getRdTasksPage({
        taskType: nextTaskType,
        status: nextStatus,
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
    loadTasks(1);
  }, []);

  const handleSearch = () => {
    setKeyword(searchInput.trim());
    loadTasks(1, statusFilter, searchInput.trim(), taskTypeFilter);
  };

  const handleStatusChange = (value: string) => {
    const next = value === "all" ? undefined : value;
    setStatusFilter(next);
    setPage(1);
    loadTasks(1, next, keyword, taskTypeFilter);
  };

  const handleTaskTypeChange = (value: string) => {
    const next = value === "all" ? undefined : value;
    setTaskTypeFilter(next);
    setPage(1);
    loadTasks(1, statusFilter, keyword, next);
  };

  const handleRefresh = () => {
    loadTasks(1, statusFilter, keyword, taskTypeFilter);
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
      loadTasks(page, statusFilter, keyword, taskTypeFilter);
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
      loadTasks(page, statusFilter, keyword, taskTypeFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

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

        <Card>
          <CardContent className="min-w-0 pt-6">
            {loading ? (
              <div className="py-8 text-center text-muted-foreground">加载中...</div>
            ) : records.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">
                暂无任务，点击「新建任务」创建
              </div>
            ) : (
              <Table className="min-w-[1080px] table-fixed">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[230px]">标题</TableHead>
                    <TableHead className="w-[80px]">类型</TableHead>
                    <TableHead className="w-[120px]">项目</TableHead>
                    <TableHead className="w-[130px]">工单</TableHead>
                    <TableHead className="w-[72px]">优先级</TableHead>
                    <TableHead className="w-[110px]">状态</TableHead>
                    <TableHead className="w-[64px]">暂停</TableHead>
                    <TableHead className="w-[118px]">更新时间</TableHead>
                    <TableHead className="w-[176px] text-left">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {records.map((task) => (
                    <TableRow key={task.taskId}>
                      <TableCell className="font-medium">
                        <button
                          type="button"
                          className="admin-link block max-w-full truncate"
                          title={task.title}
                          onClick={() => navigate(`/admin/rd-tasks/${task.taskId}`)}
                        >
                          {task.title || "-"}
                        </button>
                        {task.errorMessage ? (
                          <div className="mt-0.5 truncate text-xs text-destructive">
                            {truncate(task.errorMessage, 50)}
                          </div>
                        ) : null}
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">
                          {task.taskType === "REQUIREMENT" ? "做需求" : "修 Bug"}
                        </Badge>
                      </TableCell>
                      <TableCell className="truncate text-sm text-muted-foreground">
                        <span title={task.projectName || task.projectKey || "-"}>
                          {task.projectName || task.projectKey || "-"}
                        </span>
                      </TableCell>
                      <TableCell className="truncate text-sm text-muted-foreground">
                        <span title={task.taskType === "REQUIREMENT" ? (task.baseBranch || "-") : (task.ticketId || "-")}>
                          {task.taskType === "REQUIREMENT" ? (task.baseBranch || "-") : (task.ticketId || "-")}
                        </span>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">{task.priority}</Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className={STATUS_BADGE_CLASS[task.status] || ""}>
                          {task.status}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        {task.paused ? (
                          <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">
                            已暂停
                          </Badge>
                        ) : (
                          <span className="text-muted-foreground/40">-</span>
                        )}
                      </TableCell>
                      <TableCell>
                        <RelativeTime value={new Date(task.updateTimeEpochMillis).toISOString()} />
                      </TableCell>
                      <TableCell>
                        <div className="grid grid-cols-2 gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => navigate(`/admin/rd-tasks/${task.taskId}`)}
                          >
                            <ClipboardList className="mr-1 h-4 w-4" />
                            详情
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setEditTarget(task)}
                          >
                            <Pencil className="mr-1 h-4 w-4" />
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleTogglePause(task)}
                          >
                            {task.paused ? (
                              <>
                                <Play className="mr-1 h-4 w-4" />
                                重启
                              </>
                            ) : (
                              <>
                                <Pause className="mr-1 h-4 w-4" />
                                暂停
                              </>
                            )}
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setDeleteTarget(task)}
                          >
                            <Trash2 className="mr-1 h-4 w-4" />
                            删除
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}

            <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
              <span>共 {total} 条</span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => loadTasks(Math.max(1, page - 1), statusFilter, keyword, taskTypeFilter)}
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
                  onClick={() => loadTasks(Math.min(pages || 1, page + 1), statusFilter, keyword, taskTypeFilter)}
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
          onSuccess={() => loadTasks(1, statusFilter, keyword, taskTypeFilter)}
        />
        <RdTaskEditDialog
          open={!!editTarget}
          mode="edit"
          task={editTarget}
          onOpenChange={(open) => setEditTarget(open ? editTarget : null)}
          onSuccess={() => loadTasks(page, statusFilter, keyword, taskTypeFilter)}
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
  const [taskKind, setTaskKind] = useState<"BUG_FIX" | "REQUIREMENT">("BUG_FIX");
  const [title, setTitle] = useState("");
  const [ticketId, setTicketId] = useState("");
  const [autoTicketId, setAutoTicketId] = useState(createAutoTicketId);
  const [ticketTitle, setTicketTitle] = useState("");
  const [priority, setPriority] = useState("P2");
  const [promptSnapshot, setPromptSnapshot] = useState("");
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [projectOptions, setProjectOptions] = useState<RdProject[]>([]);
  const [projectLoading, setProjectLoading] = useState(false);
  const [baseBranch, setBaseBranch] = useState("main");
  const [expectedResult, setExpectedResult] = useState("");
  const [acceptanceCriteriaText, setAcceptanceCriteriaText] = useState("");
  const [materialSourceType, setMaterialSourceType] = useState<"MANUAL_TEXT" | "FEISHU_DOC" | "LOCAL_UPLOAD">("MANUAL_TEXT");
  const [manualRequirementText, setManualRequirementText] = useState("");
  const [feishuDocumentUrl, setFeishuDocumentUrl] = useState("");
  const [localRequirementFile, setLocalRequirementFile] = useState<File | null>(null);
  const [autoExecute, setAutoExecute] = useState(true);
  const [bugActualBehavior, setBugActualBehavior] = useState("");
  const [bugExpectedBehavior, setBugExpectedBehavior] = useState("");
  const [bugReproductionSteps, setBugReproductionSteps] = useState("");
  const [bugErrorLog, setBugErrorLog] = useState("");
  const [bugAffectedScope, setBugAffectedScope] = useState("");
  const [saving, setSaving] = useState(false);

  const selectedProject = projectOptions.find((project) => project.projectId === selectedProjectId) || null;

  useEffect(() => {
    if (!open) return;
    if (mode === "edit" && task) {
      setTitle(task.title || "");
      setTicketId(task.ticketId || "");
      setAutoTicketId(task.ticketId || createAutoTicketId());
      setTicketTitle(task.ticketTitle || "");
      setPriority(task.priority || "P2");
      setPromptSnapshot("");
      setTaskKind("BUG_FIX");
    } else {
      setTaskKind("BUG_FIX");
      setTitle("");
      setTicketId("");
      setAutoTicketId(createAutoTicketId());
      setTicketTitle("");
      setPriority("P2");
      setPromptSnapshot("");
      setSelectedProjectId("");
      setBaseBranch("main");
      setExpectedResult("");
      setAcceptanceCriteriaText("");
      setMaterialSourceType("MANUAL_TEXT");
      setManualRequirementText("");
      setFeishuDocumentUrl("");
      setLocalRequirementFile(null);
      setAutoExecute(true);
      setBugActualBehavior("");
      setBugExpectedBehavior("");
      setBugReproductionSteps("");
      setBugErrorLog("");
      setBugAffectedScope("");
    }
  }, [open, mode, task]);

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

  const handleSubmit = async () => {
    const trimmed = title.trim();
    if (!trimmed) {
      toast.error("请输入任务标题");
      return;
    }
    setSaving(true);
    try {
      if (mode === "create" && taskKind === "REQUIREMENT") {
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
        await createRequirementTask({
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
          autoExecute
        });
        toast.success("需求任务创建成功");
      } else if (mode === "create") {
        if (!selectedProjectId) {
          toast.error("请选择项目");
          return;
        }
        const summary = ticketTitle.trim();
        const actualBehavior = bugActualBehavior.trim();
        const expectedBehavior = bugExpectedBehavior.trim();
        const reproductionSteps = bugReproductionSteps.trim();
        const errorLog = bugErrorLog.trim();
        const acceptanceCriteria = splitNonEmptyLines(acceptanceCriteriaText);
        if (!summary) {
          toast.error("请输入问题摘要");
          return;
        }
        if (!actualBehavior) {
          toast.error("请输入实际现象");
          return;
        }
        if (!expectedBehavior) {
          toast.error("请输入期望表现");
          return;
        }
        if (!reproductionSteps) {
          toast.error("请输入复现步骤");
          return;
        }
        if (!errorLog) {
          toast.error("请输入错误日志或异常栈");
          return;
        }
        if (acceptanceCriteria.length === 0) {
          toast.error("请输入验收标准");
          return;
        }
        const generatedTicketId = autoTicketId || createAutoTicketId();
        setAutoTicketId(generatedTicketId);
        await createRdTask({
          title: trimmed,
          ticketId: generatedTicketId,
          ticketTitle: summary,
          priority,
          promptSnapshot: buildBugFixPromptSnapshot({
            title: trimmed,
            ticketTitle: summary,
            ticketId: generatedTicketId,
            priority,
            project: selectedProject,
            baseBranch,
            actualBehavior,
            expectedBehavior,
            reproductionSteps,
            errorLog,
            affectedScope: bugAffectedScope,
            acceptanceCriteriaText,
            extraContext: promptSnapshot
          }),
          projectId: selectedProjectId,
          autoExecute
        });
        toast.success(autoExecute ? "创建成功，已提交修复执行" : "创建成功");
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
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[860px]" onOpenAutoFocus={(event) => event.preventDefault()}>
        <DialogHeader>
          <DialogTitle>{mode === "create" ? "新建任务" : "编辑任务"}</DialogTitle>
          <DialogDescription>
            {mode === "create" ? "创建可进入启动链路的 RD 任务，初始状态为 CREATED" : "修改任务标题 / 优先级 / 工单"}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          {mode === "create" ? (
            <div>
              <label className="mb-2 block text-sm font-medium">任务类型</label>
              <Select value={taskKind} onValueChange={(value) => setTaskKind(value as "BUG_FIX" | "REQUIREMENT")}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TASK_TYPE_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}
          <div>
            <label className="mb-2 block text-sm font-medium">标题</label>
            <Input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder={taskKind === "REQUIREMENT" ? "例如：增加订单催单功能" : "例如：支付下单接口 500 修复"}
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
          {mode === "create" && taskKind === "BUG_FIX" ? (
            <>
              <div className="grid gap-3 rounded-2xl border border-border/80 bg-muted/30 p-4 sm:grid-cols-[1.2fr_1fr]">
                <div className="flex min-w-0 items-start gap-3">
                  <ClipboardList className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                  <div className="min-w-0">
                    <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      自动工单 ID
                    </div>
                    <div className="mt-1 truncate font-mono text-sm" title={autoTicketId}>
                      {autoTicketId}
                    </div>
                  </div>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      <Terminal className="h-3.5 w-3.5" />
                      启动队列
                    </div>
                    <div className="mt-1 truncate font-mono text-sm" title={REPAIR_QUEUE_TOPIC}>
                      {REPAIR_QUEUE_TOPIC}
                    </div>
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setAutoTicketId(createAutoTicketId())}
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    重新生成
                  </Button>
                </div>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="mb-2 flex items-center gap-2 text-sm font-medium">
                    <GitBranch className="h-4 w-4 text-muted-foreground" />
                    基准分支
                  </label>
                  <Input
                    value={baseBranch || selectedProject?.defaultBranch || "main"}
                    readOnly
                    className="bg-muted/40"
                  />
                </div>
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
            </>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2">
              {mode === "create" && taskKind === "REQUIREMENT" ? (
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
                  <Input
                    value={ticketId}
                    onChange={(event) => setTicketId(event.target.value)}
                    placeholder="自动生成"
                    disabled={mode === "edit"}
                  />
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
          )}
          {mode === "create" && taskKind === "REQUIREMENT" ? (
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
          ) : mode === "create" ? (
            <>
              <div>
                <label className="mb-2 flex items-center gap-2 text-sm font-medium">
                  <FileText className="h-4 w-4 text-muted-foreground" />
                  问题摘要
                </label>
                <Input
                  value={ticketTitle}
                  onChange={(event) => setTicketTitle(event.target.value)}
                  placeholder="例如：恢复任务时报 No route info of topic RD_BOT_REPAIR_TICKET"
                />
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="mb-2 block text-sm font-medium">实际现象</label>
                  <Textarea
                    value={bugActualBehavior}
                    onChange={(event) => setBugActualBehavior(event.target.value)}
                    className="min-h-[110px] resize-y"
                    placeholder="用户操作、接口响应、页面状态或任务状态的异常表现"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-medium">期望表现</label>
                  <Textarea
                    value={bugExpectedBehavior}
                    onChange={(event) => setBugExpectedBehavior(event.target.value)}
                    className="min-h-[110px] resize-y"
                    placeholder="修复后应当达到的状态、返回值或可观测结果"
                  />
                </div>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="mb-2 block text-sm font-medium">复现步骤</label>
                  <Textarea
                    value={bugReproductionSteps}
                    onChange={(event) => setBugReproductionSteps(event.target.value)}
                    className="min-h-[132px] resize-y"
                    placeholder={"1. 打开任务管理\n2. 点击暂停任务的重启\n3. 观察接口返回和任务状态"}
                  />
                </div>
                <div>
                  <label className="mb-2 flex items-center gap-2 text-sm font-medium">
                    <Terminal className="h-4 w-4 text-muted-foreground" />
                    错误日志或异常栈
                  </label>
                  <Textarea
                    value={bugErrorLog}
                    onChange={(event) => setBugErrorLog(event.target.value)}
                    className="min-h-[132px] resize-y font-mono text-xs"
                    placeholder={`org.apache.rocketmq.client.exception.MQClientException: No route info of this topic: ${REPAIR_QUEUE_TOPIC}`}
                  />
                </div>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="mb-2 block text-sm font-medium">影响范围</label>
                  <Textarea
                    value={bugAffectedScope}
                    onChange={(event) => setBugAffectedScope(event.target.value)}
                    className="min-h-[90px] resize-y"
                    placeholder="涉及页面、接口、状态机节点、队列或外部依赖"
                  />
                </div>
                <div>
                  <label className="mb-2 flex items-center gap-2 text-sm font-medium">
                    <ListChecks className="h-4 w-4 text-muted-foreground" />
                    验收标准
                  </label>
                  <Textarea
                    value={acceptanceCriteriaText}
                    onChange={(event) => setAcceptanceCriteriaText(event.target.value)}
                    className="min-h-[90px] resize-y"
                    placeholder={"每行一条，例如：\n重启任务不再返回队列发布失败\n任务详情能看到失败原因或恢复后的状态"}
                  />
                </div>
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium">补充上下文（可选）</label>
                <Textarea
                  value={promptSnapshot}
                  onChange={(event) => setPromptSnapshot(event.target.value)}
                  className="min-h-[90px] resize-y"
                  placeholder="相关提交、配置、临时绕过方式、排查命令或备注"
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
              <div className="flex gap-3 rounded-2xl border border-amber-200 bg-amber-50/70 p-4 text-sm text-amber-800">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  报错中出现的 {REPAIR_QUEUE_TOPIC} 无路由属于启动依赖问题；本表单会把队列、项目、分支、复现步骤与异常栈写入启动上下文。
                </div>
              </div>
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
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
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
