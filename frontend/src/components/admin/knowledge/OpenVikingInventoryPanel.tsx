import { useCallback, useEffect, useId, useState } from "react";
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
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  DEFAULT_INVENTORY_BACKFILL_LIMIT,
  SUM_MISMATCH_WARNING,
  backfillStatusLabel,
  buildDuplicateResolveRequest,
  displaySafeError,
  extractServerMessage,
  formatEpochMillis,
  inventoryCategoryRows,
  inventorySeverityClass,
  inventorySumMismatchWarning,
  isProjectionDisabledConflict,
  mapDuplicateResolveConflictMessage
} from "@/services/openVikingKnowledgePresentation";
import {
  backfillOpenVikingInventory,
  getOpenVikingInventory,
  getOpenVikingInventoryCandidates,
  getOpenVikingInventoryDrift,
  getOpenVikingInventoryDuplicates,
  resolveOpenVikingDuplicates,
  type OpenVikingBackfillReport,
  type OpenVikingDuplicateGroup,
  type OpenVikingInventory,
  type OpenVikingInventoryCandidate,
  type OpenVikingInventoryDrift
} from "@/services/openVikingKnowledgeService";

function notifyProjectionError(error: unknown, fallback: string) {
  if (isProjectionDisabledConflict(error)) {
    return;
  }
  toast.error(extractServerMessage(error) || fallback);
}

function recordsOf<T>(page: { records?: T[] } | T[] | null | undefined): T[] {
  if (!page) {
    return [];
  }
  if (Array.isArray(page)) {
    return page;
  }
  return page.records ?? [];
}

