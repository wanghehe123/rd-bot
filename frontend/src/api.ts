import type {
  AnyRecord,
  KnowledgeBase,
  KnowledgeChunk,
  KnowledgeDocument,
  ManagedUser,
  PageResponse
} from "./types";

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const hasFormData = options.body instanceof FormData;
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(hasFormData ? {} : { "Content-Type": "application/json" }),
      ...(options.headers || {})
    }
  });
  const contentType = response.headers.get("content-type") || "";
  const payload = contentType.includes("application/json") ? await response.json() : await response.text();

  if (!response.ok) {
    const message =
      payload && typeof payload === "object" && "message" in payload
        ? String((payload as AnyRecord).message)
        : response.statusText;
    throw new Error(message || "请求失败");
  }

  return payload as T;
}

const jsonBody = (payload: unknown) => JSON.stringify(payload);
const enc = encodeURIComponent;

export const api = {
  overview: () => request<AnyRecord>("/admin/overview"),
  settings: () => request<AnyRecord>("/rag/settings"),

  listKnowledgeBases: (name = "", size = 100) =>
    request<PageResponse<KnowledgeBase>>(`/knowledge-base?current=1&size=${size}&name=${enc(name)}`),
  createKnowledgeBase: (payload: Pick<KnowledgeBase, "name" | "description">) =>
    request<KnowledgeBase>("/knowledge-base", { method: "POST", body: jsonBody(payload) }),
  getKnowledgeBase: (id: string) => request<KnowledgeBase>(`/knowledge-base/${enc(id)}`),
  updateKnowledgeBase: (id: string, payload: Pick<KnowledgeBase, "name">) =>
    request<KnowledgeBase>(`/knowledge-base/${enc(id)}`, { method: "PUT", body: jsonBody(payload) }),
  deleteKnowledgeBase: (id: string) => request<AnyRecord>(`/knowledge-base/${enc(id)}`, { method: "DELETE" }),

  listDocuments: (kbId: string) => request<KnowledgeDocument[]>(`/knowledge-base/${enc(kbId)}/docs`),
  searchDocuments: (keyword: string, limit = 6) =>
    request<KnowledgeDocument[]>(`/knowledge-base/docs/search?keyword=${enc(keyword)}&limit=${limit}`),
  writeDocument: (kbId: string, payload: AnyRecord) =>
    request<KnowledgeDocument>(`/knowledge-base/${enc(kbId)}/docs/write`, {
      method: "POST",
      body: jsonBody(payload)
    }),
  importFeishuDocument: (kbId: string, payload: AnyRecord) =>
    request<KnowledgeDocument>(`/knowledge-base/${enc(kbId)}/docs/import/feishu`, {
      method: "POST",
      body: jsonBody(payload)
    }),
  uploadDocument: (kbId: string, file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    const query = new URLSearchParams({
      pipelineId: "default-document-pipeline",
      knowledgeBaseId: kbId,
      knowledgeType: "api",
      chunkingMode: "STRUCTURE_AWARE",
      chunkSize: "72",
      overlapSize: "8"
    });
    return request<AnyRecord>(`/ingestion/tasks/upload?${query.toString()}`, { method: "POST", body: formData });
  },
  getDocument: (id: string) => request<KnowledgeDocument>(`/knowledge-base/docs/${enc(id)}`),
  updateDocument: (id: string, payload: AnyRecord) =>
    request<KnowledgeDocument>(`/knowledge-base/docs/${enc(id)}`, { method: "PUT", body: jsonBody(payload) }),
  deleteDocument: (id: string) => request<AnyRecord>(`/knowledge-base/docs/${enc(id)}`, { method: "DELETE" }),
  previewDocument: (id: string) => request<{ content: string }>(`/knowledge-base/docs/${enc(id)}/preview`),
  listChunkLogs: (id: string) => request<AnyRecord[]>(`/knowledge-base/docs/${enc(id)}/chunk-logs`),
  listChunks: (id: string) => request<KnowledgeChunk[]>(`/knowledge-base/docs/${enc(id)}/chunks`),
  createChunk: (docId: string, payload: AnyRecord) =>
    request<KnowledgeChunk>(`/knowledge-base/docs/${enc(docId)}/chunks`, { method: "POST", body: jsonBody(payload) }),
  updateChunk: (docId: string, chunkId: string, payload: AnyRecord) =>
    request<KnowledgeChunk>(`/knowledge-base/docs/${enc(docId)}/chunks/${enc(chunkId)}`, {
      method: "PUT",
      body: jsonBody(payload)
    }),
  deleteChunk: (docId: string, chunkId: string) =>
    request<AnyRecord>(`/knowledge-base/docs/${enc(docId)}/chunks/${enc(chunkId)}`, { method: "DELETE" }),
  setDocumentEnabled: (id: string, enabled: boolean) =>
    request<KnowledgeDocument>(`/knowledge-base/docs/${enc(id)}/enable?value=${enabled}`, {
      method: "PATCH",
      body: "null"
    }),
  setChunkEnabled: (docId: string, chunkId: string, enabled: boolean) =>
    request<KnowledgeChunk>(`/knowledge-base/docs/${enc(docId)}/chunks/${enc(chunkId)}/enable?value=${enabled}`, {
      method: "PATCH",
      body: "null"
    }),
  batchChunks: (docId: string, chunkIds: string[], enabled: boolean) =>
    request<AnyRecord>(`/knowledge-base/docs/${enc(docId)}/chunks/batch-enable?value=${enabled}`, {
      method: "PATCH",
      body: jsonBody({ chunkIds })
    }),

  listUsers: (current = 1, keyword = "") =>
    request<PageResponse<ManagedUser>>(`/users?current=${current}&size=10&keyword=${enc(keyword)}`),
  createUser: (payload: AnyRecord) => request<ManagedUser>("/users", { method: "POST", body: jsonBody(payload) }),
  updateUser: (id: string, payload: AnyRecord) =>
    request<ManagedUser>(`/users/${enc(id)}`, { method: "PUT", body: jsonBody(payload) }),
  deleteUser: (id: string) => request<AnyRecord>(`/users/${enc(id)}`, { method: "DELETE" }),
  changePassword: (payload: AnyRecord) =>
    request<AnyRecord>("/user/password", { method: "PUT", body: jsonBody(payload) })
};
