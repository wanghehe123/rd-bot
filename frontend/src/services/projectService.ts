import { api } from "@/services/api";

export interface RdProject {
  projectId: string;
  projectKey: string;
  name: string;
  description: string;
  repositoryUrl: string;
  repoOwner: string;
  repoName: string;
  defaultBranch: string;
  enabled: boolean;
  createTimeEpochMillis: number;
  updateTimeEpochMillis: number;
}

export interface RdProjectPage {
  records: RdProject[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
}

export interface RdProjectListQuery {
  keyword?: string;
  enabled?: boolean;
  page?: number;
  pageSize?: number;
}

export interface RdProjectPayload {
  projectKey: string;
  name: string;
  description?: string;
  repositoryUrl: string;
  repoOwner?: string;
  repoName?: string;
  defaultBranch: string;
  enabled: boolean;
}

export const getProjectsPage = (query: RdProjectListQuery = {}): Promise<RdProjectPage> =>
  api.get<RdProjectPage, RdProjectPage>("/admin/projects", {
    params: {
      keyword: query.keyword || undefined,
      enabled: query.enabled,
      page: query.page ?? 1,
      pageSize: query.pageSize ?? 20
    }
  });

export const createProject = (payload: RdProjectPayload): Promise<RdProject> =>
  api.post<RdProject, RdProject>("/admin/projects", payload);

export const updateProject = (projectId: string, payload: RdProjectPayload): Promise<RdProject> =>
  api.put<RdProject, RdProject>(`/admin/projects/${projectId}`, payload);

export const deleteProject = (projectId: string): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(`/admin/projects/${projectId}`);
