import type { DataQualityStatus, PercentileMetric, RatioMetric } from "@/services/deliveryObservabilityQuery";

export type DeliveryVisualState = "ok" | "unavailable" | "no-sample" | "stale" | "collector-failed";

export function deliveryVisualState(
  quality: DataQualityStatus[] | undefined,
  noSample = false
): DeliveryVisualState {
  const items = quality ?? [];
  if (items.some((item) => !item.available && !item.stale)) {
    return "collector-failed";
  }
  if (items.some((item) => item.stale)) {
    return "stale";
  }
  if (items.some((item) => !item.available)) {
    return "unavailable";
  }
  if (noSample) {
    return "no-sample";
  }
  return "ok";
}

export function formatRatio(metric: RatioMetric | undefined): string {
  if (!metric || !metric.available) {
    return "不可用";
  }
  if (metric.noSample) {
    return "无样本";
  }
  return `${(metric.value * 100).toFixed(1)}% (${metric.numerator}/${metric.denominator})`;
}

export function formatPercentile(metric: PercentileMetric | undefined, window: string): string {
  if (!metric || !metric.available) {
    return "不可用";
  }
  if (metric.noSample) {
    return `无样本 · n=0 · ${window}`;
  }
  const p99 = metric.p99Insufficient || Number.isNaN(metric.p99Seconds)
    ? "P99样本不足"
    : `P99 ${metric.p99Seconds.toFixed(1)}s`;
  return `P50 ${metric.p50Seconds.toFixed(1)}s / P95 ${metric.p95Seconds.toFixed(1)}s / ${p99} · n=${metric.sampleCount} · ${window}`;
}

export function isolateStaleResponse<T extends { projectId?: string; window?: string }>(
  previous: T | null,
  nextProjectId: string,
  nextWindow: string
): T | null {
  if (!previous) {
    return null;
  }
  const previousProject = previous.projectId ?? "";
  const expectedProject = nextProjectId === "all" ? "" : nextProjectId;
  if (previousProject !== expectedProject || (previous.window && previous.window !== nextWindow)) {
    return null;
  }
  return previous;
}
