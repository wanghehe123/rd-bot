import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Activity, Ban, Code2, FlaskConical, PauseCircle, Play, PlayCircle, RefreshCw } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import {
  cancelCodingBenchmarkCampaign,
  createCodingBenchmarkFormal,
  createCodingBenchmarkProbe,
  getCodingBenchmarkCampaigns,
  getCodingBenchmarkSnapshots,
  getCodingBenchmarkTrials,
  getEvaluationRun,
  isEvaluationRunActive,
  pauseCodingBenchmarkCampaign,
  resumeCodingBenchmarkCampaign,
  type CodingBenchmarkSnapshot,
  type CodingBenchmarkTrial,
  type EvaluationRun
} from "@/services/evaluationService";
import { getErrorMessage } from "@/utils/error";

function statusBadgeClass(status: string): string {
  if (status === "SUCCEEDED") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (status === "FAILED" || status === "CANCELLED") return "border-rose-200 bg-rose-50 text-rose-700";
  if (status === "RUNNING_TRIALS" || status === "RECORDING" || status === "PREPARING") return "border-sky-200 bg-sky-50 text-sky-700";
  if (status === "QUEUED" || status === "CREATED" || status === "CANCEL_REQUESTED") return "border-amber-200 bg-amber-50 text-amber-700";
  if (status === "SCORING" || status === "REPORTING" || status === "DIFFING") return "border-indigo-200 bg-indigo-50 text-indigo-700";
  return "border-slate-200 bg-slate-50 text-slate-600";
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    CREATED: "已创建",
    QUEUED: "排队中",
    PREPARING: "准备中",
    RECORDING: "录制中",
    RUNNING_TRIALS: "执行中",
    RUNNING_AGENTS: "Agent 执行中",
    RUNNING_ORACLE: "Oracle 评分中",
    RETRY_PENDING: "待重试",
    SCORING: "评分中",
    REPORTING: "生成报告",
    DIFFING: "对比中",
    CANCEL_REQUESTED: "取消中",
    SUCCEEDED: "已通过",
    FAILED: "已失败",
    CANCELLED: "已取消"
  };
  return labels[status] ?? status;
}

function verdictBadgeClass(verdict: string): string {
  if (verdict === "PASS") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (verdict === "TEST_FAIL" || verdict === "NO_PATCH" || verdict === "PROTOCOL_ERROR") return "border-rose-200 bg-rose-50 text-rose-700";
  if (verdict === "INFRA_ERROR") return "border-amber-200 bg-amber-50 text-amber-700";
  return "border-slate-200 bg-slate-50 text-slate-600";
}

function verdictLabel(verdict: string): string {
  const labels: Record<string, string> = {
    PASS: "通过",
    TEST_FAIL: "测试失败",
    NO_PATCH: "无补丁",
    PROTOCOL_ERROR: "协议错误",
    INFRA_ERROR: "基建错误",
    PENDING: "待定"
  };
  return labels[verdict] ?? verdict;
}

function shortCaseId(caseId: string): string {
  if (caseId.length <= 36) return caseId;
  const parts = caseId.split("__");
  return parts.length > 1 ? parts[parts.length - 1] : caseId.slice(-32);
}

