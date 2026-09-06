const ALL_PROJECTS_SCOPE = "all";

export type DashboardAvailabilityRatio = {
  available: boolean;
  value: number | null;
};

export type TaskListFilters = {
  taskType?: string;
  status?: string;
};

const STATUS_LABELS: Record<string, string> = {
  CREATED: "已创建",
  MATERIAL_COLLECTING: "收集材料",
  MATERIAL_READY: "材料就绪",
  CONTEXT_BUILDING: "构建上下文",
  CONTEXT_READY: "上下文就绪",
  PLAN_GENERATING: "生成计划",
  PLAN_GENERATED: "计划就绪",
  WAITING_POLICY: "策略检查",
  WAITING_APPROVAL: "等待审批",
  WAITING_USER_INPUT: "等待补充信息",
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
  RECOVERING: "恢复中",
  DELETED: "已删除"
};

const ROLE_LABELS: Record<string, string> = {
  BUG_EVIDENCE_COLLECTOR: "证据收集",
  BUG_RAG_RETRIEVER: "RAG 检索",
  BUG_ACCEPTANCE_PLANNER: "验收规划",
  BUG_CODING_AGENT: "修复执行",
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证"
};

const STAGE_STATUS_LABELS: Record<string, string> = {
  PENDING: "待开始",
  CONTEXT_READY: "上下文就绪",
  DISPATCHING: "调度中",
  RUNNING: "执行中",
  RESULT_COLLECTING: "收集结果",
  VERIFYING: "验证中",
  SUCCEEDED: "已成功",
  FAILED_RETRYABLE: "可重试失败",
  FAILED_NEEDS_HUMAN: "需人工处理",
  SKIPPED: "已跳过",
  CANCELLED: "已取消",
  RECOVERING: "恢复中"
};

/** Creates a task-list URL that retains a concrete delivery-project scope. */
export function taskListHref(projectId: string, filters: TaskListFilters = {}): string {
  const searchParams = new URLSearchParams();
  const normalizedProjectId = projectId.trim();

  if (normalizedProjectId && normalizedProjectId !== ALL_PROJECTS_SCOPE) {
    searchParams.set("projectId", normalizedProjectId);
  }
  if (filters.taskType?.trim()) {
    searchParams.set("taskType", filters.taskType.trim());
  }
  if (filters.status?.trim()) {
    searchParams.set("status", filters.status.trim());
  }

  const query = searchParams.toString();
  return query ? `/admin/rd-tasks?${query}` : "/admin/rd-tasks";
}

/** Keeps an old async response from being rendered after the selected project changes. */
export function dashboardDataForScope<T>(
  responseProjectId: string,
  selectedProjectId: string,
  data: T | null
): T | null {
  return responseProjectId === selectedProjectId ? data : null;
}

/** Shows unavailable ratios without pretending that unavailable data is a zero percent result. */
export function formatAvailabilityRatio(ratio: DashboardAvailabilityRatio | null | undefined): string {
  if (!ratio?.available || ratio.value == null || !Number.isFinite(ratio.value)) {
    return "--";
  }
  return `${(Math.max(0, Math.min(1, ratio.value)) * 100).toFixed(1)}%`;
}

export function formatDashboardStatus(status: string): string {
  return STATUS_LABELS[status] || status || "未记录";
}

export function formatDashboardRole(role: string): string {
  return ROLE_LABELS[role] || role || "待分配";
}

export function formatDashboardStageStatus(status: string): string {
  return STAGE_STATUS_LABELS[status] || status || "待开始";
}

export function formatDashboardDuration(elapsedMillis: number): string {
  if (!Number.isFinite(elapsedMillis) || elapsedMillis <= 0) {
    return "--";
  }
  if (elapsedMillis < 1_000) {
    return `${Math.round(elapsedMillis)}ms`;
  }
  if (elapsedMillis < 60_000) {
    return `${(elapsedMillis / 1_000).toFixed(1)}s`;
  }
  if (elapsedMillis < 3_600_000) {
    return `${(elapsedMillis / 60_000).toFixed(1)}min`;
  }
  return `${(elapsedMillis / 3_600_000).toFixed(1)}h`;
}

export function formatCny(value: number): string {
  if (!Number.isFinite(value)) {
    return "--";
  }
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value);
}
