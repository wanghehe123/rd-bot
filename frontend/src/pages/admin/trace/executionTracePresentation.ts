import type { ExecutionTraceQuery } from "@/services/executionTraceService";

export function executionTraceQueryForScope(
  projectId: string,
  filters: Omit<ExecutionTraceQuery, "projectId"> = {}
): ExecutionTraceQuery {
  const normalizedProjectId = projectId.trim();
  return {
    ...(normalizedProjectId && normalizedProjectId !== "all" ? { projectId: normalizedProjectId } : {}),
    ...filters
  };
}

export function traceProgressPercent(completed: number, total: number): number {
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(completed)) return 0;
  return Math.max(0, Math.min(100, Math.round((completed / total) * 100)));
}

export function formatTraceDuration(elapsedMillis: number): string {
  if (!Number.isFinite(elapsedMillis) || elapsedMillis <= 0) return "--";
  if (elapsedMillis < 60_000) return `${Math.round(elapsedMillis / 1_000)} 秒`;
  if (elapsedMillis < 3_600_000) return `${(elapsedMillis / 60_000).toFixed(1)} 分钟`;
  return `${(elapsedMillis / 3_600_000).toFixed(1)} 小时`;
}

export const TRACE_STATUS_LABELS: Record<string, string> = {
  CREATED: "已创建",
  MATERIAL_COLLECTING: "收集材料",
  MATERIAL_READY: "材料就绪",
  CONTEXT_BUILDING: "构建上下文",
  CONTEXT_READY: "上下文就绪",
  PLAN_GENERATING: "生成计划",
  PLAN_GENERATED: "计划就绪",
  WAITING_POLICY: "策略检查",
  WAITING_APPROVAL: "等待审批",
  SEARCHING: "检索中",
  EXECUTING: "执行中",
  VALIDATING: "验证中",
  PR_CREATING: "创建 PR",
  COMMITTED: "已提交",
  MERGED: "已合并",
  REPORTING: "报告中",
  COMPLETED: "已完成",
  REJECTED: "已打回",
  FAILED_RETRYABLE: "可重试失败",
  FAILED_NEEDS_HUMAN: "需人工处理",
  CANCELLED: "已取消",
  DEAD_LETTERED: "死信",
  RECOVERING: "恢复中"
};

export const TRACE_ROLE_LABELS: Record<string, string> = {
  BUG_EVIDENCE_COLLECTOR: "证据收集",
  BUG_RAG_RETRIEVER: "RAG 检索",
  BUG_ACCEPTANCE_PLANNER: "验收规划",
  BUG_CODING_AGENT: "修复执行",
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证"
};

export const traceStatusLabel = (status: string) => TRACE_STATUS_LABELS[status] || status || "待开始";
export const traceRoleLabel = (role: string) => TRACE_ROLE_LABELS[role] || role || "待分配";
