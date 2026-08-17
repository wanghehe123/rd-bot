import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, FlaskConical, RefreshCw } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  getCodingBenchmarkTrialArtifact,
  getCodingBenchmarkTrialDetail,
  type CodingBenchmarkTrialArtifactContent,
  type CodingBenchmarkTrialDetail
} from "@/services/evaluationService";
import { getErrorMessage } from "@/utils/error";

function statusBadgeClass(status: string): string {
  if (status === "SUCCEEDED") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (status === "FAILED" || status === "CANCELLED") return "border-rose-200 bg-rose-50 text-rose-700";
  if (String(status).startsWith("RUNNING") || status === "PREPARING") return "border-sky-200 bg-sky-50 text-sky-700";
  if (status === "QUEUED" || status === "RETRY_PENDING") return "border-amber-200 bg-amber-50 text-amber-700";
  return "border-slate-200 bg-slate-50 text-slate-600";
}

function verdictBadgeClass(verdict: string): string {
  if (verdict === "PASS") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (verdict === "TEST_FAIL" || verdict === "NO_PATCH" || verdict === "PROTOCOL_ERROR") return "border-rose-200 bg-rose-50 text-rose-700";
  if (verdict === "INFRA_ERROR") return "border-amber-200 bg-amber-50 text-amber-700";
  return "border-slate-200 bg-slate-50 text-slate-600";
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
    <div className="admin-page coding-benchmark-page space-y-4">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <FlaskConical className="h-6 w-6 text-primary" />
            <span>Trial 详情</span>
          </h1>
          <p className="admin-page-subtitle">
            查看单个评测项的生命周期、Oracle 评分与允许读取的执行产物
          </p>
        </div>
        <div className="coding-benchmark-detail-actions flex items-center gap-2">
          <Button asChild variant="outline">
            <Link to="/admin/evaluations/coding-benchmarks">
              <ArrowLeft className="mr-1.5 h-4 w-4" aria-hidden="true" />
              返回列表
            </Link>
          </Button>
          <Button variant="outline" onClick={() => void loadDetail()} disabled={loading}>
            <RefreshCw className={loading ? "spin mr-1.5 h-4 w-4" : "mr-1.5 h-4 w-4"} aria-hidden="true" />
            刷新
          </Button>
        </div>
      </div>

      {error ? <div className="coding-benchmark-notice" role="alert">{error}</div> : null}

      {loading && !detail ? (
        <div className="py-12 text-center text-xs text-muted-foreground">正在加载 Trial 详情...</div>
      ) : null}

      {trial ? (
        <>
          <Card>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <CardTitle className="text-sm font-semibold">{trial.caseId} · Arm {trial.arm}</CardTitle>
                <div className="flex items-center gap-1.5">
                  <Badge variant="outline" className={statusBadgeClass(trial.status)}>{statusLabel(trial.status)}</Badge>
                  <Badge variant="outline" className={verdictBadgeClass(trial.verdict)}>{verdictLabel(trial.verdict)}</Badge>
                </div>
              </div>
              <CardDescription className="font-mono text-xs">
                <code>{trial.trialId}</code> · campaign: {trial.campaignId}
              </CardDescription>
            </CardHeader>
            {trial.errorMessage ? (
              <CardContent className="pt-0">
                <div className="rounded-md border border-rose-200 bg-rose-50 p-2.5 text-xs text-rose-800">
                  <strong>{trial.errorCategory || "错误"}: </strong>{trial.errorMessage}
                </div>
              </CardContent>
            ) : null}
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-semibold">生命周期</CardTitle>
              <CardDescription className="text-xs">Trial 状态流转事件</CardDescription>
            </CardHeader>
            <CardContent className="pt-0">
              {detail.events.length === 0 ? (
                <div className="py-6 text-center text-xs text-muted-foreground">暂无事件。</div>
              ) : (
                <div className="space-y-2">
                  {detail.events.map((event) => (
                    <div key={event.eventId} className="flex items-center justify-between rounded-md border border-slate-100 bg-slate-50/50 p-2 text-xs">
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className={statusBadgeClass(event.toStatus)}>{statusLabel(event.toStatus)}</Badge>
                        <span className="text-slate-700">{event.message || event.errorMessage || "—"}</span>
                      </div>
                      <time className="text-muted-foreground">{new Date(event.occurredAtEpochMillis).toLocaleString("zh-CN")}</time>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <div className="grid gap-4 md:grid-cols-2">
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-sm font-semibold">产物文件</CardTitle>
                <CardDescription className="text-xs">仅展示允许读取的结构化文件，不暴露主机绝对路径</CardDescription>
              </CardHeader>
              <CardContent className="space-y-1.5 pt-0">
                {detail.artifacts.map((item) => (
                  <button
                    key={item.key}
                    type="button"
                    className={`flex w-full items-center justify-between rounded-lg border p-2.5 text-left text-xs transition ${
                      selectedKey === item.key
                        ? "border-primary bg-primary/5 text-primary font-medium"
                        : "border-slate-200 bg-white hover:bg-slate-50"
                    } ${!item.available ? "opacity-50 cursor-not-allowed" : ""}`}
                    disabled={!item.available}
                    onClick={() => setSelectedKey(item.key)}
                  >
                    <strong>{item.label}</strong>
                    <span className="font-mono text-[10px] text-muted-foreground">{item.available ? `${item.sizeBytes} B` : "不可用"}</span>
                  </button>
                ))}
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-sm font-semibold">{artifact?.label || "内容预览"}</CardTitle>
                {artifact?.truncated ? <CardDescription className="text-xs text-amber-600">内容已截断</CardDescription> : null}
              </CardHeader>
              <CardContent className="pt-0">
                {artifact ? (
                  <pre className="max-h-[400px] overflow-auto rounded-lg border border-slate-200 bg-slate-950 p-3 font-mono text-xs text-slate-100">
                    {artifact.content}
                  </pre>
                ) : (
                  <div className="py-12 text-center text-xs text-muted-foreground">选择左侧产物查看内容。</div>
                )}
              </CardContent>
            </Card>
          </div>
        </>
      ) : null}
    </div>
  );
}

