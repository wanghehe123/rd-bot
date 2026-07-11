import { api } from "@/services/api";

export type RetrievalRuleScope = "GLOBAL" | "PROJECT";

export type RetrievalRule = {
  id: string;
  projectId: string;
  scope: RetrievalRuleScope;
  sourceTerm: string;
  targetTerm: string;
  priority: number;
  enabled: boolean;
  remark: string;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
};

export type RetrievalRuleQuery = {
  projectId?: string;
  scope?: RetrievalRuleScope;
  enabled?: boolean;
  keyword?: string;
};

export type RetrievalRuleCommand = {
  projectId: string;
  scope: RetrievalRuleScope;
  sourceTerm: string;
  targetTerm: string;
  priority: number;
  enabled: boolean;
  remark: string;
};

export type QueryRewriteMatch = {
  mappingId: string;
  sourceTerm: string;
  targetTerm: string;
  scope: RetrievalRuleScope;
};

export type QueryRewritePreview = {
  originalText: string;
  rewrittenText: string;
  matches: QueryRewriteMatch[];
};

export function getRetrievalRules(query: RetrievalRuleQuery = {}): Promise<RetrievalRule[]> {
  return api.get<RetrievalRule[], RetrievalRule[]>("/mappings", {
    params: {
      projectId: query.projectId?.trim() || undefined,
      scope: query.scope,
      enabled: query.enabled,
      keyword: query.keyword?.trim() || undefined
    }
  });
}

export const createRetrievalRule = (command: RetrievalRuleCommand): Promise<RetrievalRule> =>
  api.post<RetrievalRule, RetrievalRule>("/mappings", command);

export const updateRetrievalRule = (id: string, command: RetrievalRuleCommand): Promise<RetrievalRule> =>
  api.put<RetrievalRule, RetrievalRule>(`/mappings/${id}`, command);

export const deleteRetrievalRule = (id: string): Promise<{ deleted: boolean }> =>
  api.delete<{ deleted: boolean }, { deleted: boolean }>(`/mappings/${id}`);

export const previewRetrievalRuleRewrite = (
  projectId: string | undefined,
  text: string
): Promise<QueryRewritePreview> =>
  api.post<QueryRewritePreview, QueryRewritePreview>("/mappings/preview", {
    projectId: projectId?.trim() || "",
    text
  });
