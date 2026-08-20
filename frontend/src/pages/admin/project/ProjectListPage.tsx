import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Bell,
  Database,
  FolderOpen,
  Gauge,
  LayoutTemplate,
  MonitorCheck,
  MoreHorizontal,
  Pencil,
  Plus,
  RefreshCw,
  SlidersHorizontal,
  Trash2
} from "lucide-react";
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
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
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
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { RelativeTime } from "@/components/RelativeTime";
import { getErrorMessage } from "@/utils/error";
import { normalizeAlertRecipients } from "./alertRecipients";
import { normalizeProjectKnowledgeBaseId, UNBOUND_KNOWLEDGE_BASE_VALUE } from "./projectKnowledgeBinding";

import {
  createProject,
  deleteProject,
  getProjectsPage,
  getProjectAlertConfig,
  getProjectTokenBudget,
  getProjectTaskTemplate,
  getProjectQaProfile,
  updateProjectAlertConfig,
  updateProjectTokenBudget,
  updateProjectTaskTemplate,
  updateProjectQaProfile,
  updateProject,
  type RdProject,
  type ProjectAlertEventType,
  type ProjectQaMode,
  type RdProjectPayload
} from "@/services/projectService";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";

const PAGE_SIZE = 10;

type EnabledFilter = "all" | "enabled" | "disabled";

const repositoryLabel = (project: RdProject) => {
  if (project.repoOwner && project.repoName) {
    return `${project.repoOwner}/${project.repoName}`;
  }
  return project.repositoryUrl || "-";
};

