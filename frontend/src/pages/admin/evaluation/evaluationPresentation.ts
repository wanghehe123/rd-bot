import type { EvaluationDatasetKind, EvaluationJudgeProvider, EvaluationRunStatus, EvaluationSource } from "@/services/evaluationService";

export const evaluationStatusLabel = (status: EvaluationRunStatus) => ({
  CREATED: "已创建",
  QUEUED: "排队中",
  RECORDING: "录制样本",
  SCORING: "计算指标",
  REPORTING: "生成报告",
  DIFFING: "对比基线",
  CANCEL_REQUESTED: "取消中",
  SUCCEEDED: "执行完成",
  FAILED: "执行失败",
  CANCELLED: "已取消"
}[status] || status);

export const evaluationStatusTone = (status: EvaluationRunStatus) => {
  if (status === "SUCCEEDED") return "info";
  if (status === "FAILED") return "danger";
  if (status === "CANCELLED" || status === "CANCEL_REQUESTED") return "warning";
  return "info";
};

export const evaluationGateLabel = (status: string) => ({
  PASSED: "门禁通过",
  NOT_PASSED: "门禁未通过",
  FAILED: "门禁未通过",
  INCOMPLETE: "评测不完整"
}[status] || "待计算");

export const evaluationJudgeStatusLabel = (status: string) => ({
  AVAILABLE: "Judge 可用",
  FAILED: "Judge 失败",
  PARTIAL: "Judge 部分完成",
  SKIPPED: "未启用 Judge",
  NOT_REQUESTED: "未请求 Judge"
}[status] || "Judge 待执行");

export const evaluationDatasetKindLabel = (kind: EvaluationDatasetKind) => ({
  SCORER_SMOKE: "评分器自检",
  QUALITY_BENCHMARK: "质量基线",
  TASK_RUN: "任务实跑"
}[kind] || kind);

export const evaluationSourceLabel = (source: EvaluationSource) =>
  source === "FIXTURE" ? "Fixture 数据" : source === "RAG_HTTP" ? "实时 RAG HTTP" : "本次任务执行";

export const evaluationJudgeLabel = (provider: EvaluationJudgeProvider) => ({
  NONE: "确定性评分",
  RAGAS: "RAGAS",
  OPENAI_COMPATIBLE: "OpenAI Compatible"
}[provider] || provider);

export const formatEvaluationDuration = (startedAt: number, finishedAt: number, now = Date.now()) => {
  if (!startedAt) return "--";
  const millis = Math.max(0, (finishedAt || now) - startedAt);
  if (millis < 1_000) return `${millis}ms`;
  if (millis < 60_000) return `${(millis / 1_000).toFixed(1)}s`;
  const minutes = Math.floor(millis / 60_000);
  const seconds = Math.floor((millis % 60_000) / 1_000);
  return `${minutes}m ${seconds}s`;
};
