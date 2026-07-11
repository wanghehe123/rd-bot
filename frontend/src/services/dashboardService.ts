import { api } from "@/services/api";

export type DashboardAvailabilityRatio = {
  available: boolean;
  value: number | null;
};

export type DashboardRuntimeSnapshot = {
  available: boolean;
  activeAlertCount: number;
  runningExecutionCount: number;
  estimatedSpendCny: number;
  costAvailable: boolean;
};

export type DashboardTaskSummary = {
  taskId: string;
  projectId: string;
  taskType: string;
  status: string;
  title: string;
  updateTimeEpochMillis: number;
  currentRole: string;
  currentStageStatus: string;
  progressCompleted: number;
  progressTotal: number;
  provider: string;
  retryCount: number;
  elapsedMillis: number;
  running: boolean;
};

export type DashboardKnowledgeSupport = {
  available: boolean;
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  documentCount: number;
  enabledDocumentCount: number;
};

/** Matches the read-only `RdDashboardOverview` response from `/admin/dashboard/overview`. */
export type RdDashboardOverview = {
  projectId: string;
  projectName: string;
  requirementCount: number;
  bugFixCount: number;
  inProgressCount: number;
  waitingHumanCount: number;
  completedCount: number;
  blockedCount: number;
  statusCounts: Record<string, number>;
  successRate: DashboardAvailabilityRatio;
  runtime: DashboardRuntimeSnapshot;
  currentExecutions: DashboardTaskSummary[];
  recentDeliveries: DashboardTaskSummary[];
  knowledgeSupport: DashboardKnowledgeSupport;
  generatedAtEpochMillis: number;
};

export type DashboardOverviewQuery = {
  projectId?: string;
  limit?: number;
};

export function getDashboardOverview(query: DashboardOverviewQuery = {}): Promise<RdDashboardOverview> {
  const projectId = query.projectId?.trim();
  return api.get<RdDashboardOverview, RdDashboardOverview>("/admin/dashboard/overview", {
    params: {
      projectId: projectId || undefined,
      limit: query.limit
    }
  });
}