export function ProjectListPage() {
  const navigate = useNavigate();
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
  const [alertTarget, setAlertTarget] = useState<RdProject | null>(null);
  const [tokenBudgetTarget, setTokenBudgetTarget] = useState<RdProject | null>(null);
  const [templateTarget, setTemplateTarget] = useState<RdProject | null>(null);
  const [qaProfileTarget, setQaProfileTarget] = useState<RdProject | null>(null);
  const [knowledgeTarget, setKnowledgeTarget] = useState<RdProject | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [knowledgeBasesLoading, setKnowledgeBasesLoading] = useState(false);

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
    void loadKnowledgeBases();
  }, []);

  const loadKnowledgeBases = async () => {
    setKnowledgeBasesLoading(true);
    try {
      const data = await getKnowledgeBases(1, 100);
      setKnowledgeBases(data.records || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载知识库失败"));
    } finally {
      setKnowledgeBasesLoading(false);
    }
  };

  const knowledgeBaseById = useMemo(
    () => new Map(knowledgeBases.map((knowledgeBase) => [knowledgeBase.id, knowledgeBase])),
    [knowledgeBases]
  );

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
    <TooltipProvider delayDuration={300}>
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
            <div className="overflow-x-auto">
              <Table className="min-w-[1180px] table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[180px]">项目</TableHead>
                  <TableHead className="w-[150px]">标识</TableHead>
                  <TableHead className="w-[220px]">仓库</TableHead>
                  <TableHead className="w-[120px]">默认分支</TableHead>
                  <TableHead className="w-[150px]">项目知识库</TableHead>
                  <TableHead className="w-[90px]">状态</TableHead>
                  <TableHead className="w-[130px]">更新时间</TableHead>
                  <TableHead className="w-[200px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((project) => (
                  <TableRow key={project.projectId}>
                    <TableCell className="font-medium">
                      <div className="flex min-w-0 items-center gap-2">
                        <FolderOpen className="h-4 w-4 shrink-0 text-muted-foreground" />
                        <span className="truncate font-semibold text-slate-900" title={project.name}>{project.name}</span>
                      </div>
                      {project.description ? (
                        <div className="mt-0.5 truncate text-xs text-muted-foreground" title={project.description}>
                          {project.description}
                        </div>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <code className="rounded bg-slate-100 px-2 py-1 font-mono text-xs text-slate-700">
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
                      <code className="rounded bg-slate-100 px-2 py-1 font-mono text-xs text-slate-700">
                        {project.defaultBranch || "-"}
                      </code>
                    </TableCell>
                    <TableCell className="truncate">
                      {project.knowledgeBaseId ? (
                        <span title={knowledgeBaseById.get(project.knowledgeBaseId)?.name || project.knowledgeBaseId}>
                          {knowledgeBaseById.get(project.knowledgeBaseId)?.name || project.knowledgeBaseId}
                        </span>
                      ) : (
                        <span className="text-muted-foreground/50">-</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant="outline"
                        className={project.enabled ? "border-emerald-200 bg-emerald-50 text-emerald-700 font-medium" : "border-slate-200 bg-slate-50 text-slate-500"}
                      >
                        {project.enabled ? "启用" : "停用"}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <RelativeTime value={new Date(project.updateTimeEpochMillis).toISOString()} />
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 gap-1 px-2.5 text-xs font-medium text-slate-700 hover:text-primary hover:border-primary/40"
                          onClick={() => navigate(`/admin/projects/${project.projectId}/agent-strategy`)}
                        >
                          <SlidersHorizontal className="h-3.5 w-3.5 text-primary" />
                          <span>策略</span>
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 gap-1 px-2.5 text-xs text-slate-700 hover:text-slate-950"
                          onClick={() => setEditTarget(project)}
                        >
                          <Pencil className="h-3.5 w-3.5 text-slate-500" />
                          <span>编辑</span>
                        </Button>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-8 w-8 p-0 text-slate-500 hover:text-slate-900"
                              aria-label="更多配置"
                              title="更多配置"
                            >
                              <MoreHorizontal className="h-4 w-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-44">
                            <DropdownMenuItem onClick={() => setKnowledgeTarget(project)}>
                              <Database className="mr-2 h-4 w-4 text-muted-foreground" />
                              <span>绑定知识库</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => setTokenBudgetTarget(project)}>
                              <Gauge className="mr-2 h-4 w-4 text-muted-foreground" />
                              <span>Token 预算</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => setTemplateTarget(project)}>
                              <LayoutTemplate className="mr-2 h-4 w-4 text-muted-foreground" />
                              <span>需求模板</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => setQaProfileTarget(project)}>
                              <MonitorCheck className="mr-2 h-4 w-4 text-muted-foreground" />
                              <span>浏览器 QA</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => setAlertTarget(project)}>
                              <Bell className="mr-2 h-4 w-4 text-muted-foreground" />
                              <span>飞书告警</span>
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                              className="text-destructive focus:bg-destructive/10 focus:text-destructive"
                              onClick={() => setDeleteTarget(project)}
                            >
                              <Trash2 className="mr-2 h-4 w-4" />
                              <span>删除项目</span>
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
      <ProjectAlertDialog project={alertTarget} onOpenChange={(open) => !open && setAlertTarget(null)} />
      <ProjectTokenBudgetDialog project={tokenBudgetTarget} onOpenChange={(open) => !open && setTokenBudgetTarget(null)} />
      <ProjectTemplateDialog project={templateTarget} onOpenChange={(open) => !open && setTemplateTarget(null)} />
      <ProjectQaProfileDialog project={qaProfileTarget} onOpenChange={(open) => !open && setQaProfileTarget(null)} />
      <ProjectKnowledgeBindingDialog
        project={knowledgeTarget}
        knowledgeBases={knowledgeBases}
        loading={knowledgeBasesLoading}
        onOpenChange={(open) => !open && setKnowledgeTarget(null)}
        onSuccess={() => loadProjects(page, keyword, enabledParam)}
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
    </TooltipProvider>
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
      knowledgeBaseId: project?.knowledgeBaseId || "",
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

function ProjectKnowledgeBindingDialog({
  project,
  knowledgeBases,
  loading,
  onOpenChange,
  onSuccess
}: {
  project: RdProject | null;
  knowledgeBases: KnowledgeBase[];
  loading: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}) {
  const [knowledgeBaseId, setKnowledgeBaseId] = useState(UNBOUND_KNOWLEDGE_BASE_VALUE);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!project) return;
    setKnowledgeBaseId(project.knowledgeBaseId || UNBOUND_KNOWLEDGE_BASE_VALUE);
  }, [project]);

  const save = async () => {
    if (!project) return;
    setSaving(true);
    try {
      const normalizedKnowledgeBaseId = normalizeProjectKnowledgeBaseId(knowledgeBaseId, knowledgeBases);
      const payload: RdProjectPayload = {
        projectKey: project.projectKey,
        name: project.name,
        description: project.description,
        repositoryUrl: project.repositoryUrl,
        repoOwner: project.repoOwner,
        repoName: project.repoName,
        defaultBranch: project.defaultBranch,
        knowledgeBaseId: normalizedKnowledgeBaseId,
        enabled: project.enabled
      };
      await updateProject(project.projectId, payload);
      toast.success(normalizedKnowledgeBaseId ? "项目知识库已绑定" : "项目知识库已清除");
      onOpenChange(false);
      onSuccess();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存项目知识库失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={!!project} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>项目知识库</DialogTitle>
          <DialogDescription>{project?.name}</DialogDescription>
        </DialogHeader>
        <Select value={knowledgeBaseId} onValueChange={setKnowledgeBaseId} disabled={loading || saving}>
          <SelectTrigger>
            <SelectValue placeholder={loading ? "加载中..." : "选择知识库"} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={UNBOUND_KNOWLEDGE_BASE_VALUE}>不绑定知识库</SelectItem>
            {knowledgeBases.map((knowledgeBase) => (
              <SelectItem key={knowledgeBase.id} value={knowledgeBase.id} disabled={!knowledgeBase.enabled}>
                {knowledgeBase.name}{knowledgeBase.enabled ? "" : "（已停用）"}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>取消</Button>
          <Button onClick={() => void save()} disabled={saving || loading}>{saving ? "保存中..." : "保存"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ProjectTokenBudgetDialog({ project, onOpenChange }: { project: RdProject | null; onOpenChange: (open: boolean) => void }) {
  const [value, setValue] = useState("0");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const loadSequence = useRef(0);

  useEffect(() => {
    const sequence = ++loadSequence.current;
    if (!project) return () => { loadSequence.current++; };
    setLoading(true);
    getProjectTokenBudget(project.projectId).then((budget) => {
      if (sequence === loadSequence.current) setValue(String(budget.defaultTokenBudget ?? 0));
    }).catch((error) => {
      if (sequence === loadSequence.current) toast.error(getErrorMessage(error, "加载 Token 预算失败"));
    }).finally(() => {
      if (sequence === loadSequence.current) setLoading(false);
    });
    return () => { loadSequence.current++; };
  }, [project?.projectId]);

  const save = async () => {
    if (!project) return;
    const budget = Number(value);
    if (!Number.isSafeInteger(budget) || budget < 0) {
      toast.error("请输入非负整数额度");
      return;
    }
    setSaving(true);
    try {
      await updateProjectTokenBudget(project.projectId, budget);
      toast.success("Token 预算已保存");
      onOpenChange(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存 Token 预算失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={!!project} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[440px]">
        <DialogHeader><DialogTitle>项目 Token 预算</DialogTitle><DialogDescription>{project?.name}</DialogDescription></DialogHeader>
        <div>
          <label className="mb-2 block text-sm font-medium">默认额度</label>
          <Input type="number" min="0" step="1" inputMode="numeric" value={value} disabled={loading || saving}
            onChange={(event) => setValue(event.target.value)} />
          <p className="mt-2 text-xs text-muted-foreground">0 表示不限制；任务创建时可填写覆盖额度。</p>
        </div>
        <DialogFooter><Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>取消</Button><Button onClick={() => void save()} disabled={loading || saving}>{saving ? "保存中..." : "保存"}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const ALERT_EVENTS: Array<{ value: ProjectAlertEventType; label: string }> = [
  { value: "TASK_COMPLETED", label: "任务完成" },
  { value: "TASK_BLOCKED", label: "任务阻塞" },
  { value: "TASK_FAILED", label: "任务失败" },
  { value: "RETRY_EXHAUSTED", label: "失败过多" },
  { value: "BUDGET_EXCEEDED", label: "预算超限" },
  { value: "QA_FAILED", label: "QA 失败" }
];

function ProjectAlertDialog({ project, onOpenChange }: { project: RdProject | null; onOpenChange: (open: boolean) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [chatRecipients, setChatRecipients] = useState<string[]>([]);
  const [userRecipients, setUserRecipients] = useState<string[]>([]);
  const [events, setEvents] = useState<ProjectAlertEventType[]>([]);
  const [budget, setBudget] = useState("36");
  const [failureThreshold, setFailureThreshold] = useState("2");
  const [saving, setSaving] = useState(false);
  const loadSequence = useRef(0);

  useEffect(() => {
    const sequence = ++loadSequence.current;
    if (!project) return () => { loadSequence.current++; };
    getProjectAlertConfig(project.projectId).then((config) => {
      if (sequence !== loadSequence.current) return;
      setEnabled(config.enabled);
      setChatRecipients(config.recipients.filter((item) => item.type === "CHAT_ID").map((item) => item.value));
      setUserRecipients(config.recipients.filter((item) => item.type === "OPEN_ID").map((item) => item.value));
      setEvents(config.eventTypes || []);
      setBudget(String(config.budgetThresholdCny ?? 36));
      setFailureThreshold(String(config.failureThreshold ?? 2));
    }).catch((error) => {
      if (sequence === loadSequence.current) toast.error(getErrorMessage(error, "加载告警配置失败"));
    });
    return () => { loadSequence.current++; };
  }, [project?.projectId]);

  const save = async () => {
    if (!project) return;
    setSaving(true);
    try {
      const recipients = normalizeAlertRecipients(chatRecipients, userRecipients);
      await updateProjectAlertConfig(project.projectId, {
        enabled, recipients, eventTypes: events,
        budgetThresholdCny: Number(budget), failureThreshold: Number(failureThreshold)
      });
      toast.success("告警配置已保存");
      onOpenChange(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存告警配置失败"));
    } finally { setSaving(false); }
  };

  return (
    <TooltipProvider delayDuration={300}>
      <Dialog open={!!project} onOpenChange={onOpenChange}>
        <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col sm:max-w-[620px]">
          <DialogHeader><DialogTitle>飞书告警</DialogTitle><DialogDescription>{project?.name} 的任务通知路由</DialogDescription></DialogHeader>
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />启用项目告警</label>
            <div className="grid gap-4 sm:grid-cols-2">
              <RecipientListEditor
                label="群聊告警"
                values={chatRecipients}
                placeholder="oc_xxx"
                addLabel="添加群聊告警"
                removeLabel="删除群聊告警"
                onChange={setChatRecipients}
              />
              <RecipientListEditor
                label="个人用户告警"
                values={userRecipients}
                placeholder="ou_xxx"
                addLabel="添加个人用户告警"
                removeLabel="删除个人用户告警"
                onChange={setUserRecipients}
              />
            </div>
            <div><label className="mb-2 block text-sm font-medium">事件</label><div className="grid gap-2 sm:grid-cols-2">{ALERT_EVENTS.map((item) => <label key={item.value} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={events.includes(item.value)} onChange={(e) => setEvents(e.target.checked ? [...events, item.value] : events.filter((event) => event !== item.value))} />{item.label}</label>)}</div></div>
            <div className="grid gap-4 sm:grid-cols-2"><div><label className="mb-2 block text-sm font-medium">预算阈值（元）</label><Input type="number" min="0" step="0.1" value={budget} onChange={(e) => setBudget(e.target.value)} /></div><div><label className="mb-2 block text-sm font-medium">失败次数阈值</label><Input type="number" min="1" value={failureThreshold} onChange={(e) => setFailureThreshold(e.target.value)} /></div></div>
          </div>
          <DialogFooter className="shrink-0"><Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button><Button onClick={() => void save()} disabled={saving}>{saving ? "保存中" : "保存"}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </TooltipProvider>
  );
}

function RecipientListEditor({
  label,
  values,
  placeholder,
  addLabel,
  removeLabel,
  onChange
}: {
  label: string;
  values: string[];
  placeholder: string;
  addLabel: string;
  removeLabel: string;
  onChange: (values: string[]) => void;
}) {
  const updateValue = (index: number, nextValue: string) => {
    onChange(values.map((value, currentIndex) => currentIndex === index ? nextValue : value));
  };

  return (
    <section className="space-y-2 rounded-md border border-slate-200 p-3">
      <div className="flex items-center justify-between gap-3">
        <label className="text-sm font-medium">{label}</label>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button type="button" variant="outline" size="icon" className="h-8 w-8" aria-label={addLabel} onClick={() => onChange([...values, ""])}>
              <Plus className="h-4 w-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{addLabel}</TooltipContent>
        </Tooltip>
      </div>
      {values.map((value, index) => (
        <div className="flex items-center gap-2" key={`${placeholder}-${index}`}>
          <Input value={value} placeholder={placeholder} onChange={(event) => updateValue(index, event.target.value)} className="font-mono text-xs" />
          <Tooltip>
            <TooltipTrigger asChild>
              <Button type="button" variant="ghost" size="icon" className="h-8 w-8 shrink-0 text-slate-500 hover:text-destructive" aria-label={removeLabel} onClick={() => onChange(values.filter((_, currentIndex) => currentIndex !== index))}>
                <Trash2 className="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>{removeLabel}</TooltipContent>
          </Tooltip>
        </div>
      ))}
    </section>
  );
}

function ProjectQaProfileDialog({ project, onOpenChange }: { project: RdProject | null; onOpenChange: (open: boolean) => void }) {
  const [mode, setMode] = useState<ProjectQaMode>("AUTO");
  const [baseUrl, setBaseUrl] = useState("");
  const [startCommand, setStartCommand] = useState("");
  const [healthPath, setHealthPath] = useState("/");
  const [allowedHosts, setAllowedHosts] = useState("");
  const [buildCommands, setBuildCommands] = useState("");
  const [skipBuild, setSkipBuild] = useState(false);
  const [staticCommands, setStaticCommands] = useState("");
  const [skipStatic, setSkipStatic] = useState(false);
  const [regressionCommands, setRegressionCommands] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!project) return;
    setLoading(true);
    getProjectQaProfile(project.projectId).then((profile) => {
      setMode(profile.mode);
      setBaseUrl(profile.baseUrl || "");
      setStartCommand(profile.startCommand || "");
      setHealthPath(profile.healthPath || "/");
      setAllowedHosts((profile.allowedHosts || []).join("\n"));
      if (Array.isArray(profile.buildCommands)) {
        if (profile.buildCommands.length === 0) {
          setSkipBuild(true);
          setBuildCommands("");
        } else {
          setSkipBuild(false);
          setBuildCommands(profile.buildCommands.join("\n"));
        }
      } else {
        setSkipBuild(false);
        setBuildCommands("");
      }
      if (Array.isArray(profile.staticCommands)) {
        if (profile.staticCommands.length === 0) {
          setSkipStatic(true);
          setStaticCommands("");
        } else {
          setSkipStatic(false);
          setStaticCommands(profile.staticCommands.join("\n"));
        }
      } else {
        setSkipStatic(false);
        setStaticCommands("");
      }
      setRegressionCommands((profile.regressionCommands || []).join("\n"));
    }).catch(() => {
      setMode("AUTO");
      setBaseUrl("");
      setStartCommand("");
      setHealthPath("/");
      setAllowedHosts("");
      setBuildCommands("");
      setSkipBuild(false);
      setStaticCommands("");
      setSkipStatic(false);
      setRegressionCommands("");
    }).finally(() => setLoading(false));
  }, [project?.projectId]);

  const lines = (value: string) => value.split("\n").map((item) => item.trim()).filter(Boolean);
  const save = async () => {
    if (!project) return;
    setSaving(true);
    const buildLines = lines(buildCommands);
    const staticLines = lines(staticCommands);
    const payloadBuildCommands = skipBuild ? [] : (buildLines.length > 0 ? buildLines : null);
    const payloadStaticCommands = skipStatic ? [] : (staticLines.length > 0 ? staticLines : null);

    try {
      await updateProjectQaProfile(project.projectId, {
        mode,
        baseUrl: mode === "REQUIRED" ? baseUrl.trim() : "",
        startCommand: mode === "REQUIRED" ? startCommand.trim() : "",
        healthPath: mode === "REQUIRED" ? healthPath.trim() || "/" : "",
        allowedHosts: mode === "REQUIRED" ? lines(allowedHosts) : [],
        buildCommands: payloadBuildCommands,
        staticCommands: payloadStaticCommands,
        regressionCommands: mode === "DISABLED" ? [] : lines(regressionCommands)
      });
      toast.success("浏览器 QA 配置已保存");
      onOpenChange(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存浏览器 QA 配置失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={!!project} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col overflow-hidden sm:max-w-[640px]">
        <DialogHeader>
          <DialogTitle>浏览器 QA</DialogTitle>
          <DialogDescription>{project?.name} 的真实页面验证、宿主构建与回归命令</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
          <div>
            <label className="mb-2 block text-sm font-medium">验证模式</label>
            <Select value={mode} onValueChange={(value) => setMode(value as ProjectQaMode)} disabled={loading}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="AUTO">自动识别</SelectItem>
                <SelectItem value="REQUIRED">必须浏览器验证</SelectItem>
                <SelectItem value="DISABLED">不执行浏览器验证</SelectItem>
              </SelectContent>
            </Select>
          </div>
          {mode === "REQUIRED" ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="sm:col-span-2"><label className="mb-2 block text-sm font-medium">应用地址</label><Input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="http://127.0.0.1:4173" /></div>
              <div className="sm:col-span-2"><label className="mb-2 block text-sm font-medium">启动命令</label><Input value={startCommand} onChange={(event) => setStartCommand(event.target.value)} className="font-mono text-xs" placeholder="npm run preview -- --host 0.0.0.0" /></div>
              <div><label className="mb-2 block text-sm font-medium">健康检查路径</label><Input value={healthPath} onChange={(event) => setHealthPath(event.target.value)} className="font-mono text-xs" placeholder="/health" /></div>
              <div><label className="mb-2 block text-sm font-medium">允许访问的主机</label><Textarea value={allowedHosts} onChange={(event) => setAllowedHosts(event.target.value)} className="min-h-24 font-mono text-xs" placeholder={"127.0.0.1\nlocalhost"} /></div>
            </div>
          ) : null}

          {/* 宿主构建命令 */}
          <div className="space-y-2 rounded-md border border-slate-200 bg-slate-50/50 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <label className="text-sm font-medium text-slate-900">构建命令（多行）</label>
              <label className="flex items-center gap-1.5 text-xs text-slate-600 cursor-pointer">
                <Checkbox
                  checked={skipBuild}
                  onCheckedChange={(checked) => setSkipBuild(Boolean(checked))}
                  disabled={loading}
                />
                <span>跳过构建</span>
              </label>
            </div>
            <p className="text-xs text-slate-500">
              安装、编译、仓库测试。留空=自动探测。勾选「跳过构建」才保存为空列表。
            </p>
            {!skipBuild ? (
              <Textarea
                value={buildCommands}
                onChange={(event) => setBuildCommands(event.target.value)}
                className="min-h-20 font-mono text-xs bg-white"
                placeholder={"npm install\nnpm run build\nnpm test"}
                disabled={loading}
              />
            ) : (
              <div className="rounded border border-dashed border-slate-200 px-3 py-2 text-xs text-slate-500 bg-slate-100/60">
                已显式配置为跳过构建步骤
              </div>
            )}
          </div>

          {/* 宿主静态检查命令 */}
          <div className="space-y-2 rounded-md border border-slate-200 bg-slate-50/50 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <label className="text-sm font-medium text-slate-900">静态检查命令（多行）</label>
              <label className="flex items-center gap-1.5 text-xs text-slate-600 cursor-pointer">
                <Checkbox
                  checked={skipStatic}
                  onCheckedChange={(checked) => setSkipStatic(Boolean(checked))}
                  disabled={loading}
                />
                <span>跳过静态检查</span>
              </label>
            </div>
            <p className="text-xs text-slate-500">
              typecheck / lint。留空=自动探测。勾选「跳过静态检查」才保存为空列表。
            </p>
            {!skipStatic ? (
              <Textarea
                value={staticCommands}
                onChange={(event) => setStaticCommands(event.target.value)}
                className="min-h-20 font-mono text-xs bg-white"
                placeholder={"npm run typecheck\nnpm run lint"}
                disabled={loading}
              />
            ) : (
              <div className="rounded border border-dashed border-slate-200 px-3 py-2 text-xs text-slate-500 bg-slate-100/60">
                已显式配置为跳过静态检查步骤
              </div>
            )}
          </div>

          {mode !== "DISABLED" ? (
            <div>
              <label className="mb-2 block text-sm font-medium">回归命令</label>
              <p className="mb-2 text-xs text-slate-500">业务回归测试（由 QA Agent 在容器内运行）。</p>
              <Textarea value={regressionCommands} onChange={(event) => setRegressionCommands(event.target.value)} className="min-h-24 font-mono text-xs" placeholder={"npm test"} />
            </div>
          ) : null}
        </div>
        <DialogFooter className="shrink-0 border-t border-slate-200 pt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>取消</Button>
          <Button onClick={() => void save()} disabled={loading || saving}>{saving ? "保存中..." : "保存"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ProjectTemplateDialog({ project, onOpenChange }: { project: RdProject | null; onOpenChange: (open: boolean) => void }) {
  const [name, setName] = useState("");
  const [criteria, setCriteria] = useState("");
  const [body, setBody] = useState("");
  const [result, setResult] = useState("");
  const [saving, setSaving] = useState(false);
  const loadSequence = useRef(0);

  useEffect(() => {
    const sequence = ++loadSequence.current;
    if (!project) return () => { loadSequence.current++; };
    getProjectTaskTemplate(project.projectId, "REQUIREMENT").then((template) => {
      if (sequence !== loadSequence.current) return;
      setName(template.name || "");
      setCriteria((template.acceptanceCriteria || []).join("\n"));
      setBody(template.requirementBody || "");
      setResult(template.expectedResult || "");
    }).catch((error) => {
      if (sequence === loadSequence.current) toast.error(getErrorMessage(error, "加载任务模板失败"));
    });
    return () => { loadSequence.current++; };
  }, [project?.projectId]);

  const save = async () => {
    if (!project) return;
    setSaving(true);
    try {
      await updateProjectTaskTemplate(project.projectId, "REQUIREMENT", {
        name,
        actualBehavior: "",
        expectedBehavior: "",
        reproductionSteps: "",
        affectedScope: "",
        acceptanceCriteria: criteria.split("\n").map((line) => line.trim()).filter(Boolean),
        requirementBody: body,
        expectedResult: result
      });
      toast.success("需求模板已保存");
      onOpenChange(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存任务模板失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={!!project} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col overflow-hidden sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>需求模板</DialogTitle>
          <DialogDescription>{project?.name} 的默认需求表单内容</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
          <div>
            <label className="mb-2 block text-sm font-medium">模板名称</label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <Textarea value={body} onChange={(e) => setBody(e.target.value)} placeholder="需求正文" />
          <Textarea value={result} onChange={(e) => setResult(e.target.value)} placeholder="预期结果" />
          <Textarea value={criteria} onChange={(e) => setCriteria(e.target.value)} placeholder="验收标准，每行一条" />
        </div>
        <DialogFooter className="shrink-0 border-t border-slate-200 pt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={() => void save()} disabled={saving}>{saving ? "保存中" : "保存"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
