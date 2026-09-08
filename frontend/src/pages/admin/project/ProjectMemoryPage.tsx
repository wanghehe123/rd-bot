import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Brain, RefreshCw, ShieldAlert, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { ProjectScopeSelector } from "@/components/ProjectScopeSelector";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAsyncData } from "@/hooks";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import { getProject, getProjectsPage, type RdProject, type RdProjectPage } from "@/services/projectService";
import {
  canPerformGovernanceActions,
  formatContentHash,
  formatSourceReference,
  governancePanelHint,
  mapGovernanceConflictMessage,
  memoryTypeLabel,
  revisionStatusBadgeClass,
  revisionStatusLabel,
  sortRevisionsForDisplay,
  type ProjectMemoryDetail,
  type ProjectMemoryListPage,
  type ProjectMemorySummary
} from "@/services/projectMemoryModel";
import {
  confirmProjectMemory,
  createGovernanceRequestId,
  getProjectMemoryDetail,
  invalidateProjectMemory,
  listProjectMemories,
  softDeleteProjectMemory
} from "@/services/projectMemoryService";
import { formatEpochMillis } from "@/services/openVikingKnowledgePresentation";

const EMPTY_PROJECT_PAGE: RdProjectPage = {
  records: [],
  total: 0,
  page: 1,
  pageSize: 100,
  pages: 0
};

const PAGE_SIZE = 20;

function totalPages(page: ProjectMemoryListPage | null): number {
  if (!page || page.size <= 0) {
    return 1;
  }
  return Math.max(1, Math.ceil(page.total / page.size));
}

