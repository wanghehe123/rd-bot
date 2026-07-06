import { useEffect, useMemo, useState } from "react";
import { FolderOpen, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
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
import { Textarea } from "@/components/ui/textarea";
import { RelativeTime } from "@/components/RelativeTime";
import { getErrorMessage } from "@/utils/error";

import {
  createProject,
  deleteProject,
  getProjectsPage,
  updateProject,
  type RdProject,
  type RdProjectPayload
} from "@/services/projectService";

const PAGE_SIZE = 10;

type EnabledFilter = "all" | "enabled" | "disabled";

const repositoryLabel = (project: RdProject) => {
  if (project.repoOwner && project.repoName) {
    return `${project.repoOwner}/${project.repoName}`;
  }
  return project.repositoryUrl || "-";
};

export function ProjectListPage() {
  const [records, setRecords] = useState<RdProject[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [enabledFilter, setEnabledFilter] = useState<EnabledFilter>("all");
  const [editTarget, setEditTarget] = useState<RdProject | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RdProject | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const enabledParam = useMemo(() => {
    if (enabledFilter === "enabled") return true;
    if (enabledFilter === "disabled") return false;
    return undefined;
  }, [enabledFilter]);

  const loadProjects = async (nextPage = page, nextKeyword = keyword, nextEnabled = enabledParam) => {
    setLoading(true);
    try {
      const data = await getProjectsPage({
        keyword: nextKeyword,
        enabled: nextEnabled,
        page: nextPage,
        pageSize: PAGE_SIZE
      });
      setRecords(data.records || []);
      setPage(data.page);
      setTotal(data.total);
      setPages(data.pages);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载项目列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProjects(1);
  }, []);

  const handleSearch = () => {
    const nextKeyword = searchInput.trim();
    setKeyword(nextKeyword);
    setPage(1);
    loadProjects(1, nextKeyword, enabledParam);
  };

  const handleEnabledChange = (value: string) => {
    const nextFilter = value as EnabledFilter;
    const nextEnabled = nextFilter === "enabled" ? true : nextFilter === "disabled" ? false : undefined;
    setEnabledFilter(nextFilter);
    setPage(1);
    loadProjects(1, keyword, nextEnabled);
  };

  const handleRefresh = () => {
    loadProjects(1, keyword, enabledParam);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteProject(deleteTarget.projectId);
      toast.success("项目已删除");
      setDeleteTarget(null);
      loadProjects(1, keyword, enabledParam);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除项目失败"));
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">项目管理</h1>
          <p className="admin-page-subtitle">维护 RD 任务可选择的项目与仓库配置</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder="搜索项目 / 仓库"
            className="w-[220px]"
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
          />
          <Select value={enabledFilter} onValueChange={handleEnabledChange}>
            <SelectTrigger className="w-[130px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部项目</SelectItem>
              <SelectItem value="enabled">仅启用</SelectItem>
              <SelectItem value="disabled">仅停用</SelectItem>
            </SelectContent>
          </Select>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => setCreateOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            新建项目
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="min-w-0 pt-6">
          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无项目</div>
          ) : (
            <Table className="min-w-[1040px] table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[180px]">项目</TableHead>
                  <TableHead className="w-[150px]">标识</TableHead>
                  <TableHead className="w-[220px]">仓库</TableHead>
                  <TableHead className="w-[120px]">默认分支</TableHead>
                  <TableHead className="w-[90px]">状态</TableHead>
                  <TableHead className="w-[130px]">更新时间</TableHead>
                  <TableHead className="w-[150px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((project) => (
                  <TableRow key={project.projectId}>
                    <TableCell className="font-medium">
                      <div className="flex min-w-0 items-center gap-2">
                        <FolderOpen className="h-4 w-4 shrink-0 text-muted-foreground" />
                        <span className="truncate" title={project.name}>{project.name}</span>
                      </div>
                      {project.description ? (
                        <div className="mt-0.5 truncate text-xs text-muted-foreground" title={project.description}>
                          {project.description}
                        </div>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <code className="rounded bg-slate-100 px-2 py-1 text-xs text-slate-700">
                        {project.projectKey}
                      </code>
                    </TableCell>
                    <TableCell className="truncate">
                      <a
                        href={project.repositoryUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="admin-link block truncate"
                        title={project.repositoryUrl}
                      >
                        {repositoryLabel(project)}
                      </a>
                    </TableCell>
                    <TableCell>
                      <code className="rounded bg-slate-100 px-2 py-1 text-xs text-slate-700">
                        {project.defaultBranch || "-"}
                      </code>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant="outline"
                        className={project.enabled ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-slate-50 text-slate-500"}
                      >
                        {project.enabled ? "启用" : "停用"}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <RelativeTime value={new Date(project.updateTimeEpochMillis).toISOString()} />
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        <Button size="sm" variant="outline" onClick={() => setEditTarget(project)}>
                          <Pencil className="mr-1 h-4 w-4" />
                          编辑
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(project)}
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
                onClick={() => loadProjects(Math.max(1, page - 1), keyword, enabledParam)}
                disabled={page <= 1}
              >
                上一页
              </Button>
              <span>{page} / {pages || 1}</span>
              <Button
                variant="outline"
                size="sm"
                onClick={() => loadProjects(Math.min(pages || 1, page + 1), keyword, enabledParam)}
                disabled={page >= pages}
              >
                下一页
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <ProjectEditDialog
        open={createOpen}
        mode="create"
        onOpenChange={setCreateOpen}
        onSuccess={() => loadProjects(1, keyword, enabledParam)}
      />
      <ProjectEditDialog
        open={!!editTarget}
        mode="edit"
        project={editTarget}
        onOpenChange={(open) => setEditTarget(open ? editTarget : null)}
        onSuccess={() => loadProjects(page, keyword, enabledParam)}
      />

      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除项目？</AlertDialogTitle>
            <AlertDialogDescription>
              项目 [{deleteTarget?.name}] 将被逻辑删除，已创建任务保留项目快照。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

interface ProjectEditDialogProps {
  open: boolean;
  mode: "create" | "edit";
  project?: RdProject | null;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

function ProjectEditDialog({ open, mode, project, onOpenChange, onSuccess }: ProjectEditDialogProps) {
  const [projectKey, setProjectKey] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [repositoryUrl, setRepositoryUrl] = useState("");
  const [defaultBranch, setDefaultBranch] = useState("main");
  const [enabled, setEnabled] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    setProjectKey(project?.projectKey || "");
    setName(project?.name || "");
    setDescription(project?.description || "");
    setRepositoryUrl(project?.repositoryUrl || "");
    setDefaultBranch(project?.defaultBranch || "main");
    setEnabled(project?.enabled ?? true);
  }, [open, project]);

  const handleSubmit = async () => {
    const payload: RdProjectPayload = {
      projectKey: projectKey.trim(),
      name: name.trim(),
      description: description.trim(),
      repositoryUrl: repositoryUrl.trim(),
      defaultBranch: defaultBranch.trim(),
      enabled
    };
    if (!payload.projectKey) {
      toast.error("请输入项目标识");
      return;
    }
    if (!payload.name) {
      toast.error("请输入项目名称");
      return;
    }
    if (!payload.repositoryUrl) {
      toast.error("请输入仓库地址");
      return;
    }
    if (!payload.defaultBranch) {
      toast.error("请输入默认分支");
      return;
    }
    setSaving(true);
    try {
      if (mode === "edit" && project) {
        await updateProject(project.projectId, payload);
        toast.success("项目已更新");
      } else {
        await createProject(payload);
        toast.success("项目已创建");
      }
      onOpenChange(false);
      onSuccess();
    } catch (error) {
      toast.error(getErrorMessage(error, mode === "edit" ? "更新项目失败" : "创建项目失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[560px]" onOpenAutoFocus={(event) => event.preventDefault()}>
        <DialogHeader>
          <DialogTitle>{mode === "edit" ? "编辑项目" : "新建项目"}</DialogTitle>
          <DialogDescription>配置项目标识、仓库与默认分支</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-2 block text-sm font-medium">项目标识</label>
              <Input value={projectKey} onChange={(event) => setProjectKey(event.target.value)} placeholder="waimai" />
            </div>
            <div>
              <label className="mb-2 block text-sm font-medium">项目名称</label>
              <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="外卖系统" />
            </div>
          </div>
          <div>
            <label className="mb-2 block text-sm font-medium">仓库地址</label>
            <Input
              value={repositoryUrl}
              onChange={(event) => setRepositoryUrl(event.target.value)}
              placeholder="https://github.com/example/waimai.git"
            />
          </div>
          <div>
            <label className="mb-2 block text-sm font-medium">默认分支</label>
            <Input value={defaultBranch} onChange={(event) => setDefaultBranch(event.target.value)} placeholder="main" />
          </div>
          <div>
            <label className="mb-2 block text-sm font-medium">描述</label>
            <Textarea
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              className="min-h-[90px] resize-y"
              placeholder="项目用途或交付边界"
            />
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
              className="h-4 w-4 rounded border-slate-300"
            />
            启用项目
          </label>
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