export function CodingBenchmarkPage() {
  const [snapshots, setSnapshots] = useState<CodingBenchmarkSnapshot[]>([]);
  const [selectedSnapshot, setSelectedSnapshot] = useState<CodingBenchmarkSnapshot | null>(null);
  const [campaigns, setCampaigns] = useState<EvaluationRun[]>([]);
  const [latestRun, setLatestRun] = useState<EvaluationRun | null>(null);
  const [trials, setTrials] = useState<CodingBenchmarkTrial[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [controlling, setControlling] = useState(false);
  const [error, setError] = useState("");

  const loadSnapshots = useCallback(async () => {
    setError("");
    try {
      const data = await getCodingBenchmarkSnapshots();
      setSnapshots(data);
      setSelectedSnapshot((current) => current ?? (data[0] ?? null));
    } catch (requestError) {
      setError(getErrorMessage(requestError, "加载快照列表失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshTrials = useCallback(async (runId: string) => {
    if (!runId) {
      setTrials([]);
      return;
    }
    try {
      const data = await getCodingBenchmarkTrials(runId);
      setTrials(data);
    } catch (requestError) {
      toast.error(getErrorMessage(requestError, "加载 Trial 列表失败"));
    }
  }, []);

  const refreshRun = useCallback(async (runId: string) => {
    if (!runId) return;
    try {
      const data = await getEvaluationRun(runId);
      setLatestRun(data);
    } catch (requestError) {
      setError(getErrorMessage(requestError, "刷新评测状态失败"));
    }
  }, []);

  const loadCampaigns = useCallback(async (preferRunId?: string) => {
    try {
      const data = await getCodingBenchmarkCampaigns();
      setCampaigns(data);
      if (preferRunId) {
        const found = data.find((item) => item.runId === preferRunId);
        if (found) setLatestRun(found);
      } else if (data.length > 0) {
        setLatestRun((current) => current ?? data[0]);
      }
    } catch (requestError) {
      setError(getErrorMessage(requestError, "加载评测历史失败"));
    }
  }, []);

  useEffect(() => {
    void loadSnapshots();
    void loadCampaigns();
  }, [loadSnapshots, loadCampaigns]);

  useEffect(() => {
    if (latestRun?.runId) {
      void refreshTrials(latestRun.runId);
    }
  }, [latestRun?.runId, refreshTrials]);

  useEffect(() => {
    if (!latestRun || !isEvaluationRunActive(latestRun.status)) return;
    const timer = window.setInterval(() => {
      void refreshRun(latestRun.runId);
      void refreshTrials(latestRun.runId);
      void loadCampaigns(latestRun.runId);
      if (!isEvaluationRunActive(latestRun.status)) {
        window.clearInterval(timer);
      }
    }, 2_000);
    return () => window.clearInterval(timer);
  }, [latestRun, refreshRun, refreshTrials, loadCampaigns]);

  const selectCampaign = (run: EvaluationRun) => {
    setLatestRun(run);
  };

  const submitProbe = async () => {
    if (!selectedSnapshot) return;
    setSubmitting(true);
    setError("");
    try {
      const run = await createCodingBenchmarkProbe(selectedSnapshot.snapshotId);
      toast.success(`探针评测 ${run.runId} 已提交`);
      setLatestRun(run);
      await loadCampaigns(run.runId);
      await refreshTrials(run.runId);
    } catch (requestError) {
      const msg = getErrorMessage(requestError, "启动探针评测失败");
      setError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const pauseRun = async () => {
    if (!latestRun) return;
    setControlling(true);
    setError("");
    try {
      const run = await pauseCodingBenchmarkCampaign(latestRun.runId);
      setLatestRun(run);
      toast.success("调度已暂停");
    } catch (requestError) {
      const msg = getErrorMessage(requestError, "暂停调度失败");
      setError(msg);
      toast.error(msg);
    } finally {
      setControlling(false);
    }
  };

  const resumeRun = async () => {
    if (!latestRun) return;
    setControlling(true);
    setError("");
    try {
      const run = await resumeCodingBenchmarkCampaign(latestRun.runId);
      setLatestRun(run);
      toast.success("调度已恢复");
    } catch (requestError) {
      const msg = getErrorMessage(requestError, "恢复调度失败");
      setError(msg);
      toast.error(msg);
    } finally {
      setControlling(false);
    }
  };

  const cancelRun = async () => {
    if (!latestRun) return;
    if (!window.confirm(`确认取消评测 ${latestRun.runId}？进行中的 Trial 将被中止。`)) return;
    setControlling(true);
    setError("");
    try {
      const run = await cancelCodingBenchmarkCampaign(latestRun.runId);
      setLatestRun(run);
      toast.success("评测已取消");
      await refreshTrials(run.runId);
    } catch (requestError) {
      const msg = getErrorMessage(requestError, "取消评测失败");
      setError(msg);
      toast.error(msg);
    } finally {
      setControlling(false);
    }
  };

  const submitFormal = async () => {
    if (!selectedSnapshot) return;
    if (!window.confirm("正式评测将执行 20×4+4 个 Trial，耗时较长，确认继续？")) return;
    setSubmitting(true);
    setError("");
    try {
      const run = await createCodingBenchmarkFormal(selectedSnapshot.snapshotId);
      toast.success(`正式评测 ${run.runId} 已提交`);
      setLatestRun(run);
      await loadCampaigns(run.runId);
      await refreshTrials(run.runId);
    } catch (requestError) {
      const msg = getErrorMessage(requestError, "启动正式评测失败");
      setError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const refreshAll = async () => {
    setLoading(true);
    await Promise.all([loadSnapshots(), loadCampaigns(latestRun?.runId)]);
    if (latestRun) {
      await refreshTrials(latestRun.runId);
    }
    setLoading(false);
  };

  return (
    <div className="admin-page coding-benchmark-page space-y-4">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <Code2 className="h-6 w-6 text-primary" />
            <span>编码消融评测</span>
          </h1>
          <p className="admin-page-subtitle">
            选择已就绪的快照，启动探针评测（2×4=8 Trials）或正式评测（20×4+4 Trials），观察执行进度与结果
          </p>
        </div>
        <div className="admin-page-actions flex items-center gap-2">
          <Button variant="outline" onClick={() => void refreshAll()} disabled={loading} title="刷新快照与结果">
            <RefreshCw className={loading ? "spin mr-1.5 h-4 w-4" : "mr-1.5 h-4 w-4"} aria-hidden="true" />
            刷新
          </Button>
        </div>
      </div>

      {error ? (
        <div className="coding-benchmark-notice" role="alert">
          <Activity aria-hidden="true" />
          {error}
        </div>
      ) : null}

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-semibold">可用快照</CardTitle>
          <CardDescription className="text-xs">
            选择要评测的 Coding Benchmark 快照，配置由后端管理，页面不暴露任何敏感参数
          </CardDescription>
        </CardHeader>
        <CardContent className="pt-0">
          {loading && snapshots.length === 0 ? (
            <div className="py-8 text-center text-xs text-muted-foreground">正在读取快照列表...</div>
          ) : snapshots.length === 0 ? (
            <div className="py-8 text-center text-xs text-muted-foreground">当前没有就绪的 Coding Benchmark 快照。</div>
          ) : (
            <div className="overflow-x-auto">
              <Table className="min-w-[640px]">
                <TableHeader>
                  <TableRow>
                    <TableHead>快照名称</TableHead>
                    <TableHead className="w-[120px]">Case 数</TableHead>
                    <TableHead className="w-[180px]">Digest（前 12 位）</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {snapshots.map((snap) => (
                    <TableRow
                      key={snap.snapshotId}
                      className={selectedSnapshot?.snapshotId === snap.snapshotId ? "bg-primary/5 font-medium" : "cursor-pointer hover:bg-slate-50"}
                      onClick={() => setSelectedSnapshot(snap)}
                    >
                      <TableCell>
                        <div className="font-semibold text-slate-900">{snap.displayLabel}</div>
                        <code className="text-[10px] text-muted-foreground">{snap.snapshotId}</code>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="border-slate-200 bg-slate-50 text-xs">{snap.caseCount}</Badge>
                      </TableCell>
                      <TableCell>
                        <code className="font-mono text-xs text-slate-700">{snap.snapshotDigest.slice(0, 12)}</code>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {selectedSnapshot && (
        <section className="grid gap-3 sm:grid-cols-2" aria-label="启动评测">
          <div className="flex items-center justify-between rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <div className="flex items-center gap-3">
              <div className="rounded-md bg-sky-50 p-2 text-sky-600 border border-sky-100">
                <FlaskConical className="h-5 w-5" aria-hidden="true" />
              </div>
              <div>
                <strong className="text-xs font-semibold text-slate-900">探针评测</strong>
                <p className="text-[11px] text-muted-foreground">2 Cases × 4 Arms = 8 Trials，快速验证评测链路</p>
              </div>
            </div>
            <Button
              className="admin-primary-gradient gap-1.5 text-xs shadow-sm"
              onClick={() => void submitProbe()}
              disabled={submitting}
            >
              <Play className="h-3.5 w-3.5" aria-hidden="true" />
              <span>{submitting ? "正在提交..." : "启动探针"}</span>
            </Button>
          </div>

          <div className="flex items-center justify-between rounded-lg border border-indigo-200 bg-indigo-50/20 p-4 shadow-sm">
            <div className="flex items-center gap-3">
              <div className="rounded-md bg-indigo-50 p-2 text-indigo-600 border border-indigo-100">
                <Activity className="h-5 w-5" aria-hidden="true" />
              </div>
              <div>
                <strong className="text-xs font-semibold text-indigo-950">正式评测</strong>
                <p className="text-[11px] text-indigo-700/80">20 Cases × 4 Arms + 4 Sentinels，完整消融实验</p>
              </div>
            </div>
            <Button
              className="bg-indigo-600 hover:bg-indigo-700 text-white gap-1.5 text-xs shadow-sm"
              onClick={() => void submitFormal()}
              disabled={submitting}
            >
              <Play className="h-3.5 w-3.5" aria-hidden="true" />
              <span>{submitting ? "正在提交..." : "启动正式评测"}</span>
            </Button>
          </div>
        </section>
      )}

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-semibold">评测历史</CardTitle>
          <CardDescription className="text-xs">
            最近的编码消融 Campaign。刷新页面后仍可查看，不依赖本会话是否点过启动
          </CardDescription>
        </CardHeader>
        <CardContent className="pt-0">
          {campaigns.length === 0 ? (
            <div className="py-8 text-center text-xs text-muted-foreground">还没有编码消融评测记录。</div>
          ) : (
            <div className="overflow-x-auto">
              <Table className="min-w-[720px]">
                <TableHeader>
                  <TableRow>
                    <TableHead>名称</TableHead>
                    <TableHead className="w-[120px]">状态</TableHead>
                    <TableHead className="w-[160px]">通过 / 失败</TableHead>
                    <TableHead className="w-[120px]">进度</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {campaigns.map((run) => (
                    <TableRow
                      key={run.runId}
                      className={latestRun?.runId === run.runId ? "bg-primary/5 font-medium" : "cursor-pointer hover:bg-slate-50"}
                      onClick={() => selectCampaign(run)}
                    >
                      <TableCell>
                        <div className="font-semibold text-slate-900">{run.name || run.runId}</div>
                        <code className="text-[10px] text-muted-foreground">{run.runId}</code>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className={statusBadgeClass(run.status)}>{statusLabel(run.status)}</Badge>
                      </TableCell>
                      <TableCell>
                        <span className="text-xs">
                          <strong className="text-emerald-600">{run.passedSampleCount}</strong> / <strong className="text-rose-600">{run.failedSampleCount}</strong>
                          <span className="text-[10px] text-muted-foreground ml-1">共 {run.sampleCount}</span>
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className="font-mono text-xs text-slate-700">{run.progressPercent}%</span>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {latestRun && (
        <Card>
          <CardHeader className="pb-3">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <CardTitle className="text-sm font-semibold">Campaign 详情与 Trial 结果板</CardTitle>
                <CardDescription className="text-xs font-mono">
                  {latestRun.name || latestRun.runId} · {latestRun.runId}
                  {latestRun.config.snapshotId || latestRun.config.environmentId ? (
                    <span> · snapshot: {latestRun.config.snapshotId || latestRun.config.environmentId}</span>
                  ) : null}
                </CardDescription>
              </div>
              <div className="flex items-center gap-2">
                <Badge variant="outline" className={statusBadgeClass(latestRun.status)}>{statusLabel(latestRun.status)}</Badge>
                {latestRun.dispatchPaused ? <Badge variant="outline" className="border-amber-300 bg-amber-50 text-amber-800">调度已暂停</Badge> : null}
                {isEvaluationRunActive(latestRun.status) ? (
                  <div className="flex items-center gap-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-7 text-xs"
                      onClick={() => void pauseRun()}
                      disabled={controlling || latestRun.dispatchPaused}
                    >
                      <PauseCircle className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                      暂停
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-7 text-xs"
                      onClick={() => void resumeRun()}
                      disabled={controlling || !latestRun.dispatchPaused}
                    >
                      <PlayCircle className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                      恢复
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-7 text-xs text-destructive hover:bg-destructive/10"
                      onClick={() => void cancelRun()}
                      disabled={controlling}
                    >
                      <Ban className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                      取消
                    </Button>
                  </div>
                ) : null}
              </div>
            </div>
          </CardHeader>

          <CardContent className="space-y-4 pt-0">
            {/* 进度与样本统计 */}
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-lg border border-slate-200 bg-slate-50/50 p-3">
                <div className="text-xs text-muted-foreground">执行进度</div>
                <div className="text-base font-bold text-slate-900">{latestRun.progressPercent}%</div>
              </div>
              <div className="rounded-lg border border-slate-200 bg-slate-50/50 p-3">
                <div className="text-xs text-muted-foreground">总样本</div>
                <div className="text-base font-bold text-slate-900">{latestRun.sampleCount}</div>
              </div>
              <div className="rounded-lg border border-emerald-200 bg-emerald-50/40 p-3">
                <div className="text-xs font-medium text-emerald-800">通过</div>
                <div className="text-base font-bold text-emerald-900">{latestRun.passedSampleCount}</div>
              </div>
              <div className="rounded-lg border border-rose-200 bg-rose-50/40 p-3">
                <div className="text-xs font-medium text-rose-800">失败</div>
                <div className="text-base font-bold text-rose-900">{latestRun.failedSampleCount}</div>
              </div>
            </div>

            {latestRun.dispatchPaused && isEvaluationRunActive(latestRun.status) ? (
              <div className="rounded-md border border-amber-200 bg-amber-50 p-2.5 text-xs text-amber-800 flex items-center gap-2">
                <PauseCircle className="h-4 w-4 shrink-0" aria-hidden="true" />
                <span>调度已暂停：在途 Trial 将继续完成，不再领取新 Trial。</span>
              </div>
            ) : null}

            {latestRun.errorMessage && (
              <div className="rounded-md border border-rose-200 bg-rose-50 p-2.5 text-xs text-rose-800">
                <strong>{latestRun.errorCategory || "执行错误"}: </strong>{latestRun.errorMessage}
              </div>
            )}

            {/* Trial 结果列表 */}
            <div>
              <div className="mb-2 flex items-center justify-between">
                <strong className="text-xs font-semibold text-slate-800">Trial 结果板</strong>
                <span className="text-[10px] text-muted-foreground">{trials.length} 条 · case × arm</span>
              </div>
              {trials.length === 0 ? (
                <div className="py-8 text-center text-xs text-muted-foreground">该 Campaign 暂无 Trial 记录。</div>
              ) : (
                <div className="overflow-x-auto">
                  <Table className="min-w-[960px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead>Case</TableHead>
                        <TableHead className="w-[100px]">Arm</TableHead>
                        <TableHead className="w-[120px]">状态</TableHead>
                        <TableHead className="w-[120px]">Verdict</TableHead>
                        <TableHead>错误摘要</TableHead>
                        <TableHead className="w-[80px] text-right">详情</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {trials.map((trial) => (
                        <TableRow key={trial.trialId}>
                          <TableCell>
                            <div className="font-semibold text-slate-900" title={trial.caseId}>{shortCaseId(trial.caseId)}</div>
                            <code className="text-[10px] text-muted-foreground">{trial.trialId}</code>
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline" className="border-sky-200 bg-sky-50 text-sky-700 text-xs">{trial.arm}</Badge>
                            {trial.replicateNo > 0 ? <span className="ml-1 text-[10px] text-muted-foreground">sentinel</span> : null}
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline" className={statusBadgeClass(trial.status)}>{statusLabel(trial.status)}</Badge>
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline" className={verdictBadgeClass(trial.verdict)}>{verdictLabel(trial.verdict)}</Badge>
                          </TableCell>
                          <TableCell>
                            <span className="text-xs text-muted-foreground truncate max-w-[280px] inline-block" title={trial.errorMessage || undefined}>
                              {trial.errorMessage || "—"}
                            </span>
                          </TableCell>
                          <TableCell className="text-right">
                            <Button asChild size="sm" variant="ghost" className="h-7 text-xs">
                              <Link to={`/admin/evaluations/coding-benchmarks/campaigns/${latestRun.runId}/trials/${trial.trialId}`}>
                                查看
                              </Link>
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
