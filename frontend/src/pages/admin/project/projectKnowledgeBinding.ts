export const UNBOUND_KNOWLEDGE_BASE_VALUE = "__unbound__";

type ProjectKnowledgeBaseOption = {
  id: string;
  enabled: boolean;
};

export function normalizeProjectKnowledgeBaseId(
  value: string,
  knowledgeBases: ProjectKnowledgeBaseOption[]
): string {
  const knowledgeBaseId = value?.trim() || "";
  if (!knowledgeBaseId || knowledgeBaseId === UNBOUND_KNOWLEDGE_BASE_VALUE) {
    return "";
  }

  const knowledgeBase = knowledgeBases.find((item) => item.id === knowledgeBaseId);
  if (!knowledgeBase) {
    throw new Error("所选知识库不存在，请刷新后重试");
  }
  if (!knowledgeBase.enabled) {
    throw new Error("所选知识库已停用，不能绑定到项目");
  }
  return knowledgeBase.id;
}
