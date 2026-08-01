import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, RefreshCw } from "lucide-react";

import { Badge, Button, Card, Empty, PageHeader } from "@/components/Ui";
import {
  getCodingBenchmarkTrialArtifact,
  getCodingBenchmarkTrialDetail,
  type CodingBenchmarkTrialArtifactContent,
  type CodingBenchmarkTrialDetail
} from "@/services/evaluationService";
import { getErrorMessage } from "@/utils/error";

function statusTone(status: string): "neutral" | "success" | "warning" | "danger" | "info" {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED" || status === "CANCELLED") return "danger";
  if (String(status).startsWith("RUNNING") || status === "PREPARING") return "info";
  if (status === "QUEUED" || status === "RETRY_PENDING") return "warning";
  return "neutral";
}

function verdictTone(verdict: string): "neutral" | "success" | "warning" | "danger" | "info" {
  if (verdict === "PASS") return "success";
  if (verdict === "TEST_FAIL" || verdict === "NO_PATCH" || verdict === "PROTOCOL_ERROR") return "danger";
  if (verdict === "INFRA_ERROR") return "warning";
  return "neutral";
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    QUEUED: "排队中",
    PREPARING: "准备中",
    RUNNING_AGENTS: "Agent 执行中",
    RUNNING_ORACLE: "Oracle 评分中",
    RETRY_PENDING: "待重试",
    SUCCEEDED: "已通过",
    FAILED: "已失败",
    CANCELLED: "已取消"
  };
  return labels[status] ?? status;
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

export function CodingBenchmarkTrialDetailPage() {
  const { runId = "", trialId = "" } = useParams();
  const [detail, setDetail] = useState<CodingBenchmarkTrialDetail | null>(null);
  const [selectedKey, setSelectedKey] = useState("");
  const [artifact, setArtifact] = useState<CodingBenchmarkTrialArtifactContent | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadDetail = useCallback(async () => {
    if (!runId || !trialId) return;
    setLoading(true);
    setError("");
    try {
      const data = await getCodingBenchmarkTrialDetail(runId, trialId);
      setDetail(data);
      const firstAvailable = data.artifacts.find((item) => item.available)?.key
        || data.previews[0]?.key
        || "";
      setSelectedKey((current) => current || firstAvailable);
    } catch (requestError) {
      setError(getErrorMessage(requestError, "加载 Trial 详情失败"));
      setDetail(null);
    } finally {
      setLoading(false);
    }
  }, [runId, trialId]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  useEffect(() => {
    if (!detail || !selectedKey || !runId || !trialId) {
      setArtifact(null);
      return;
    }
    const preview = detail.previews.find((item) => item.key === selectedKey);
    if (preview) {
      setArtifact({
        key: preview.key,
        label: preview.label,
        content: preview.content,
        truncated: preview.truncated,
        sizeBytes: detail.artifacts.find((item) => item.key === selectedKey)?.sizeBytes ?? 0
      });
    }
    let cancelled = false;
    void (async () => {
      try {
        const content = await getCodingBenchmarkTrialArtifact(runId, trialId, selectedKey);
        if (!cancelled) setArtifact(content);
      } catch {
        // Keep preview if full fetch fails.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [detail, selectedKey, runId, trialId]);

  const trial = detail?.trial;

  return (
    <div className="admin-page coding-benchmark-page">
      <PageHeader
        title="Trial 详情"
        description="查看单个评测项的生命周期、Oracle 评分与允许读取的执行产物。"
        action={
          <div className="coding-benchmark-detail-actions">
            <Link className="ui-button ui-button--ghost" to="/admin/evaluations/coding-benchmarks">
              <ArrowLeft aria-hidden="true" />返回列表
            </Link>
            <Button variant="ghost" onClick={() => void loadDetail()} disabled={loading}>
              <RefreshCw className={loading ? "spin" : ""} aria-hidden="true" />刷新
            </Button>
          </div>
        }
      />

      {error ? <div className="coding-benchmark-notice" role="alert">{error}</div> : null}

      {loading && !detail ? <Empty>正在加载 Trial 详情...</Empty> : null}

      {trial ? (
        <>
          <Card title={`${trial.caseId} · Arm ${trial.arm}`}>
            <div className="coding-benchmark-run-summary">
              <div className="coding-benchmark-run-meta">
                <div className="coding-benchmark-run-head">
                  <Badge tone={statusTone(trial.status)}>{statusLabel(trial.status)}</Badge>
                  <Badge tone={verdictTone(trial.verdict)}>{verdictLabel(trial.verdict)}</Badge>
                </div>
                <div className="coding-benchmark-run-id">
                  <code>{trial.trialId}</code>
                  <small>campaign · {trial.campaignId}</small>
                </div>
              </div>
              {trial.errorMessage ? (
                <div className="coding-benchmark-run-error">
                  <span><strong>{trial.errorCategory || "错误"}</strong>{trial.errorMessage}</span>
                </div>
              ) : null}
            </div>
          </Card>

          <Card title="生命周期" description="Trial 状态流转事件。">
            {detail.events.length === 0 ? (
              <Empty>暂无事件。</Empty>
            ) : (
              <div className="coding-benchmark-timeline">
                {detail.events.map((event) => (
                  <div key={event.eventId} className="coding-benchmark-timeline-item">
                    <Badge tone={statusTone(event.toStatus)}>{statusLabel(event.toStatus)}</Badge>
                    <span>{event.message || event.errorMessage || "—"}</span>
                    <time>{new Date(event.occurredAtEpochMillis).toLocaleString("zh-CN")}</time>
                  </div>
                ))}
              </div>
            )}
          </Card>

          <div className="coding-benchmark-detail-grid">
            <Card title="产物" description="仅展示允许读取的结构化文件，不暴露主机绝对路径。">
              <div className="coding-benchmark-artifact-list">
                {detail.artifacts.map((item) => (
                  <button
                    key={item.key}
                    type="button"
                    className={selectedKey === item.key ? "is-selected" : ""}
                    disabled={!item.available}
                    onClick={() => setSelectedKey(item.key)}
                  >
                    <strong>{item.label}</strong>
                    <small>{item.available ? `${item.sizeBytes} B` : "不可用"}</small>
                  </button>
                ))}
              </div>
            </Card>

            <Card title={artifact?.label || "内容预览"}>
              {artifact ? (
                <>
                  {artifact.truncated ? <small className="coding-benchmark-truncated">内容已截断</small> : null}
                  <pre className="coding-benchmark-artifact-content">{artifact.content}</pre>
                </>
              ) : (
                <Empty>选择左侧产物查看内容。</Empty>
              )}
            </Card>
          </div>
        </>
      ) : null}
    </div>
  );
}
