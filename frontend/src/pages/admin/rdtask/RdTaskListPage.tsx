import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ClipboardList, Pencil, Play, Pause, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
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
  createRdTask,
  deleteRdTask,
  getRdTasksPage,
  pauseRdTask,
  resumeRdTask,
  updateRdTask,
  STATUS_BADGE_CLASS,
  type RdTask
} from "@/services/rdTaskService";

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "CREATED", label: "已创建" },
  { value: "SEARCHING", label: "检索中" },
  { value: "EXECUTING", label: "执行中" },
  { value: "COMMITTED", label: "已提交" },
  { value: "MERGED", label: "已合并" },
  { value: "REJECTED", label: "已打回" }
];

const PRIORITY_OPTIONS = ["P0", "P1", "P2"];

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
  const [keyword, setKeyword] = useState("");
  const [searchInput, setSearchInput] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<RdTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RdTask | null>(null);

  const loadTasks = async (
    nextPage = page,
    nextStatus = statusFilter,
    nextKeyword = keyword
  ) => {
    setLoading(true);
    try {
      const data = await getRdTasksPage({
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
    loadTasks(1, statusFilter, searchInput.trim());
  };

  const handleStatusChange = (value: string) => {
    const next = value === "all" ? undefined : value;
    setStatusFilter(next);
    setPage(1);
    loadTasks(1, next, keyword);
  };

  const handleRefresh = () => {
    loadTasks(1, statusFilter, keyword);
  };

  const handleTogglePause = async (task: RdTask) => {
    try {
      if (task.paused) {
        await resumeRdTask(task.taskId);
        toast.success("已恢复");
      } else {
        await pauseRdTask(task.taskId);
        toast.success("已暂停");
      }
      loadTasks(page, statusFilter, keyword);
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
      loadTasks(page, statusFilter, keyword);
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
          <CardContent className="pt-6">
            {loading ? (
              <div className="py-8 text-center text-muted-foreground">加载中...</div>
            ) : records.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">
                暂无任务，点击「新建任务」创建
              </div>
            ) : (
              <Table className="min-w-[960px]">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[220px]">标题</TableHead>
                    <TableHead className="w-[120px]">工单</TableHead>
                    <TableHead className="w-[80px]">优先级</TableHead>
                    <TableHead className="w-[110px]">状态</TableHead>
                    <TableHead className="w-[70px]">暂停</TableHead>
                    <TableHead className="w-[150px]">更新时间</TableHead>
                    <TableHead className="w-[200px] text-left">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {records.map((task) => (
                    <TableRow key={task.taskId}>
                      <TableCell className="font-medium">
                        <button
                          type="button"
                          className="admin-link max-w-[220px] truncate"
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
                      <TableCell className="text-sm text-muted-foreground">
                        {task.ticketId || "-"}
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
                        <div className="flex flex-wrap gap-2">
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
                                恢复
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
                  onClick={() => loadTasks(Math.max(1, page - 1), statusFilter, keyword)}
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
                  onClick={() => loadTasks(Math.min(pages || 1, page + 1), statusFilter, keyword)}
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
          onSuccess={() => loadTasks(1, statusFilter, keyword)}
        />
        <RdTaskEditDialog
          open={!!editTarget}
          mode="edit"
          task={editTarget}
          onOpenChange={(open) => setEditTarget(open ? editTarget : null)}
          onSuccess={() => loadTasks(page, statusFilter, keyword)}
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
  const [promptSnapshot, setPromptSnapshot] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (mode === "edit" && task) {
      setTitle(task.title || "");
      setTicketId(task.ticketId || "");
      setTicketTitle(task.ticketTitle || "");
      setPriority(task.priority || "P2");
      setPromptSnapshot("");
    } else {
      setTitle("");
      setTicketId("");
      setTicketTitle("");
      setPriority("P2");
      setPromptSnapshot("");
    }
  }, [open, mode, task]);

  const handleSubmit = async () => {
    const trimmed = title.trim();
    if (!trimmed) {
      toast.error("请输入任务标题");
      return;
    }
    setSaving(true);
    try {
      if (mode === "create") {
        await createRdTask({
          title: trimmed,
          ticketId: ticketId.trim(),
          ticketTitle: ticketTitle.trim(),
          priority,
          promptSnapshot: promptSnapshot.trim()
        });
        toast.success("创建成功");
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
      <DialogContent className="sm:max-w-[520px]" onOpenAutoFocus={(event) => event.preventDefault()}>
        <DialogHeader>
          <DialogTitle>{mode === "create" ? "新建任务" : "编辑任务"}</DialogTitle>
          <DialogDescription>
            {mode === "create" ? "创建一个 RD 任务，初始状态为 CREATED" : "修改任务标题 / 优先级 / 工单"}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <label className="mb-2 block text-sm font-medium">标题</label>
            <Input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="例如：支付下单接口 500 修复"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-2 block text-sm font-medium">工单 ID</label>
              <Input
                value={ticketId}
                onChange={(event) => setTicketId(event.target.value)}
                placeholder="FS-1001"
                disabled={mode === "edit"}
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
          <div>
            <label className="mb-2 block text-sm font-medium">工单标题</label>
            <Input
              value={ticketTitle}
              onChange={(event) => setTicketTitle(event.target.value)}
              placeholder="工单摘要"
            />
          </div>
          {mode === "create" ? (
            <div>
              <label className="mb-2 block text-sm font-medium">Prompt 快照（可选）</label>
              <Input
                value={promptSnapshot}
                onChange={(event) => setPromptSnapshot(event.target.value)}
                placeholder="发送给执行器的初始 Prompt"
              />
            </div>
          ) : null}
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
