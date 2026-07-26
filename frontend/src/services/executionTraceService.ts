import { api } from "@/services/api";
import {
  getRdTask,
  getRdTaskExecutionOverview,
  getRdTaskMaterials,
  getRdTaskTimeline,
  type RdTask,
  type RdTaskExecutionOverview,
  type RdTaskStatusEvent,
  type TaskMaterial
} from "@/services/rdTaskService";
import type { AgentExecutionProfileSnapshot } from "@/services/projectService";

export type ExecutionTraceQuery = {
  projectId?: string;
  taskType?: string;
  status?: string;
  role?: string;
  provider?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
};

export type ExecutionTraceRecord = {
  taskId: string;
  projectId: string;
  projectName: string;
  taskType: string;
  taskStatus: string;
  title: string;
  currentRole: string;
  currentStageStatus: string;
  providerName: string;
  progressCompleted: number;
  progressTotal: number;
  retryCount: number;
  blocked: boolean;
  elapsedMillis: number;
  updateTimeEpochMillis: number;
};

export type ExecutionTracePageResult = {
  records: ExecutionTraceRecord[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
};

export type ExecutionTraceAuditEvent = {
  repairRecordId: string;
  taskId: string;
  ticketId: string;
  type: string;
  externalSystem: string;
  summary: string;
  metadata: Record<string, string>;
  createdAtEpochMillis: number;
};

export type ExecutionTraceDetail = {
  task: RdTask;
  overview: RdTaskExecutionOverview;
  materials: TaskMaterial[];
  timeline: RdTaskStatusEvent[];
  auditEvents: ExecutionTraceAuditEvent[];
};

export type AgentRuntimeEvent = {
  protocol: "rd-agent-event/v1";
  eventType: string;
  sequence?: number;
  sourceSequence?: number;
  stageRunId: string;
  taskId: string;
  role: string;
  runtimeType: string;
  snapshotId: string;
  provider: string;
  model: string;
  occurredAt: string;
  payload: Record<string, unknown>;
  redacted: boolean;
};

export type AgentRuntimeEventSnapshot = {
  version: number;
  source: "LIVE" | "ARCHIVED" | string;
  available: boolean;
  finalized: boolean;
  truncated: boolean;
  hasMore: boolean;
  nextSequence: number;
  events: AgentRuntimeEvent[];
};

export function getExecutionTraces(query: ExecutionTraceQuery = {}): Promise<ExecutionTracePageResult> {
  return api.get<ExecutionTracePageResult, ExecutionTracePageResult>("/admin/execution-traces", {
    params: {
      projectId: query.projectId?.trim() || undefined,
      taskType: query.taskType?.trim() || undefined,
      status: query.status?.trim() || undefined,
      role: query.role?.trim() || undefined,
      provider: query.provider?.trim() || undefined,
      keyword: query.keyword?.trim() || undefined,
      page: query.page ?? 1,
      pageSize: query.pageSize ?? 20
    }
  });
}

export const getExecutionTraceAuditEvents = (taskId: string): Promise<ExecutionTraceAuditEvent[]> =>
  api.get<ExecutionTraceAuditEvent[], ExecutionTraceAuditEvent[]>("/admin/operations/audit-events", {
    params: { taskId }
  });

export async function getExecutionTraceDetail(taskId: string): Promise<ExecutionTraceDetail> {
  const [task, overview, materials, timeline, auditEvents] = await Promise.all([
    getRdTask(taskId),
    getRdTaskExecutionOverview(taskId),
    getRdTaskMaterials(taskId),
    getRdTaskTimeline(taskId),
    getExecutionTraceAuditEvents(taskId)
  ]);
  return { task, overview, materials, timeline, auditEvents };
}

export const getAgentRuntimeSnapshot = (
  taskId: string,
  stageRunId: string
): Promise<AgentExecutionProfileSnapshot> =>
  api.get<AgentExecutionProfileSnapshot, AgentExecutionProfileSnapshot>(
    `/admin/rd-tasks/${taskId}/stage-runs/${stageRunId}/execution-profile`
  );

export const getAgentRuntimeEvents = (
  taskId: string,
  stageRunId: string,
  query: { after?: number; limit?: number } = {}
): Promise<AgentRuntimeEventSnapshot> =>
  api.get<AgentRuntimeEventSnapshot, AgentRuntimeEventSnapshot>(
    agentRuntimeEventsPath(taskId, stageRunId, query)
  );

export function agentRuntimeEventsPath(
  taskId: string,
  stageRunId: string,
  query: { after?: number; limit?: number } = {}
): string {
  const params = new URLSearchParams();
  if (query.after && query.after > 0) params.set("after", String(query.after));
  params.set("limit", String(query.limit && query.limit > 0 ? query.limit : 100));
  return `/admin/rd-tasks/${encodeURIComponent(taskId)}/stage-runs/${encodeURIComponent(stageRunId)}/execution-events?${params.toString()}`;
}
