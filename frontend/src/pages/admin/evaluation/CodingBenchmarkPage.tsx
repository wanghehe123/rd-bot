import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Activity, Ban, FlaskConical, PauseCircle, Play, PlayCircle, RefreshCw } from "lucide-react";
import { toast } from "sonner";

import { Badge, Button, Card, Empty, PageHeader } from "@/components/Ui";
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

function statusTone(status: string): "neutral" | "success" | "warning" | "danger" | "info" {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED" || status === "CANCELLED") return "danger";
  if (status === "RUNNING_TRIALS" || status === "RECORDING" || status === "PREPARING") return "info";
  if (status === "QUEUED" || status === "CREATED" || status === "CANCEL_REQUESTED") return "warning";
  if (status === "SCORING" || status === "REPORTING" || status === "DIFFING") return "info";
  return "neutral";
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

function verdictTone(verdict: string): "neutral" | "success" | "warning" | "danger" | "info" {
  if (verdict === "PASS") return "success";
  if (verdict === "TEST_FAIL" || verdict === "NO_PATCH" || verdict === "PROTOCOL_ERROR") return "danger";
  if (verdict === "INFRA_ERROR") return "warning";
  if (verdict === "PENDING") return "neutral";
  return "info";
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

  const loadCampaigns = useCallback(async (preferredRunId?: string) => {
    try {
      const data = await getCodingBenchmarkCampaigns(20);
      setCampaigns(data);
      setLatestRun((current) => {
        if (preferredRunId) {
          return data.find((run) => run.runId === preferredRunId) ?? current ?? data[0] ?? null;
        }
        if (current) {
          return data.find((run) => run.runId === current.runId) ?? data[0] ?? current;
        }
        return data[0] ?? null;
      });
    } catch (requestError) {
      setError(getErrorMessage(requestError, "加载评测历史失败"));
    }
  }, []);

  const refreshRun = useCallback(async (runId: string) => {
    try {
      const run = await getEvaluationRun(runId);
      setLatestRun(run);
      setCampaigns((current) => {
        const others = current.filter((item) => item.runId !== run.runId);
        return [run, ...others];
      });
      return run;
    } catch {
      return null;
    }
  }, []);

  const refreshTrials = useCallback(async (runId: string) => {
    try {
      const data = await getCodingBenchmarkTrials(runId);
      setTrials(data);
      return data;
    } catch {
      setTrials([]);
      return [];
    }
  }, []);

  useEffect(() => {
    void (async () => {
      await Promise.all([loadSnapshots(), loadCampaigns()]);
    })();
  }, [loadSnapshots, loadCampaigns]);

  useEffect(() => {
    if (!latestRun) {
      setTrials([]);
      return;
    }
    void refreshTrials(latestRun.runId);
  }, [latestRun?.runId, refreshTrials]);

  useEffect(() => {
    if (!latestRun) return;
    if (!isEvaluationRunActive(latestRun.status)) return;
    const timer = window.setInterval(async () => {
      const updated = await refreshRun(latestRun.runId);
      await refreshTrials(latestRun.runId);
      if (!updated || !isEvaluationRunActive(updated.status)) {
        window.clearInterval(timer);
        void loadCampaigns(latestRun.runId);
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
    if (!window.confirm(`确认取消编码消融评测 ${latestRun.runId}？`)) return;
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
    <div className="admin-page coding-benchmark-page">
      <PageHeader
        title="编码消融评测"
        description="选择已就绪的快照，启动探针评测（2×4=8 Trials）或正式评测（20×4+4 Trials），观察执行进度与结果。"
        action={
          <Button variant="ghost" onClick={() => void refreshAll()} disabled={loading} title="刷新快照与结果">
            <RefreshCw className={loading ? "spin" : ""} aria-hidden="true" />刷新
          </Button>
        }
      />

      {error ? (
        <div className="coding-benchmark-notice" role="alert">
          <Activity aria-hidden="true" />
          {error}
        </div>
      ) : null}

      <Card
        title="可用快照"
        description="选择要评测的 Coding Benchmark 快照，配置由后端管理，页面不暴露任何敏感参数。"
      >
        {loading && snapshots.length === 0 ? (
          <div className="coding-benchmark-loading">
            <Empty>正在读取快照列表...</Empty>
          </div>
        ) : snapshots.length === 0 ? (
          <Empty>当前没有就绪的 Coding Benchmark 快照。</Empty>
        ) : (
          <div className="coding-benchmark-snapshot-list">
            <Table headers={["快照名称", "Case 数", "Digest（前 12 位）"]} minWidth={640}>
              {snapshots.map((snap) => (
                <tr
                  key={snap.snapshotId}
                  className={selectedSnapshot?.snapshotId === snap.snapshotId ? "is-selected" : ""}
                  onClick={() => setSelectedSnapshot(snap)}
                  style={{ cursor: "pointer" }}
                >
                  <td>
                    <strong>{snap.displayLabel}</strong>
                    <small>{snap.snapshotId}</small>
                  </td>
                  <td>
                    <Badge tone="neutral">{snap.caseCount}</Badge>
                  </td>
                  <td>
                    <code>{snap.snapshotDigest.slice(0, 12)}</code>
                  </td>
                </tr>
              ))}
            </Table>
          </div>
        )}
      </Card>

      {selectedSnapshot && (
        <section className="coding-benchmark-actions" aria-label="启动评测">
          <div className="coding-benchmark-action-card">
            <div className="coding-benchmark-action-info">
              <div className="coding-benchmark-action-label">
                <FlaskConical aria-hidden="true" />
                <span><strong>探针评测</strong><small>2 Cases × 4 Arms = 8 Trials，快速验证评测链路</small></span>
              </div>
            </div>
            <Button
              variant="primary"
              onClick={() => void submitProbe()}
              disabled={submitting}
            >
              <Play aria-hidden="true" />
              {submitting ? "正在提交..." : "启动探针"}
            </Button>
          </div>

          <div className="coding-benchmark-action-card coding-benchmark-action-card--formal">
            <div className="coding-benchmark-action-info">
              <div className="coding-benchmark-action-label">
                <Activity aria-hidden="true" />
                <span><strong>正式评测</strong><small>20 Cases × 4 Arms + 4 Sentinels，完整消融实验</small></span>
              </div>
            </div>
            <Button
              variant="primary"
              onClick={() => void submitFormal()}
              disabled={submitting}
            >
              <Play aria-hidden="true" />
              {submitting ? "正在提交..." : "启动正式评测"}
            </Button>
          </div>
        </section>
      )}

      <Card
        title="评测历史"
        description="最近的编码消融 Campaign。刷新页面后仍可查看，不依赖本会话是否点过启动。"
      >
        {campaigns.length === 0 ? (
          <Empty>还没有编码消融评测记录。</Empty>
        ) : (
          <div className="coding-benchmark-campaign-list">
            <Table headers={["名称", "状态", "通过 / 失败", "进度"]} minWidth={720}>
              {campaigns.map((run) => (
                <tr
                  key={run.runId}
                  className={latestRun?.runId === run.runId ? "is-selected" : ""}
                  onClick={() => selectCampaign(run)}
                  style={{ cursor: "pointer" }}
                >
                  <td>
                    <strong>{run.name || run.runId}</strong>
                    <small>{run.runId}</small>
                  </td>
                  <td>
                    <Badge tone={statusTone(run.status)}>{statusLabel(run.status)}</Badge>
                  </td>
                  <td>
                    <span className="coding-benchmark-pass-fail">
                      <em>{run.passedSampleCount}</em> / <i>{run.failedSampleCount}</i>
                      <small>共 {run.sampleCount}</small>
                    </span>
                  </td>
                  <td>{run.progressPercent}%</td>
                </tr>
              ))}
            </Table>
          </div>
        )}
      </Card>

      {latestRun && (
        <Card title="Campaign 详情与 Trial 结果板">
          <div className="coding-benchmark-run-summary">
            <div className="coding-benchmark-run-meta">
              <div className="coding-benchmark-run-head">
                <strong>{latestRun.name || latestRun.runId}</strong>
                <Badge tone={statusTone(latestRun.status)}>{statusLabel(latestRun.status)}</Badge>
                {latestRun.dispatchPaused ? <Badge tone="warning">调度已暂停</Badge> : null}
              </div>
              <div className="coding-benchmark-run-id">
                <code>{latestRun.runId}</code>
                {latestRun.config.snapshotId || latestRun.config.environmentId ? (
                  <small>snapshot · {latestRun.config.snapshotId || latestRun.config.environmentId}</small>
                ) : null}
              </div>
            </div>

            <div className="coding-benchmark-progress">
              <div className="coding-benchmark-progress-label">
                <span>执行进度</span>
                <span>{latestRun.progressPercent}%</span>
              </div>
              <div className="coding-benchmark-progress-track">
                <i style={{ width: `${latestRun.progressPercent}%` }} />
              </div>
            </div>

            <div className="coding-benchmark-samples">
              <div className="coding-benchmark-sample-stat">
                <span>总样本</span>
                <strong>{latestRun.sampleCount}</strong>
              </div>
              <div className="coding-benchmark-sample-stat coding-benchmark-sample-stat--pass">
                <span>通过</span>
                <strong>{latestRun.passedSampleCount}</strong>
              </div>
              <div className="coding-benchmark-sample-stat coding-benchmark-sample-stat--fail">
                <span>失败</span>
                <strong>{latestRun.failedSampleCount}</strong>
              </div>
            </div>

            {latestRun.dispatchPaused && isEvaluationRunActive(latestRun.status) ? (
              <div className="coding-benchmark-paused-notice" role="status">
                <PauseCircle aria-hidden="true" />
                <span>调度已暂停：在途 Trial 将继续完成，不再领取新 Trial。</span>
              </div>
            ) : null}

            {isEvaluationRunActive(latestRun.status) ? (
              <div className="coding-benchmark-run-controls" aria-label="运行控制">
                <Button
                  variant="ghost"
                  onClick={() => void pauseRun()}
                  disabled={controlling || latestRun.dispatchPaused}
                >
                  <PauseCircle aria-hidden="true" />
                  暂停
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => void resumeRun()}
                  disabled={controlling || !latestRun.dispatchPaused}
                >
                  <PlayCircle aria-hidden="true" />
                  恢复
                </Button>
                <Button
                  variant="danger"
                  onClick={() => void cancelRun()}
                  disabled={controlling}
                >
                  <Ban aria-hidden="true" />
                  取消
                </Button>
              </div>
            ) : null}

            {latestRun.errorMessage && (
              <div className="coding-benchmark-run-error">
                <Activity aria-hidden="true" />
                <span><strong>{latestRun.errorCategory || "执行错误"}</strong>{latestRun.errorMessage}</span>
              </div>
            )}
          </div>

          <div className="coding-benchmark-trial-board" aria-label="Trial 结果板">
            <div className="coding-benchmark-trial-board-head">
              <strong>Trial 结果</strong>
              <small>{trials.length} 条 · case × arm</small>
            </div>
            {trials.length === 0 ? (
              <Empty>该 Campaign 暂无 Trial 记录。</Empty>
            ) : (
              <Table headers={["Case", "Arm", "状态", "Verdict", "错误摘要", "详情"]} minWidth={960}>
                {trials.map((trial) => (
                  <tr key={trial.trialId}>
                    <td>
                      <strong title={trial.caseId}>{shortCaseId(trial.caseId)}</strong>
                      <small>{trial.trialId}</small>
                    </td>
                    <td>
                      <Badge tone="info">{trial.arm}</Badge>
                      {trial.replicateNo > 0 ? <small>sentinel</small> : null}
                    </td>
                    <td>
                      <Badge tone={statusTone(trial.status)}>{statusLabel(trial.status)}</Badge>
                    </td>
                    <td>
                      <Badge tone={verdictTone(trial.verdict)}>{verdictLabel(trial.verdict)}</Badge>
                    </td>
                    <td>
                      <span className="coding-benchmark-trial-error" title={trial.errorMessage || undefined}>
                        {trial.errorMessage || "—"}
                      </span>
                    </td>
                    <td>
                      <Link
                        className="coding-benchmark-trial-link"
                        to={`/admin/evaluations/coding-benchmarks/campaigns/${latestRun.runId}/trials/${trial.trialId}`}
                      >
                        查看
                      </Link>
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </div>
        </Card>
      )}
    </div>
  );
}

function Table({
  headers,
  children,
  minWidth
}: {
  headers: string[];
  children: React.ReactNode;
  minWidth?: number;
}) {
  return (
    <div className="ui-table-wrap">
      <table className="ui-table" style={{ minWidth }}>
        <thead className="ui-table-header">
          <tr>{headers.map((h) => <th key={h}>{h}</th>)}</tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