export function ProjectMemoryPage() {
  const { projectId: routeProjectId = "" } = useParams();
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProjectMemoryDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [acting, setActing] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const projectsState = useAsyncData(
    () => getProjectsPage({ page: 1, pageSize: 100 }),
    [],
    EMPTY_PROJECT_PAGE
  );
  const projects = projectsState.data.records;
  const projectId = useMemo(() => {
    const enabledIds = new Set(projects.filter((project) => project.enabled).map((project) => project.projectId));
    if (routeProjectId && enabledIds.has(routeProjectId)) {
      return routeProjectId;
    }
    return projects.find((project) => project.enabled)?.projectId || routeProjectId;
  }, [projects, routeProjectId]);

  const projectState = useAsyncData<RdProject | null>(
    () => (projectId ? getProject(projectId) : Promise.resolve(null)),
    [projectId],
    null
  );

  const listState = useAsyncData<ProjectMemoryListPage | null>(
    () => (projectId ? listProjectMemories(projectId, { page, size: PAGE_SIZE }) : Promise.resolve(null)),
    [projectId, page],
    null
  );

  const availability = useMemo(() => ({
    mutationsEnabled: listState.data?.mutationsEnabled ?? false,
    projectReadOnly: listState.data?.projectReadOnly ?? !projectState.data?.enabled
  }), [listState.data, projectState.data?.enabled]);

  const governanceAllowed = canPerformGovernanceActions(availability);
  const governanceHint = governancePanelHint(availability);

  const loadDetail = useCallback(async (memoryId: string) => {
    if (!projectId) {
      return;
    }
    setDetailLoading(true);
    try {
      setDetail(await getProjectMemoryDetail(projectId, memoryId));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载记忆详情失败"));
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    if (selectedId) {
      void loadDetail(selectedId);
    } else {
      setDetail(null);
    }
  }, [loadDetail, selectedId]);

  useEffect(() => {
    if (projectId && projectId !== routeProjectId) {
      navigate(`/admin/projects/${projectId}/memories`, { replace: true });
    }
  }, [navigate, projectId, routeProjectId]);

  const refresh = useCallback(async () => {
    await listState.refresh();
    if (selectedId) {
      await loadDetail(selectedId);
    }
  }, [listState, loadDetail, selectedId]);

  const handleProjectChange = useCallback((nextProjectId: string) => {
    setPage(1);
    setSelectedId(null);
    navigate(`/admin/projects/${nextProjectId}/memories`);
  }, [navigate]);

  const handleGovernanceError = useCallback((error: unknown) => {
    toast.error(mapGovernanceConflictMessage(error));
  }, []);

  const runConfirm = useCallback(async () => {
    if (!projectId || !detail) {
      return;
    }
    const candidate = detail.revisions.find((revision) => revision.status === "CANDIDATE");
    if (!candidate) {
      toast.error("当前记忆没有可确认的候选 revision");
      return;
    }
    setActing(true);
    try {
      await confirmProjectMemory(projectId, detail.memoryId, {
        revisionId: candidate.revisionId,
        expectedMemoryRowVersion: detail.memoryRowVersion,
        expectedRevisionRowVersion: candidate.rowVersion,
        requestId: createGovernanceRequestId("confirm")
      });
      toast.success("候选记忆已确认");
      setConfirmOpen(false);
      await refresh();
    } catch (error) {
      handleGovernanceError(error);
    } finally {
      setActing(false);
    }
  }, [detail, handleGovernanceError, projectId, refresh]);

  const runInvalidateHead = useCallback(async () => {
    if (!projectId || !detail) {
      return;
    }
    const head = detail.revisions.find((revision) => revision.head);
    if (!head) {
      toast.error("当前记忆没有可失效的 head revision");
      return;
    }
    setActing(true);
    try {
      await invalidateProjectMemory(projectId, detail.memoryId, {
        revisionId: head.revisionId,
        expectedMemoryRowVersion: detail.memoryRowVersion,
        expectedRevisionRowVersion: head.rowVersion,
        requestId: createGovernanceRequestId("invalidate")
      });
      toast.success("当前 head 已失效");
      await refresh();
    } catch (error) {
      handleGovernanceError(error);
    } finally {
      setActing(false);
    }
  }, [detail, handleGovernanceError, projectId, refresh]);

  const runSoftDelete = useCallback(async () => {
    if (!projectId || !detail) {
      return;
    }
    setActing(true);
    try {
      await softDeleteProjectMemory(projectId, detail.memoryId, {
        expectedMemoryRowVersion: detail.memoryRowVersion,
        requestId: createGovernanceRequestId("delete")
      });
      toast.success("记忆已软删除");
      setDeleteOpen(false);
      setSelectedId(null);
      await refresh();
    } catch (error) {
      handleGovernanceError(error);
    } finally {
      setActing(false);
    }
  }, [detail, handleGovernanceError, projectId, refresh]);

  const sortedRevisions = useMemo(
    () => sortRevisionsForDisplay(detail?.revisions ?? []),
    [detail?.revisions]
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-2">
          <Button variant="ghost" size="sm" className="-ml-2 h-8 gap-1 text-slate-600" asChild>
            <Link to="/admin/projects">
              <ArrowLeft className="h-4 w-4" />
              返回项目列表
            </Link>
          </Button>
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-slate-900">项目记忆治理</h1>
            <p className="mt-1 text-sm text-slate-500">
              查看 head、revision、来源与检索审计，并对候选记忆执行确认、失效或软删除。
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <ProjectScopeSelector
            projects={projects}
            projectId={projectId}
            onProjectChange={handleProjectChange}
            loading={projectsState.loading}
            unavailable={Boolean(projectsState.error)}
          />
          <Button variant="outline" size="sm" onClick={() => void refresh()} disabled={listState.loading}>
            <RefreshCw className={cn("mr-2 h-4 w-4", listState.loading && "animate-spin")} />
            刷新
          </Button>
        </div>
      </div>

      {governanceHint ? (
        <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{governanceHint}</span>
        </div>
      ) : null}

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg">
              <Brain className="h-5 w-5 text-primary" />
              项目记忆
              <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">
                Experimental · 首发不支持
              </Badge>
            </CardTitle>
            <CardDescription>
              {projectState.data?.name || projectId || "未选择项目"}
              {listState.data ? ` · 共 ${listState.data.total} 条` : ""}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {listState.error ? (
              <p className="text-sm text-red-600">{getErrorMessage(listState.error, "加载记忆列表失败")}</p>
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>逻辑键</TableHead>
                      <TableHead>类型</TableHead>
                      <TableHead>角色</TableHead>
                      <TableHead>Head 状态</TableHead>
                      <TableHead>版本</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(listState.data?.records ?? []).map((memory: ProjectMemorySummary) => (
                      <TableRow
                        key={memory.memoryId}
                        className={cn(
                          "cursor-pointer",
                          selectedId === memory.memoryId && "bg-slate-50"
                        )}
                        onClick={() => setSelectedId(memory.memoryId)}
                      >
                        <TableCell className="font-medium">{memory.logicalKey}</TableCell>
                        <TableCell>{memoryTypeLabel(memory.memoryType)}</TableCell>
                        <TableCell>{memory.scopeRole}</TableCell>
                        <TableCell>
                          <Badge variant="outline" className={revisionStatusBadgeClass(memory.headStatus)}>
                            {revisionStatusLabel(memory.headStatus)}
                          </Badge>
                        </TableCell>
                        <TableCell>v{memory.headVersion}</TableCell>
                      </TableRow>
                    ))}
                    {!listState.loading && (listState.data?.records.length ?? 0) === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5} className="py-8 text-center text-sm text-slate-500">
                          当前项目暂无记忆记录
                        </TableCell>
                      </TableRow>
                    ) : null}
                  </TableBody>
                </Table>
                {listState.data ? (
                  <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
                    <span>第 {listState.data.page} / {totalPages(listState.data)} 页</span>
                    <div className="flex items-center gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={listState.data.page <= 1}
                        onClick={() => setPage((current) => Math.max(1, current - 1))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={listState.data.page >= totalPages(listState.data)}
                        onClick={() => setPage((current) => current + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                ) : null}
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>记忆详情</CardTitle>
            <CardDescription>
              {selectedId ? `memoryId=${selectedId}` : "选择一条记忆查看 revision、来源与检索审计"}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {!selectedId ? (
              <p className="text-sm text-slate-500">请从左侧列表选择一条记忆。</p>
            ) : detailLoading ? (
              <p className="text-sm text-slate-500">正在加载详情...</p>
            ) : !detail ? (
              <p className="text-sm text-slate-500">暂无详情。</p>
            ) : (
              <div className="space-y-4">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline" className={revisionStatusBadgeClass(detail.revisions.find((revision) => revision.head)?.status)}>
                    {revisionStatusLabel(detail.revisions.find((revision) => revision.head)?.status)}
                  </Badge>
                  <span className="text-sm text-slate-500">
                    rowVersion={detail.memoryRowVersion} · head v{detail.headVersion}
                  </span>
                </div>

                {governanceAllowed ? (
                  <div className="flex flex-wrap gap-2">
                    <Button size="sm" onClick={() => setConfirmOpen(true)} disabled={acting}>
                      确认候选
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => void runInvalidateHead()} disabled={acting}>
                      失效 head
                    </Button>
                    <Button size="sm" variant="destructive" onClick={() => setDeleteOpen(true)} disabled={acting}>
                      <Trash2 className="mr-2 h-4 w-4" />
                      软删除
                    </Button>
                  </div>
                ) : null}

                <Tabs defaultValue="revisions">
                  <TabsList>
                    <TabsTrigger value="revisions">Revisions</TabsTrigger>
                    <TabsTrigger value="sources">Sources</TabsTrigger>
                    <TabsTrigger value="audits">检索审计</TabsTrigger>
                  </TabsList>
                  <TabsContent value="revisions" className="space-y-3">
                    {sortedRevisions.map((revision) => (
                      <div key={revision.revisionId} className="rounded-lg border border-slate-200 p-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">v{revision.version}</span>
                          <Badge variant="outline" className={revisionStatusBadgeClass(revision.status)}>
                            {revisionStatusLabel(revision.status)}
                          </Badge>
                          {revision.head ? <Badge variant="outline">HEAD</Badge> : null}
                        </div>
                        <p className="mt-2 text-sm font-medium text-slate-900">{revision.title || "—"}</p>
                        <p className="mt-1 text-sm text-slate-600">{revision.summary || "—"}</p>
                        <p className="mt-2 text-xs text-slate-500">
                          hash {formatContentHash(revision.contentHash)} · rowVersion {revision.rowVersion}
                        </p>
                      </div>
                    ))}
                  </TabsContent>
                  <TabsContent value="sources" className="space-y-3">
                    {detail.sources.map((source) => (
                      <div key={source.sourceId} className="rounded-lg border border-slate-200 p-3">
                        <p className="text-sm text-slate-900">{formatSourceReference(source)}</p>
                        <p className="mt-2 text-xs text-slate-500">
                          {source.sourceUri} · repo {source.repositoryRevision} · extractor {source.extractorVersion}
                        </p>
                      </div>
                    ))}
                    {detail.sources.length === 0 ? (
                      <p className="text-sm text-slate-500">暂无来源快照。</p>
                    ) : null}
                  </TabsContent>
                  <TabsContent value="audits" className="space-y-3">
                    {detail.retrievalAudits.map((audit, index) => (
                      <div key={`${audit.revisionId}-${audit.observedAtEpochMillis}-${index}`} className="rounded-lg border border-slate-200 p-3">
                        <p className="text-sm text-slate-900">{audit.querySummary || "—"}</p>
                        <p className="mt-2 text-xs text-slate-500">
                          revision v{audit.revisionVersion} · examined {audit.examinedRowCount} rows · {formatEpochMillis(audit.observedAtEpochMillis)}
                        </p>
                      </div>
                    ))}
                    {detail.retrievalAudits.length === 0 ? (
                      <p className="text-sm text-slate-500">暂无检索审计。</p>
                    ) : null}
                  </TabsContent>
                </Tabs>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认候选记忆</DialogTitle>
            <DialogDescription>
              将把当前 CANDIDATE revision 激活为可检索的 ACTIVE head。若版本已被他人修改，将返回冲突提示。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmOpen(false)} disabled={acting}>取消</Button>
            <Button onClick={() => void runConfirm()} disabled={acting}>确认激活</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>软删除记忆</DialogTitle>
            <DialogDescription>
              软删除后记忆不再参与检索，但来源快照与审计记录会保留。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteOpen(false)} disabled={acting}>取消</Button>
            <Button variant="destructive" onClick={() => void runSoftDelete()} disabled={acting}>确认软删除</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
