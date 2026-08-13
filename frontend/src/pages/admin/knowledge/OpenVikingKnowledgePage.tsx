import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Hammer, RefreshCw, RotateCcw, ShieldCheck, Waypoints } from "lucide-react";
import { toast } from "sonner";

import { OpenVikingOperationTimeline } from "@/components/admin/knowledge/OpenVikingOperationTimeline";
import { OpenVikingStatusBadge } from "@/components/admin/knowledge/OpenVikingStatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  actionOutcomeLabel,
  displaySafeError,
  extractServerMessage,
  formatAgeMillis,
  formatEpochMillis,
  isProjectionDisabledConflict,
  mapRequeueConflictMessage,
  nextPollingState,
  projectionBadgeClass,
  projectionStatusLabel,
  shortenChecksum,
  SUMMARY_PROJECTION_STATUSES,
  treeEntryTone
} from "@/services/openVikingKnowledgePresentation";
import {
  getOpenVikingDeadLetters,
  getOpenVikingDocumentDetail,
  getOpenVikingDocuments,
  getOpenVikingHealth,
  getOpenVikingOverview,
  getOpenVikingTombstones,
  getOpenVikingTree,
  rebuildOpenVikingDocument,
  reconcileOpenViking,
  requeueOpenVikingDeadLetter,
  retryOpenVikingDocument,
  verifyOpenVikingDocument,
  type OpenVikingAction,
  type OpenVikingDocumentDetail,
  type OpenVikingDocumentRow,
  type OpenVikingFinding,
  type OpenVikingHealth,
  type OpenVikingOperation,
  type OpenVikingOverview,
  type OpenVikingPage,
  type OpenVikingTombstone,
  type OpenVikingTree
} from "@/services/openVikingKnowledgeService";
import { getKnowledgeBase, type KnowledgeBase } from "@/services/knowledgeService";

const PAGE_SIZE = 20;
const POLL_INTERVAL_MS = 5_000;

function totalPages(page: OpenVikingPage<unknown> | null): number {
  if (!page || page.size <= 0) {
    return 1;
  }
  return Math.max(1, Math.ceil(page.total / page.size));
}

function notifyProjectionError(error: unknown, fallback: string) {
  if (isProjectionDisabledConflict(error)) {
    return;
  }
  toast.error(extractServerMessage(error) || fallback);
}

function Pager({
  page,
  onPageChange
}: {
  page: OpenVikingPage<unknown> | null;
  onPageChange: (next: number) => void;
}) {
  if (!page) {
    return null;
  }
  const pages = totalPages(page);
  return (
    <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
      <span>共 {page.total} 条</span>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={() => onPageChange(Math.max(1, page.page - 1))} disabled={page.page <= 1}>
          上一页
        </Button>
        <span>
          {Math.max(1, page.page)} / {pages}
        </span>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(Math.min(pages, page.page + 1))}
          disabled={page.page >= pages}
        >
          下一页
        </Button>
      </div>
    </div>
  );
}

