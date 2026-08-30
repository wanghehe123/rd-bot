import { api } from "./api";
import { unwrapAdminData } from "./openVikingKnowledgePresentation";
import {
  buildConfirmPayload,
  buildInvalidatePayload,
  buildSoftDeletePayload,
  type ConfirmPayload,
  type InvalidatePayload,
  type ProjectMemoryDetail,
  type ProjectMemoryListPage,
  type ProjectMemoryMutation,
  type SoftDeletePayload
} from "./projectMemoryModel";

type DataEnvelope<T> = { data: T };

export type CorrectPayload = InvalidatePayload & {
  correctedTitle: string;
  correctedSummary: string;
  correctedContentJson: string;
  correctedContentHash: string;
  newRevisionId: string;
};

export {
  buildConfirmPayload,
  buildInvalidatePayload,
  buildSoftDeletePayload,
  type ConfirmPayload,
  type InvalidatePayload,
  type SoftDeletePayload
};

function memoryBase(projectId: string): string {
  return `/admin/projects/${projectId}/memories`;
}

async function getData<T>(url: string, params?: Record<string, string | number | undefined>): Promise<T> {
  const body = await api.get<DataEnvelope<T>, DataEnvelope<T>>(url, { params });
  return unwrapAdminData<T>(body);
}

async function postData<T>(url: string, payload: unknown): Promise<T> {
  const body = await api.post<DataEnvelope<T>, DataEnvelope<T>>(url, payload);
  return unwrapAdminData<T>(body);
}

export function createGovernanceRequestId(prefix: string): string {
  const token = typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${token}`;
}

export function listProjectMemories(
  projectId: string,
  query: { page?: number; size?: number } = {}
): Promise<ProjectMemoryListPage> {
  return getData<ProjectMemoryListPage>(memoryBase(projectId), {
    page: query.page ?? 1,
    size: query.size ?? 20
  });
}

export function getProjectMemoryDetail(projectId: string, memoryId: string): Promise<ProjectMemoryDetail> {
  return getData<ProjectMemoryDetail>(`${memoryBase(projectId)}/${encodeURIComponent(memoryId)}`);
}

export function confirmProjectMemory(
  projectId: string,
  memoryId: string,
  payload: ConfirmPayload
): Promise<ProjectMemoryMutation> {
  return postData<ProjectMemoryMutation>(
    `${memoryBase(projectId)}/${encodeURIComponent(memoryId)}/confirm`,
    buildConfirmPayload(payload)
  );
}

export function correctProjectMemory(
  projectId: string,
  memoryId: string,
  payload: CorrectPayload
): Promise<ProjectMemoryMutation> {
  return postData<ProjectMemoryMutation>(
    `${memoryBase(projectId)}/${encodeURIComponent(memoryId)}/correct`,
    payload
  );
}

export function invalidateProjectMemory(
  projectId: string,
  memoryId: string,
  payload: InvalidatePayload
): Promise<ProjectMemoryMutation> {
  return postData<ProjectMemoryMutation>(
    `${memoryBase(projectId)}/${encodeURIComponent(memoryId)}/invalidate`,
    buildInvalidatePayload(payload)
  );
}

export function softDeleteProjectMemory(
  projectId: string,
  memoryId: string,
  payload: SoftDeletePayload
): Promise<ProjectMemoryMutation> {
  return postData<ProjectMemoryMutation>(
    `${memoryBase(projectId)}/${encodeURIComponent(memoryId)}/soft-delete`,
    buildSoftDeletePayload(payload)
  );
}
