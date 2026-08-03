import { api } from "@/services/api";

/** Skill 目录状态，对齐后端 SkillCatalogStatus。 */
export type SkillCatalogStatus = "ACTIVE" | "WAITING_APPROVAL" | "DISABLED" | "REJECTED";

/** Skill 风险等级，对齐后端 SkillRiskLevel。 */
export type SkillRiskLevel = "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN";

/** Agent 角色，对齐 Skill Hub 绑定角色。 */
export type SkillAgentRole =
  | "REQUIREMENT_REVIEWER"
  | "SOLUTION_ARCHITECT"
  | "CODING_AGENT"
  | "QA_AGENT";

/** Skill 目录条目，对齐后端 SkillCatalogEntry。 */
export interface SkillCatalogEntry {
  skillId: string;
  version: string;
  description: string;
  guidePrompt: string;
  forceGuide: boolean;
  riskLevel: SkillRiskLevel;
  checksum: string;
  sourceUri: string;
  installPath: string;
  status: SkillCatalogStatus;
  allowedRoles: string[];
  updatedAt: string;
}

/** 角色绑定，对齐后端 SkillRoleBinding。 */
export interface SkillRoleBinding {
  role: string;
  skillId: string;
  sortOrder: number;
  forceGuide: boolean;
}

/** 角色绑定写入项，对齐 RoleBindingRequest。 */
export interface SkillRoleBindingPayload {
  skillId: string;
  sortOrder: number;
  forceGuide: boolean;
}

/** Skill 元数据更新，对齐 SkillUpdateRequest。 */
export interface SkillUpdatePayload {
  guidePrompt?: string;
  forceGuide?: boolean;
  allowedRoles?: string[];
  status?: SkillCatalogStatus;
  description?: string;
}

/** 上传可选字段。 */
export interface SkillUploadOptions {
  version?: string;
  riskLevel?: SkillRiskLevel | string;
  allowedRoles?: string;
  guidePrompt?: string;
  forceGuide?: boolean;
}

export const SKILL_AGENT_ROLES: SkillAgentRole[] = [
  "REQUIREMENT_REVIEWER",
  "SOLUTION_ARCHITECT",
  "CODING_AGENT",
  "QA_AGENT"
];

export const listSkillCatalog = (): Promise<SkillCatalogEntry[]> =>
  api.get<SkillCatalogEntry[], SkillCatalogEntry[]>("/admin/skills");

export const getSkill = (skillId: string): Promise<SkillCatalogEntry> =>
  api.get<SkillCatalogEntry, SkillCatalogEntry>(`/admin/skills/${encodeURIComponent(skillId)}`);

export const updateSkill = (skillId: string, payload: SkillUpdatePayload): Promise<SkillCatalogEntry> =>
  api.put<SkillCatalogEntry, SkillCatalogEntry>(`/admin/skills/${encodeURIComponent(skillId)}`, payload);

export const approveSkill = (skillId: string): Promise<SkillCatalogEntry> =>
  api.post<SkillCatalogEntry, SkillCatalogEntry>(`/admin/skills/${encodeURIComponent(skillId)}/approve`);

export const listSkillRoleBindings = (): Promise<Record<string, SkillRoleBinding[]>> =>
  api.get<Record<string, SkillRoleBinding[]>, Record<string, SkillRoleBinding[]>>("/admin/skills/role-bindings");

export const replaceSkillRoleBindings = (
  role: string,
  bindings: SkillRoleBindingPayload[]
): Promise<SkillRoleBinding[]> =>
  api.put<SkillRoleBinding[], SkillRoleBinding[]>(
    `/admin/skills/role-bindings/${encodeURIComponent(role)}`,
    bindings
  );

export const uploadSkill = (file: File, options: SkillUploadOptions = {}): Promise<SkillCatalogEntry> => {
  const formData = new FormData();
  formData.append("file", file);
  if (options.version?.trim()) formData.append("version", options.version.trim());
  if (options.riskLevel) formData.append("riskLevel", String(options.riskLevel));
  if (options.allowedRoles?.trim()) formData.append("allowedRoles", options.allowedRoles.trim());
  if (options.guidePrompt != null) formData.append("guidePrompt", options.guidePrompt);
  if (options.forceGuide != null) formData.append("forceGuide", String(options.forceGuide));
  return api.post<SkillCatalogEntry, SkillCatalogEntry>("/admin/skills/upload", formData, {
    headers: {
      "Content-Type": "multipart/form-data"
    }
  });
};