export function OpenVikingInventoryPanel({
  kbId,
  projectionClosed,
  onAfterMutation
}: {
  kbId: string;
  projectionClosed: boolean;
  onAfterMutation: () => Promise<unknown>;
}) {
  const limitInputId = useId();
  const [inventory, setInventory] = useState<OpenVikingInventory | null>(null);
  const [candidates, setCandidates] = useState<OpenVikingInventoryCandidate[]>([]);
  const [duplicates, setDuplicates] = useState<OpenVikingDuplicateGroup[]>([]);
  const [drift, setDrift] = useState<OpenVikingInventoryDrift[]>([]);
  const [loading, setLoading] = useState(false);
  const [batchLimit, setBatchLimit] = useState(String(DEFAULT_INVENTORY_BACKFILL_LIMIT));
  const [backfilling, setBackfilling] = useState(false);
  const [backfillReport, setBackfillReport] = useState<OpenVikingBackfillReport | null>(null);
  const [resolveTarget, setResolveTarget] = useState<OpenVikingDuplicateGroup | null>(null);
  const [resolving, setResolving] = useState(false);

  const loadInventory = useCallback(async () => {
    setLoading(true);
    try {
      const [nextInventory, nextCandidates, nextDuplicates, nextDrift] = await Promise.all([
        getOpenVikingInventory(kbId),
        getOpenVikingInventoryCandidates(kbId, { after: "", size: DEFAULT_INVENTORY_BACKFILL_LIMIT }),
        getOpenVikingInventoryDuplicates(kbId, { size: 50 }),
        getOpenVikingInventoryDrift(kbId, { size: 50 })
      ]);
      setInventory(nextInventory);
      setCandidates(recordsOf(nextCandidates));
      setDuplicates(recordsOf(nextDuplicates));
      setDrift(recordsOf(nextDrift));
      return nextInventory;
    } catch (error) {
      notifyProjectionError(error, "加载存量审计失败");
      return null;
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    void loadInventory();
  }, [loadInventory]);

  const parsedLimit = Number.parseInt(batchLimit, 10);
  const effectiveLimit =
    Number.isFinite(parsedLimit) && parsedLimit > 0 ? parsedLimit : DEFAULT_INVENTORY_BACKFILL_LIMIT;

  const handleBackfill = async () => {
    if (projectionClosed) {
      return;
    }
    setBackfilling(true);
    try {
      const report = await backfillOpenVikingInventory(kbId, { limit: effectiveLimit });
      setBackfillReport(report);
      await Promise.all([loadInventory(), onAfterMutation()]);
    } catch (error) {
      notifyProjectionError(error, "回填失败");
      await loadInventory();
    } finally {
      setBackfilling(false);
    }
  };

  const handleResolve = async () => {
    if (!resolveTarget || projectionClosed) {
      return;
    }
    setResolving(true);
    try {
      const body = buildDuplicateResolveRequest(resolveTarget);
      await resolveOpenVikingDuplicates(kbId, body);
      setResolveTarget(null);
      await Promise.all([loadInventory(), onAfterMutation()]);
    } catch (error) {
      if (error instanceof Error && /expectedRowVersion|行版本|来源身份|存活文档|可取代/.test(error.message)) {
        toast.error(error.message);
      } else {
        toast.error(mapDuplicateResolveConflictMessage(error));
      }
      await loadInventory();
    } finally {
      setResolving(false);
    }
  };

  const mismatchWarning = inventory ? inventorySumMismatchWarning(inventory.sumMatchesTotal) : "";
  const categoryRows = inventoryCategoryRows(inventory?.categories);
  const localOnlyCount = categoryRows.find((row) => row.category === "EXCLUDED_LOCAL_ONLY")?.count ?? 0;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <CardTitle>分类账本</CardTitle>
              <CardDescription>
                每一篇文档必须落入且只落入一个分类。手工本地覆盖单独成类，不会进入回填队列，也不会显示为已同步。
              </CardDescription>
            </div>
            {inventory ? (
              <span
                className={cn(
                  "inline-flex items-center rounded-full border px-3 py-1 text-xs font-semibold",
                  inventory.sumMatchesTotal
                    ? "border-green-200 bg-green-50 text-green-700"
                    : "border-red-300 bg-red-100 text-red-800"
                )}
              >
                {inventory.sumMatchesTotal ? "分类求和一致" : "分类求和失败"}
              </span>
            ) : null}
          </div>
        </CardHeader>
        <CardContent>
          {mismatchWarning ? (
            <div
              role="alert"
              className="mb-4 rounded-lg border-2 border-red-400 bg-red-50 px-4 py-3 text-sm font-medium text-red-900"
            >
              {SUM_MISMATCH_WARNING}
            </div>
          ) : null}
          {loading && !inventory ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : !inventory ? (
            <div className="py-8 text-center text-muted-foreground">暂无存量审计数据</div>
          ) : (
            <>
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>分类</TableHead>
                      <TableHead className="text-right">计数</TableHead>
                      <TableHead>严重度</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {categoryRows.map((row) => (
                      <TableRow key={row.category}>
                        <TableCell>
                          <div className="font-medium">{row.label}</div>
                          <div className="text-xs text-muted-foreground">{row.category}</div>
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{row.count}</TableCell>
                        <TableCell>
                          <span
                            className={cn(
                              "inline-flex rounded-full border px-2.5 py-0.5 text-xs font-semibold",
                              inventorySeverityClass(row.severity)
                            )}
                          >
                            {row.severity === "healthy"
                              ? "健康"
                              : row.severity === "blocked"
                                ? "阻塞"
                                : "需关注"}
                          </span>
                        </TableCell>
                      </TableRow>
                    ))}
                    <TableRow>
                      <TableCell className="font-semibold">文档总数</TableCell>
                      <TableCell className="text-right font-semibold tabular-nums">
                        {inventory.documentTotal}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">含墓碑与已被取代</TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </div>
              <div className="mt-4 grid gap-3 sm:grid-cols-3">
                <div className="rounded-lg border bg-slate-50 px-3 py-2 text-sm">
                  <div className="text-xs text-muted-foreground">待回填剩余</div>
                  <div className="text-lg font-semibold tabular-nums">{inventory.pendingBackfillRemaining}</div>
                </div>
                <div className="rounded-lg border bg-slate-50 px-3 py-2 text-sm">
                  <div className="text-xs text-muted-foreground">未收敛操作</div>
                  <div className="text-lg font-semibold tabular-nums">{inventory.inFlightOperations}</div>
                </div>
                <div className="rounded-lg border bg-slate-50 px-3 py-2 text-sm">
                  <div className="text-xs text-muted-foreground">手工本地覆盖</div>
                  <div className="text-lg font-semibold tabular-nums">{localOnlyCount}</div>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>回填一批</CardTitle>
          <CardDescription>
            只补身份、修订与绑定，不重切块。执行后重新拉取账本；页面上的计数才是成功依据。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-2">
              <Label htmlFor={limitInputId}>批量上限</Label>
              <Input
                id={limitInputId}
                type="number"
                min={1}
                max={200}
                inputMode="numeric"
                className="w-28"
                value={batchLimit}
                onChange={(event) => setBatchLimit(event.target.value)}
                disabled={backfilling || projectionClosed}
              />
            </div>
            <Button onClick={() => void handleBackfill()} disabled={backfilling || projectionClosed}>
              {backfilling ? "回填中..." : "回填一批"}
            </Button>
          </div>
          {backfillReport ? (
            <div className="space-y-3 rounded-lg border bg-slate-50 p-3">
              {backfillReport.stopReason ? (
                <div role="alert" className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
                  本批未入队：{backfillReport.stopReason}
                </div>
              ) : null}
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                <BackfillCount label="尝试" value={backfillReport.attempted} />
                <BackfillCount label={backfillStatusLabel("APPLIED")} value={backfillReport.applied} />
                <BackfillCount
                  label={backfillStatusLabel("SKIPPED_ALREADY_BOUND")}
                  value={backfillReport.skippedAlreadyBound}
                />
                <BackfillCount
                  label={backfillStatusLabel("SKIPPED_NOT_ELIGIBLE")}
                  value={backfillReport.skippedNotEligible}
                />
                <BackfillCount
                  label={backfillStatusLabel("SKIPPED_CONCURRENT_MODIFICATION")}
                  value={backfillReport.skippedConcurrentModification}
                />
                <BackfillCount label={backfillStatusLabel("FAILED")} value={backfillReport.failed} />
              </div>
              {backfillReport.outcomes.length > 0 ? (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>文档</TableHead>
                        <TableHead>结果</TableHead>
                        <TableHead>原因</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {backfillReport.outcomes.map((outcome) => (
                        <TableRow key={`${outcome.documentId}-${outcome.status}`}>
                          <TableCell className="font-mono text-xs">{outcome.documentId}</TableCell>
                          <TableCell>{backfillStatusLabel(outcome.status)}</TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {displaySafeError("", outcome.reason)}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              ) : null}
            </div>
          ) : null}
          {candidates.length > 0 ? (
            <div>
              <h3 className="mb-2 text-sm font-medium">下一页候选</h3>
              <ul className="space-y-1 text-sm">
                {candidates.map((candidate) => (
                  <li key={candidate.documentId} className="rounded-md border px-3 py-2">
                    <div className="font-medium">{candidate.sourceName || candidate.documentId}</div>
                    <div className="text-xs text-muted-foreground">{candidate.documentId}</div>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">当前没有待回填候选。</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>重复身份</CardTitle>
          <CardDescription>
            系统按最近同步时间提出存活文档。确认后会删除被取代文档的远端副本，本地行与修订保留。
          </CardDescription>
        </CardHeader>
        <CardContent>
          {duplicates.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无未解决的重复身份</div>
          ) : (
            <div className="space-y-3">
              {duplicates.map((group) => (
                <div key={group.identityKey} className="rounded-lg border px-4 py-3">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <div className="font-medium">身份 {group.identityKey}</div>
                      <div className="text-xs text-muted-foreground">
                        提出的存活文档 {group.proposedSurvivorDocumentId || "—"}
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={projectionClosed}
                      onClick={() => setResolveTarget(group)}
                    >
                      确认解决
                    </Button>
                  </div>
                  <ul className="mt-3 space-y-2">
                    {group.members.map((member) => {
                      const proposed = member.documentId === group.proposedSurvivorDocumentId;
                      return (
                        <li
                          key={member.documentId}
                          className={cn(
                            "rounded-md border px-3 py-2 text-sm",
                            proposed ? "border-green-300 bg-green-50" : "border-slate-200 bg-white"
                          )}
                        >
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <span className="font-mono text-xs">{member.documentId}</span>
                            {proposed ? (
                              <span className="text-xs font-semibold text-green-700">提出的存活文档</span>
                            ) : (
                              <span className="text-xs text-muted-foreground">将被取代</span>
                            )}
                          </div>
                          <div className="mt-1 text-xs text-muted-foreground">
                            同步 {formatEpochMillis(member.lastSyncedAtEpochMillis)} · 创建{" "}
                            {formatEpochMillis(member.createdAtEpochMillis)} · 行版本 {member.rowVersion}
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>本地孤儿漂移</CardTitle>
          <CardDescription>
            绑定期望仍为 PRESENT 但文档已不可见。漂移不计入分类求和，本页只读。
          </CardDescription>
        </CardHeader>
        <CardContent>
          {drift.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无本地孤儿漂移</div>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>文档</TableHead>
                    <TableHead>期望状态</TableHead>
                    <TableHead>URI</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {drift.map((entry) => (
                    <TableRow key={entry.documentId}>
                      <TableCell className="font-mono text-xs">{entry.documentId}</TableCell>
                      <TableCell>{entry.desiredState || "—"}</TableCell>
                      <TableCell className="max-w-[280px] truncate font-mono text-xs" title={entry.remoteUri}>
                        {entry.remoteUri || "—"}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <AlertDialog open={Boolean(resolveTarget)} onOpenChange={(open) => (!open ? setResolveTarget(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认解决该重复身份？</AlertDialogTitle>
            <AlertDialogDescription>
              被取代文档的远端副本将被删除。本地行与修订会保留。提交时会带回每个被取代文档当前读到的
              expectedRowVersion；版本不匹配则整组不提交。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={resolving}>取消</AlertDialogCancel>
            <AlertDialogAction
              disabled={resolving || projectionClosed}
              className="bg-destructive text-destructive-foreground"
              onClick={(event) => {
                event.preventDefault();
                void handleResolve();
              }}
            >
              {resolving ? "提交中..." : "确认解决"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function BackfillCount({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-md border bg-white px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums">{value}</div>
    </div>
  );
}
