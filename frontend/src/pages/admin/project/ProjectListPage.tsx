import { useEffect, useMemo, useRef, useState } from "react";
import { Bell, Database, FolderOpen, LayoutTemplate, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
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
  getProjectTaskTemplate,
  updateProjectAlertConfig,
  updateProjectTaskTemplate,
  updateProject,
  type RdProject,
  type ProjectAlertEventType,
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
  const [templateTarget, setTemplateTarget] = useState<RdProject | null>(null);
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
                  <TableHead className="w-[270px] text-left">操作</TableHead>
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
                    <TableCell className="truncate">
                      {project.knowledgeBaseId ? (
                        <span title={knowledgeBaseById.get(project.knowledgeBaseId)?.name || project.knowledgeBaseId}>
                          {knowledgeBaseById.get(project.knowledgeBaseId)?.name || project.knowledgeBaseId}
                        </span>
                      ) : "-"}
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
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button size="icon" variant="outline" aria-label="绑定项目知识库" onClick={() => setKnowledgeTarget(project)}>
                              <Database className="h-4 w-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>绑定项目知识库</TooltipContent>
                        </Tooltip>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button size="icon" variant="outline" aria-label="任务模板" onClick={() => setTemplateTarget(project)}>
                              <LayoutTemplate className="h-4 w-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>任务模板</TooltipContent>
                        </Tooltip>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button size="icon" variant="outline" aria-label="飞书告警" onClick={() => setAlertTarget(project)}>
                              <Bell className="h-4 w-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>飞书告警</TooltipContent>
                        </Tooltip>
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
      <ProjectTemplateDialog project={templateTarget} onOpenChange={(open) => !open && setTemplateTarget(null)} />
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

function ProjectTemplateDialog({ project, onOpenChange }: { project: RdProject | null; onOpenChange: (open: boolean) => void }) {
  const [taskType, setTaskType] = useState<"BUG_FIX" | "REQUIREMENT">("BUG_FIX");
  const [name, setName] = useState("");
  const [actual, setActual] = useState("");
  const [expected, setExpected] = useState("");
  const [steps, setSteps] = useState("");
  const [scope, setScope] = useState("");
  const [criteria, setCriteria] = useState("");
  const [body, setBody] = useState("");
  const [result, setResult] = useState("");
  const [saving, setSaving] = useState(false);
  const loadSequence = useRef(0);

  useEffect(() => {
    const sequence = ++loadSequence.current;
    if (!project) return () => { loadSequence.current++; };
    getProjectTaskTemplate(project.projectId, taskType).then((template) => {
      if (sequence !== loadSequence.current) return;
      setName(template.name || ""); setActual(template.actualBehavior || ""); setExpected(template.expectedBehavior || "");
      setSteps(template.reproductionSteps || ""); setScope(template.affectedScope || "");
      setCriteria((template.acceptanceCriteria || []).join("\n")); setBody(template.requirementBody || ""); setResult(template.expectedResult || "");
    }).catch((error) => {
      if (sequence === loadSequence.current) toast.error(getErrorMessage(error, "加载任务模板失败"));
    });
    return () => { loadSequence.current++; };
  }, [project?.projectId, taskType]);

  const save = async () => {
    if (!project) return;
    setSaving(true);
    try {
      await updateProjectTaskTemplate(project.projectId, taskType, {
        name, actualBehavior: actual, expectedBehavior: expected, reproductionSteps: steps,
        affectedScope: scope, acceptanceCriteria: criteria.split("\n").map((line) => line.trim()).filter(Boolean),
        requirementBody: body, expectedResult: result
      });
      toast.success("任务模板已保存"); onOpenChange(false);
    } catch (error) { toast.error(getErrorMessage(error, "保存任务模板失败")); }
    finally { setSaving(false); }
  };

  return (
    <Dialog open={!!project} onOpenChange={onOpenChange}><DialogContent className="flex max-h-[calc(100vh-2rem)] flex-col overflow-hidden sm:max-w-[720px]">
      <DialogHeader><DialogTitle>任务模板</DialogTitle><DialogDescription>{project?.name} 的默认表单内容</DialogDescription></DialogHeader>
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
        <Select value={taskType} onValueChange={(value) => setTaskType(value as "BUG_FIX" | "REQUIREMENT")}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="BUG_FIX">修 Bug</SelectItem><SelectItem value="REQUIREMENT">做需求</SelectItem></SelectContent></Select>
        <div><label className="mb-2 block text-sm font-medium">模板名称</label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
        {taskType === "BUG_FIX" ? <><div className="grid gap-4 sm:grid-cols-2"><Textarea value={actual} onChange={(e) => setActual(e.target.value)} placeholder="实际现象" /><Textarea value={expected} onChange={(e) => setExpected(e.target.value)} placeholder="期望表现" /></div><Textarea value={steps} onChange={(e) => setSteps(e.target.value)} placeholder="复现步骤" /><Textarea value={scope} onChange={(e) => setScope(e.target.value)} placeholder="影响范围" /></> : <><Textarea value={body} onChange={(e) => setBody(e.target.value)} placeholder="需求正文" /><Textarea value={result} onChange={(e) => setResult(e.target.value)} placeholder="预期结果" /></>}
        <Textarea value={criteria} onChange={(e) => setCriteria(e.target.value)} placeholder="验收标准，每行一条" />
      </div>
      <DialogFooter className="shrink-0 border-t border-slate-200 pt-4"><Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button><Button onClick={() => void save()} disabled={saving}>{saving ? "保存中" : "保存"}</Button></DialogFooter>
    </DialogContent></Dialog>
  );
}