export function OpenVikingKnowledgePage() {
  const { kbId } = useParams();
  const navigate = useNavigate();
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [overview, setOverview] = useState<OpenVikingOverview | null>(null);
  const [health, setHealth] = useState<OpenVikingHealth | null>(null);
  const [documents, setDocuments] = useState<OpenVikingPage<OpenVikingDocumentRow> | null>(null);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [tab, setTab] = useState("documents");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<OpenVikingDocumentDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [acting, setActing] = useState(false);
  const [tree, setTree] = useState<OpenVikingTree | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [deadLetters, setDeadLetters] = useState<OpenVikingPage<OpenVikingOperation> | null>(null);
  const [deadLetterPage, setDeadLetterPage] = useState(1);
  const [deadLetterLoading, setDeadLetterLoading] = useState(false);
  const [tombstones, setTombstones] = useState<OpenVikingPage<OpenVikingTombstone> | null>(null);
  const [tombstonePage, setTombstonePage] = useState(1);
  const [tombstoneLoading, setTombstoneLoading] = useState(false);
  const [findings, setFindings] = useState<OpenVikingFinding[]>([]);
  const [lastReconcileAt, setLastReconcileAt] = useState<number>(0);
  const [reconciling, setReconciling] = useState(false);
  const selectedIdRef = useRef<string | null>(null);
  selectedIdRef.current = selectedId;

  const projectionClosed = overview?.ready === false || health?.ready === false;

  const loadKnowledgeBase = useCallback(async () => {
    if (!kbId) {
      return;
    }
    try {
      setKb(await getKnowledgeBase(kbId));
    } catch (error) {
      toast.error(extractServerMessage(error) || "加载知识库失败");
    }
  }, [kbId]);

  const loadLedger = useCallback(async (options: { silent?: boolean; selectedDocId?: string | null } = {}) => {
    if (!kbId) {
      return null;
    }
    if (!options.silent) {
      setLoading(true);
    }
    try {
      const [nextOverview, nextHealth, nextDocuments] = await Promise.all([
        getOpenVikingOverview(kbId),
        getOpenVikingHealth(kbId),
        getOpenVikingDocuments(kbId, { status: statusFilter, page, size: PAGE_SIZE })
      ]);
      setOverview(nextOverview);
      setHealth(nextHealth);
      setDocuments(nextDocuments);
      const selectedDocId = options.selectedDocId;
      if (selectedDocId) {
        setDetail(await getOpenVikingDocumentDetail(kbId, selectedDocId));
      }
      return {
        overview: nextOverview,
        documents: nextDocuments.records,
        outboxCounts: nextOverview.outboxCounts,
        bindingCounts: nextOverview.bindingCounts,
        unconvergedCount: nextOverview.unconvergedCount
      };
    } catch (error) {
      notifyProjectionError(error, "加载投影账本失败");
      return null;
    } finally {
      if (!options.silent) {
        setLoading(false);
      }
    }
  }, [kbId, page, statusFilter]);

  const loadTree = useCallback(async () => {
    if (!kbId) {
      return;
    }
    setTreeLoading(true);
    try {
      setTree(await getOpenVikingTree(kbId));
    } catch (error) {
      notifyProjectionError(error, "加载远端树失败");
    } finally {
      setTreeLoading(false);
    }
  }, [kbId]);

  const loadDeadLetters = useCallback(async (nextPage = deadLetterPage) => {
    if (!kbId) {
      return;
    }
    setDeadLetterLoading(true);
    try {
      setDeadLetters(await getOpenVikingDeadLetters(kbId, { page: nextPage, size: PAGE_SIZE }));
    } catch (error) {
      notifyProjectionError(error, "加载死信失败");
    } finally {
      setDeadLetterLoading(false);
    }
  }, [kbId, deadLetterPage]);

  const loadTombstones = useCallback(async (nextPage = tombstonePage) => {
    if (!kbId) {
      return;
    }
    setTombstoneLoading(true);
    try {
      setTombstones(await getOpenVikingTombstones(kbId, { page: nextPage, size: PAGE_SIZE }));
    } catch (error) {
      notifyProjectionError(error, "加载墓碑失败");
    } finally {
      setTombstoneLoading(false);
    }
  }, [kbId, tombstonePage]);

  useEffect(() => {
    void loadKnowledgeBase();
  }, [loadKnowledgeBase]);

  useEffect(() => {
    if (!kbId) {
      return;
    }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let consecutiveStableRounds = 0;

    const poll = async (silent: boolean) => {
      const snapshot = await loadLedger({ silent, selectedDocId: selectedIdRef.current });
      if (cancelled) {
        return;
      }
      if (!snapshot) {
        timer = setTimeout(() => {
          void poll(true);
        }, POLL_INTERVAL_MS);
        return;
      }
      const decision = nextPollingState(consecutiveStableRounds, snapshot);
      consecutiveStableRounds = decision.consecutiveStableRounds;
      if (decision.keepPolling) {
        timer = setTimeout(() => {
          void poll(true);
        }, POLL_INTERVAL_MS);
      }
    };

    void poll(false);
    return () => {
      cancelled = true;
      if (timer) {
        clearTimeout(timer);
      }
    };
  }, [kbId, loadLedger]);

  useEffect(() => {
    if (tab === "tree") {
      void loadTree();
    } else if (tab === "dead-letters") {
      void loadDeadLetters();
    } else if (tab === "tombstones") {
      void loadTombstones();
    }
  }, [tab, loadTree, loadDeadLetters, loadTombstones]);

  const openDocument = async (documentId: string) => {
    if (!kbId) {
      return;
    }
    setSelectedId(documentId);
    setDetailLoading(true);
    try {
      setDetail(await getOpenVikingDocumentDetail(kbId, documentId));
    } catch (error) {
      notifyProjectionError(error, "加载文档投影失败");
      setSelectedId(null);
    } finally {
      setDetailLoading(false);
    }
  };

  const runDocumentAction = async (action: (id: string) => Promise<OpenVikingAction>, fallback: string) => {
    if (!kbId || !selectedId) {
      return;
    }
    setActing(true);
    try {
      const result = await action(selectedId);
      toast.success(actionOutcomeLabel(result.outcome) || result.message || fallback);
      await loadLedger({ selectedDocId: selectedId });
    } catch (error) {
      notifyProjectionError(error, fallback);
      await loadLedger({ silent: true, selectedDocId: selectedId });
    } finally {
      setActing(false);
    }
  };

  const handleReconcile = async () => {
    if (!kbId || projectionClosed) {
      return;
    }
    setReconciling(true);
    try {
      const report = await reconcileOpenViking(kbId);
      setFindings(report.findings || []);
      setLastReconcileAt(Date.now());
      toast.success("对账完成");
      await loadLedger();
    } catch (error) {
      notifyProjectionError(error, "对账失败");
    } finally {
      setReconciling(false);
    }
  };

  const handleRequeue = async (operation: OpenVikingOperation) => {
    if (!kbId) {
      return;
    }
    try {
      const result = await requeueOpenVikingDeadLetter(kbId, operation.eventId, operation.rowVersion);
      toast.success(actionOutcomeLabel(result.outcome));
      await Promise.all([loadDeadLetters(), loadLedger({ silent: true })]);
    } catch (error) {
      toast.error(mapRequeueConflictMessage(error));
    }
  };

  const findingsByUri = new Map<string, string[]>();
  for (const finding of findings) {
    const uri = finding.remoteUri || "";
    const current = findingsByUri.get(uri) || [];
    current.push(finding.findingType);
    findingsByUri.set(uri, current);
  }

  const lastReconcileLabel = lastReconcileAt > 0
    ? formatEpochMillis(lastReconcileAt)
    : findings.length > 0
      ? formatEpochMillis(Math.max(...findings.map((finding) => finding.lastSeenAtEpochMillis || 0)))
      : "尚未对账";

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">OpenViking 投影</h1>
          <p className="admin-page-subtitle">{kb ? kb.name : kbId}</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => navigate(`/admin/knowledge/${kbId}`)}>
            返回文档
          </Button>
          <Button variant="outline" onClick={() => void loadLedger()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button onClick={() => void handleReconcile()} disabled={reconciling || projectionClosed}>
            <Waypoints className="mr-2 h-4 w-4" />
            {reconciling ? "对账中..." : "立即对账"}
          </Button>
        </div>
      </div>

      {projectionClosed ? (
        <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          投影已关闭。账本仍可查看，但核验与对账不可用。请在服务端开启投影后再操作。
        </div>
      ) : null}

      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Card>
          <CardHeader className="p-4 pb-2">
            <CardDescription>投影状态</CardDescription>
            <CardTitle className="text-lg">
              {overview == null && health == null ? "—" : projectionClosed ? "已关闭" : "已开启"}
            </CardTitle>
          </CardHeader>
        </Card>
        <Card>
          <CardHeader className="p-4 pb-2">
            <CardDescription>语义指纹</CardDescription>
            <CardTitle className="truncate text-lg" title={health?.semanticConfigFingerprint || ""}>
              {shortenChecksum(health?.semanticConfigFingerprint, 12)}
            </CardTitle>
          </CardHeader>
        </Card>
        <Card>
          <CardHeader className="p-4 pb-2">
            <CardDescription>最后对账</CardDescription>
            <CardTitle className="text-lg">{lastReconcileLabel}</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4 text-xs text-muted-foreground">
            未关闭发现 {health?.openFindingCount ?? 0}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="p-4 pb-2">
            <CardDescription>卡住计数</CardDescription>
            <CardTitle className="text-lg">{overview?.unconvergedCount ?? 0}</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4 text-xs text-muted-foreground">
            最老未收敛 {formatAgeMillis(overview?.oldestUnconvergedAgeMillis)}
          </CardContent>
        </Card>
      </div>

      <div className="mb-4 flex flex-wrap gap-2">
        {SUMMARY_PROJECTION_STATUSES.map((status) => {
          const count = overview?.bindingCounts?.[status] ?? 0;
          const active = statusFilter === status;
          return (
            <button
              key={status}
              type="button"
              onClick={() => {
                setPage(1);
                setStatusFilter(active ? undefined : status);
              }}
              className={cn(
                "inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                active ? projectionBadgeClass(status) : "border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
              )}
            >
              {projectionStatusLabel(status)}
              <span className="tabular-nums">{count}</span>
            </button>
          );
        })}
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="mb-4 grid h-11 w-full grid-cols-2 gap-2 sm:grid-cols-4">
          <TabsTrigger value="documents">文档映射</TabsTrigger>
          <TabsTrigger value="tree">远端树</TabsTrigger>
          <TabsTrigger value="dead-letters">死信</TabsTrigger>
          <TabsTrigger value="tombstones">墓碑</TabsTrigger>
        </TabsList>

        <TabsContent value="documents">
          <Card>
            <CardHeader>
              <CardTitle>文档映射</CardTitle>
              <CardDescription>点击行查看操作时间线与手工收敛动作</CardDescription>
            </CardHeader>
            <CardContent>
              {loading && !documents ? (
                <div className="py-8 text-center text-muted-foreground">加载中...</div>
              ) : !documents || documents.records.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground">暂无投影文档</div>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>文档</TableHead>
                        <TableHead>desired / observed</TableHead>
                        <TableHead>checksum</TableHead>
                        <TableHead>URI</TableHead>
                        <TableHead>task</TableHead>
                        <TableHead>attempt</TableHead>
                        <TableHead>最后验证</TableHead>
                        <TableHead>状态</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {documents.records.map((row) => (
                        <TableRow
                          key={row.documentId}
                          className="cursor-pointer"
                          onClick={() => void openDocument(row.documentId)}
                        >
                          <TableCell>
                            <div className="font-medium">{row.sourceName || row.documentId}</div>
                            <div className="text-xs text-muted-foreground">{row.documentId}</div>
                          </TableCell>
                          <TableCell className="tabular-nums">
                            {row.desiredVersion} / {row.observedVersion}
                          </TableCell>
                          <TableCell className="font-mono text-xs">
                            {shortenChecksum(row.desiredChecksum)} / {shortenChecksum(row.observedChecksum)}
                          </TableCell>
                          <TableCell className="max-w-[220px] truncate text-xs" title={row.remoteUri}>
                            {row.remoteUri || "—"}
                          </TableCell>
                          <TableCell className="max-w-[140px] truncate font-mono text-xs" title={row.remoteTaskId}>
                            {row.remoteTaskId || "—"}
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground">—</TableCell>
                          <TableCell className="text-xs">
                            {formatEpochMillis(row.lastVerifiedAtEpochMillis)}
                          </TableCell>
                          <TableCell>
                            <OpenVikingStatusBadge status={row.projectionStatus} />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
              <Pager page={documents} onPageChange={setPage} />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="tree">
          <Card>
            <CardHeader>
              <CardTitle>远端树</CardTitle>
              <CardDescription>只读浏览受管根。外部所有权与孤儿资源会醒目标出，不会被本页删除。</CardDescription>
            </CardHeader>
            <CardContent>
              {treeLoading ? (
                <div className="py-8 text-center text-muted-foreground">加载中...</div>
              ) : !tree || tree.entries.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground">
                  {tree?.errorMessage ? displaySafeError(tree.errorCode, tree.errorMessage) : "暂无远端条目"}
                </div>
              ) : (
                <div className="space-y-2">
                  {tree.errorMessage ? (
                    <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
                      {displaySafeError(tree.errorCode, tree.errorMessage)}
                    </div>
                  ) : null}
                  {tree.entries.map((entry) => {
                    const tone = treeEntryTone(entry, { findingTypes: findingsByUri.get(entry.uri) || [] });
                    return (
                      <div
                        key={entry.uri}
                        className={cn(
                          "rounded-lg border px-3 py-2 text-sm",
                          tone === "foreign" && "border-orange-300 bg-orange-50 text-orange-900",
                          tone === "orphan" && "border-amber-300 bg-amber-50 text-amber-900",
                          tone === "owned" && "border-slate-200 bg-white"
                        )}
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-medium">{entry.name || entry.uri}</span>
                          <span className="text-xs">
                            {tone === "foreign" ? "外部所有权" : tone === "orphan" ? "孤儿资源" : entry.directory ? "目录" : "文件"}
                          </span>
                        </div>
                        <div className="mt-1 truncate font-mono text-xs text-muted-foreground" title={entry.uri}>
                          {entry.uri}
                        </div>
                        <div className="text-xs text-muted-foreground">owner {entry.owner || "—"}</div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="dead-letters">
          <Card>
            <CardHeader>
              <CardTitle>死信</CardTitle>
              <CardDescription>重新入队会带上 expectedRowVersion；版本过期时请刷新后重试。</CardDescription>
            </CardHeader>
            <CardContent>
              {deadLetterLoading && !deadLetters ? (
                <div className="py-8 text-center text-muted-foreground">加载中...</div>
              ) : !deadLetters || deadLetters.records.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground">暂无死信</div>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>事件</TableHead>
                        <TableHead>类型</TableHead>
                        <TableHead>状态</TableHead>
                        <TableHead>尝试</TableHead>
                        <TableHead>错误</TableHead>
                        <TableHead>操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {deadLetters.records.map((operation) => (
                        <TableRow key={operation.eventId}>
                          <TableCell className="font-mono text-xs">{operation.eventId}</TableCell>
                          <TableCell>{operation.operationType}</TableCell>
                          <TableCell>
                            <OpenVikingStatusBadge status={operation.status} kind="operation" />
                          </TableCell>
                          <TableCell className="tabular-nums text-xs">
                            {operation.attemptCount}/{operation.maxAttempts}
                          </TableCell>
                          <TableCell className="max-w-[240px] truncate text-xs" title={operation.lastErrorMessage}>
                            {displaySafeError(operation.lastErrorCode, operation.lastErrorMessage)}
                          </TableCell>
                          <TableCell>
                            <Button size="sm" variant="outline" onClick={() => void handleRequeue(operation)}>
                              重新入队
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
              <Pager
                page={deadLetters}
                onPageChange={(next) => {
                  setDeadLetterPage(next);
                  void loadDeadLetters(next);
                }}
              />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="tombstones">
          <Card>
            <CardHeader>
              <CardTitle>墓碑</CardTitle>
              <CardDescription>已删除文档只读可见，本页不会复活远端资源。</CardDescription>
            </CardHeader>
            <CardContent>
              {tombstoneLoading && !tombstones ? (
                <div className="py-8 text-center text-muted-foreground">加载中...</div>
              ) : !tombstones || tombstones.records.length === 0 ? (
                <div className="py-8 text-center text-muted-foreground">暂无墓碑</div>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>文档</TableHead>
                        <TableHead>删除时间</TableHead>
                        <TableHead>状态</TableHead>
                        <TableHead>URI</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {tombstones.records.map((row) => (
                        <TableRow key={row.documentId}>
                          <TableCell>
                            <div className="font-medium">{row.sourceName || row.documentId}</div>
                            <div className="text-xs text-muted-foreground">{row.documentId}</div>
                          </TableCell>
                          <TableCell className="text-xs">{formatEpochMillis(row.deletedAtEpochMillis)}</TableCell>
                          <TableCell>
                            <OpenVikingStatusBadge status={row.projectionStatus} />
                          </TableCell>
                          <TableCell className="max-w-[280px] truncate font-mono text-xs" title={row.remoteUri}>
                            {row.remoteUri || "—"}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
              <Pager
                page={tombstones}
                onPageChange={(next) => {
                  setTombstonePage(next);
                  void loadTombstones(next);
                }}
              />
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog open={Boolean(selectedId)} onOpenChange={(open) => (!open ? setSelectedId(null) : null)}>
        <DialogContent
          className="flex h-[100dvh] max-h-[100dvh] w-full max-w-xl left-auto right-0 top-0 translate-x-0 translate-y-0 flex-col rounded-none sm:max-w-xl"
          onOpenAutoFocus={(event) => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>
              {documents?.records.find((row) => row.documentId === selectedId)?.sourceName
                || detail?.binding.documentId
                || selectedId}
            </DialogTitle>
            <DialogDescription>操作后会重新拉取服务端账本，不以 Toast 作为成功依据。</DialogDescription>
          </DialogHeader>
          {detailLoading && !detail ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : detail ? (
            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1">
              <div className="flex flex-wrap items-center gap-2">
                <OpenVikingStatusBadge status={detail.binding.projectionStatus} />
                <span className="text-xs text-muted-foreground">
                  desired {detail.binding.desiredVersion} / observed {detail.binding.observedVersion}
                </span>
              </div>
              <div className="rounded-lg border bg-slate-50 px-3 py-2 text-xs text-slate-600">
                {displaySafeError(detail.lastErrorCode, detail.lastErrorMessage)}
              </div>
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={acting}
                  onClick={() => void runDocumentAction((docId) => retryOpenVikingDocument(kbId || "", docId), "重试失败")}
                >
                  <RotateCcw className="mr-1 h-4 w-4" />
                  重试
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={acting || projectionClosed}
                  onClick={() => void runDocumentAction((docId) => verifyOpenVikingDocument(kbId || "", docId), "核验失败")}
                >
                  <ShieldCheck className="mr-1 h-4 w-4" />
                  核验
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={acting}
                  onClick={() => void runDocumentAction((docId) => rebuildOpenVikingDocument(kbId || "", docId), "重建失败")}
                >
                  <Hammer className="mr-1 h-4 w-4" />
                  重建
                </Button>
              </div>
              <OpenVikingOperationTimeline operations={detail.operations || []} />
            </div>
          ) : null}
          <DialogFooter className="shrink-0 border-t pt-4">
            <Button variant="outline" onClick={() => setSelectedId(null)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
