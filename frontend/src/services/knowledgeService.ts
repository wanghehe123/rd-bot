import { api } from "@/services/api";

/** 分页结果，对齐后端 PageResponse<T>。 */
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/** 知识库视图，对齐后端 KnowledgeBaseView。 */
export interface KnowledgeBase {
  id: string;
  name: string;
  description?: string | null;
  enabled: boolean;
  createdAtEpochMillis?: number;
  documentCount: number;
  embeddingModel?: string | null;
  collectionName?: string | null;
  createdBy?: string | null;
  createTime?: string;
  updateTime?: string;
}

/**
 * 文档，对齐后端 KnowledgeDocument。
 * docName/fileType/fileSize/updateTime/updatedBy/processMode/chunkStrategy/pipelineId/
 * chunksEdited 为前端派生字段，供 ragent 文档页直接使用。
 */
export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  sourceName: string;
  knowledgeType: string;
  mimeType: string;
  status: string;
  enabled: boolean;
  chunkCount: number;
  nodeLogs?: KnowledgeDocumentChunkLog[];
  createdAtEpochMillis: number;
  sourceType?: string | null;
  sourceToken?: string | null;
  sourceUrl?: string | null;
  revisionId?: string | null;
  checksum?: string | null;
  rawPreview?: string | null;
  lastSyncedAtEpochMillis?: number;
  nextRefreshAtEpochMillis?: number;
  /** ragent 页面使用的文档名称别名，等于 sourceName。 */
  docName?: string;
  /** 从 mimeType/文件名派生，如 markdown/pdf/txt。 */
  fileType?: string;
  /** 文件大小（后端当前不返回，留空时页面显示 -）。 */
  fileSize?: number | null;
  /** 派生更新时间，ISO 字符串。 */
  updateTime?: string;
  updatedBy?: string | null;
  processMode?: string;
  chunkStrategy?: string;
  pipelineId?: string;
  chunksEdited?: boolean;
  /** 编辑对话框读取的来源地址，RD-Bot 不返回，留空。 */
  sourceLocation?: string;
  /** 定时拉取开关（0/1），RD-Bot 不返回，默认 0。 */
  scheduleEnabled?: number;
  /** 定时拉取 CRON，RD-Bot 不返回，留空。 */
  scheduleCron?: string;
  /** 分块配置 JSON，RD-Bot 不返回，留空。 */
  chunkConfig?: string;
}

/**
 * 分块，对齐后端 KnowledgeChunk。
 * chunkIndex/charCount/tokenCount/updateTime 为前端派生字段，供 ragent 页面直接使用。
 */
export interface KnowledgeChunk {
  id: string;
  documentId: string;
  knowledgeBaseId: string;
  index: number;
  content: string;
  knowledgeType: string;
  sourceName: string;
  enabled: number;
  metadata?: Record<string, string>;
  /** 与 index 同义，ragent 页面用此字段渲染序号。 */
  chunkIndex?: number;
  charCount?: number;
  tokenCount?: number;
  updateTime?: string;
}

/**
 * 摄取节点日志，对齐后端 IngestionNodeLog。
 * ragent 文档页的分块详情面板额外读取 status/processMode/duration 等字段，
 * RD-Bot 后端不返回这些字段，保留为可选以兼容页面渲染（缺失时显示 -）。
 */
export interface KnowledgeDocumentChunkLog {
  nodeType: string;
  message: string;
  durationMs: number;
  success: boolean;
  id?: string;
  status?: string;
  processMode?: string;
  chunkStrategy?: string;
  pipelineName?: string;
  pipelineId?: string;
  chunkCount?: number | null;
  extractDuration?: number | null;
  chunkDuration?: number | null;
  embedDuration?: number | null;
  persistDuration?: number | null;
  otherDuration?: number | null;
  totalDuration?: number | null;
  startTime?: string;
  endTime?: string;
  errorMessage?: string;
}

/** 分块策略选项，对齐后端 ChunkStrategyOption。 */
export interface ChunkStrategyOption {
  value: string;
  label: string;
  defaultConfig: Record<string, number>;
}

/** 创建知识库请求体。后端只取 name/description，其余字段忽略。 */
export interface CreateKnowledgeBasePayload {
  name: string;
  description?: string;
  embeddingModel?: string;
  collectionName?: string;
}

export interface UpdateKnowledgeBasePayload {
  name: string;
}

/** 文档更新请求体，对齐后端 KnowledgeDocumentUpdateRequest。 */
export interface KnowledgeDocumentUpdatePayload {
  docName?: string;
  knowledgeType?: string;
  processMode?: string;
  chunkStrategy?: string;
  chunkConfig?: string;
  pipelineId?: string;
  sourceLocation?: string;
  scheduleEnabled?: number;
  scheduleCron?: string;
}

/** 文档上传载荷，对齐后端 /knowledge-base/{kbId}/docs/upload multipart 字段。 */
export interface KnowledgeDocumentUploadPayload {
  file: File;
  knowledgeType?: string;
  chunkStrategy?: string;
  chunkConfig?: string;
  pipelineId?: string;
}

/** 分块新增载荷。 */
export interface KnowledgeChunkCreatePayload {
  content: string;
  index?: number | null;
}

// ----------------------------------------------------------------------------
// 知识库
// ----------------------------------------------------------------------------

export async function getKnowledgeBases(current = 1, size = 10, name?: string) {
  return api.get<PageResult<KnowledgeBase>, PageResult<KnowledgeBase>>("/knowledge-base", {
    params: { current, size, name: name || undefined }
  });
}

/** 分页查询知识库，保持 ragent 页面所用的函数名。 */
export async function getKnowledgeBasesPage(current = 1, size = 10, name?: string) {
  return getKnowledgeBases(current, size, name);
}

export async function getKnowledgeBase(id: string) {
  return api.get<KnowledgeBase, KnowledgeBase>(`/knowledge-base/${id}`);
}

export async function createKnowledgeBase(payload: CreateKnowledgeBasePayload) {
  return api.post<KnowledgeBase, KnowledgeBase>("/knowledge-base", payload);
}

export async function updateKnowledgeBase(id: string, payload: UpdateKnowledgeBasePayload) {
  return api.put<KnowledgeBase, KnowledgeBase>(`/knowledge-base/${id}`, payload);
}

/** 重命名知识库，保持 ragent 页面所用的函数名。 */
export async function renameKnowledgeBase(id: string, name: string) {
  return updateKnowledgeBase(id, { name });
}

export async function deleteKnowledgeBase(id: string) {
  await api.delete(`/knowledge-base/${id}`);
}

// ----------------------------------------------------------------------------
// 文档
// ----------------------------------------------------------------------------

export interface DocumentListQuery {
  current: number;
  size: number;
  status?: string;
  keyword?: string;
}

export async function getDocumentsPage(knowledgeBaseId: string, query: DocumentListQuery) {
  const data = await api.get<PageResult<KnowledgeDocument>, PageResult<KnowledgeDocument>>(
    `/knowledge-base/${knowledgeBaseId}/docs`,
    { params: query }
  );
  return normalizeDocumentPage(data);
}

export async function getDocument(documentId: string) {
  const data = await api.get<KnowledgeDocument, KnowledgeDocument>(`/knowledge-base/docs/${documentId}`);
  return normalizeDocument(data);
}

export async function updateDocument(documentId: string, payload: KnowledgeDocumentUpdatePayload) {
  const data = await api.put<KnowledgeDocument, KnowledgeDocument>(
    `/knowledge-base/docs/${documentId}`,
    payload
  );
  return normalizeDocument(data);
}

export async function deleteDocument(documentId: string) {
  await api.delete(`/knowledge-base/docs/${documentId}`);
}

export async function enableDocument(documentId: string, enabled: boolean) {
  const data = await api.patch<KnowledgeDocument, KnowledgeDocument>(
    `/knowledge-base/docs/${documentId}/enabled`,
    undefined,
    { params: { enabled } }
  );
  return normalizeDocument(data);
}

export async function startDocumentChunk(documentId: string) {
  const data = await api.post<KnowledgeDocument, KnowledgeDocument>(
    `/knowledge-base/docs/${documentId}/chunk`,
    {}
  );
  return normalizeDocument(data);
}

export async function getChunkStrategies() {
  return api.get<ChunkStrategyOption[], ChunkStrategyOption[]>(
    "/knowledge-base/chunk-strategies"
  );
}

export async function uploadDocument(knowledgeBaseId: string, payload: KnowledgeDocumentUploadPayload) {
  const formData = new FormData();
  formData.append("file", payload.file);
  if (payload.knowledgeType) formData.append("knowledgeType", payload.knowledgeType);
  if (payload.chunkStrategy) {
    formData.append("chunkingMode", payload.chunkStrategy);
  }
  const config = parseChunkConfigForUpload(payload.chunkConfig);
  formData.append("chunkSize", String(config.chunkSize));
  formData.append("overlapSize", String(config.overlapSize));
  const data = await api.post<KnowledgeDocument, KnowledgeDocument>(
    `/knowledge-base/${knowledgeBaseId}/docs/upload`,
    formData,
    { headers: { "Content-Type": "multipart/form-data" } }
  );
  return normalizeDocument(data);
}

export async function previewDocument(documentId: string) {
  const response = await api.get<{ content: string }, { content: string }>(
    `/knowledge-base/docs/${documentId}/preview`
  );
  return response.content ?? "";
}

export async function getChunkLogsPage(documentId: string, current = 1, size = 10) {
  const data = await api.get<PageResult<KnowledgeDocumentChunkLog>, PageResult<KnowledgeDocumentChunkLog>>(
    `/knowledge-base/docs/${documentId}/chunk-logs`,
    { params: { current, size } }
  );
  return normalizeChunkLogPage(data);
}

// ----------------------------------------------------------------------------
// 分块
// ----------------------------------------------------------------------------

export interface ChunkListQuery {
  current: number;
  size: number;
  /** 0/1/all；也可传 boolean，会在请求时规范化为 boolean。 */
  enabled?: boolean | number;
}

export async function getChunksPage(documentId: string, query: ChunkListQuery) {
  const enabled =
    query.enabled === undefined
      ? undefined
      : typeof query.enabled === "boolean"
        ? query.enabled
        : query.enabled === 1;
  const data = await api.get<PageResult<KnowledgeChunk>, PageResult<KnowledgeChunk>>(
    `/knowledge-base/docs/${documentId}/chunks`,
    { params: { current: query.current, size: query.size, enabled } }
  );
  return normalizeChunkPage(data);
}

export async function createChunk(documentId: string, payload: KnowledgeChunkCreatePayload) {
  const data = await api.post<KnowledgeChunk, KnowledgeChunk>(
    `/knowledge-base/docs/${documentId}/chunks`,
    payload
  );
  return normalizeChunk(data);
}

export async function updateChunk(documentId: string, chunkId: string, payload: { content: string }) {
  const data = await api.put<KnowledgeChunk, KnowledgeChunk>(
    `/knowledge-base/docs/${documentId}/chunks/${chunkId}`,
    payload
  );
  return normalizeChunk(data);
}

export async function deleteChunk(documentId: string, chunkId: string) {
  await api.delete(`/knowledge-base/docs/${documentId}/chunks/${chunkId}`);
}

export async function toggleChunk(documentId: string, chunkId: string, enabled: boolean) {
  const data = await api.patch<KnowledgeChunk, KnowledgeChunk>(
    `/knowledge-base/docs/${documentId}/chunks/${chunkId}/enable`,
    undefined,
    { params: { value: enabled } }
  );
  return normalizeChunk(data);
}

export async function batchToggleChunks(
  documentId: string,
  enabled: boolean,
  chunkIds: string[]
) {
  return api.patch<{ updated: number }, { updated: number }>(
    `/knowledge-base/docs/${documentId}/chunks/batch-enable`,
    { chunkIds },
    { params: { value: enabled } }
  );
}

// ----------------------------------------------------------------------------
// 内部工具
// ----------------------------------------------------------------------------

/**
 * 将后端 KnowledgeDocument 规范化为 ragent 文档页所需形态：
 * 补齐 docName/fileType/updateTime/processMode 等派生字段。
 */
function normalizeDocument(raw: KnowledgeDocument): KnowledgeDocument {
  const sourceName = raw.sourceName ?? "";
  return {
    ...raw,
    docName: raw.docName ?? sourceName,
    fileType: raw.fileType ?? deriveFileType(raw.mimeType, sourceName),
    fileSize: raw.fileSize ?? null,
    updateTime: raw.updateTime ?? (raw.createdAtEpochMillis ? new Date(raw.createdAtEpochMillis).toISOString() : undefined),
    updatedBy: raw.updatedBy ?? null,
    processMode: raw.processMode ?? "chunk",
    chunkStrategy: raw.chunkStrategy ?? undefined,
    pipelineId: raw.pipelineId ?? undefined,
    chunksEdited: Boolean(raw.chunksEdited)
  };
}

function normalizeDocumentPage(data: PageResult<KnowledgeDocument>): PageResult<KnowledgeDocument> {
  return {
    ...data,
    records: (data.records || []).map(normalizeDocument)
  };
}

/** 根据 mimeType 或文件名后缀推断文件类型标签。 */
function deriveFileType(mimeType?: string, fileName?: string): string {
  const name = (fileName || "").toLowerCase();
  const mime = (mimeType || "").toLowerCase();
  if (mime.includes("markdown") || name.endsWith(".md") || name.endsWith(".markdown")) return "markdown";
  if (mime.includes("pdf") || name.endsWith(".pdf")) return "pdf";
  if (mime.includes("html") || name.endsWith(".html") || name.endsWith(".htm")) return "html";
  if (mime.includes("csv") || name.endsWith(".csv")) return "csv";
  if (mime.includes("word") || name.endsWith(".doc") || name.endsWith(".docx")) return "docx";
  if (mime.includes("text") || name.endsWith(".txt")) return "txt";
  if (mime.includes("json") || name.endsWith(".json")) return "json";
  const ext = name.split(".").pop();
  return ext && ext.length <= 6 ? ext : "txt";
}



/**
 * 将后端 KnowledgeChunk（enabled 为 boolean）规范化为 ragent 页面所需形态：
 * enabled 转为 0/1，补齐 chunkIndex/charCount/tokenCount/updateTime 派生字段。
 */
function normalizeChunk(raw: KnowledgeChunk): KnowledgeChunk {
  const content = raw.content ?? "";
  const enabledBoolean = raw.enabled as unknown;
  const enabledNumber = enabledBoolean === true || enabledBoolean === 1 ? 1 : 0;
  return {
    ...raw,
    enabled: enabledNumber,
    chunkIndex: raw.index ?? raw.chunkIndex ?? 0,
    charCount: content.length,
    tokenCount: Math.max(1, Math.round(content.length / 4)),
    updateTime: raw.updateTime ?? new Date().toISOString()
  };
}

function normalizeChunkPage(data: PageResult<KnowledgeChunk>): PageResult<KnowledgeChunk> {
  return {
    ...data,
    records: (data.records || []).map(normalizeChunk)
  };
}

/**
 * 将后端节点日志（FETCHER/PARSER/CHUNKER/INDEXER + durationMs/success）
 * 汇总成 ragent 分块详情面板所需的一条聚合记录。
 * 节点耗时按类型映射到 extract/chunk/embed/persist 字段。
 */
function normalizeChunkLogPage(data: PageResult<KnowledgeDocumentChunkLog>): PageResult<KnowledgeDocumentChunkLog> {
  const logs = data.records || [];
  if (logs.length === 0) {
    return data;
  }
  let extractDuration = 0;
  let chunkDuration = 0;
  let embedDuration = 0;
  let persistDuration = 0;
  let anyFailed = false;
  let errorMessage = "";
  for (const log of logs) {
    const nodeType = (log.nodeType || "").toUpperCase();
    const duration = typeof log.durationMs === "number" ? log.durationMs : 0;
    if (nodeType === "FETCHER" || nodeType === "PARSER") {
      extractDuration += duration;
    } else if (nodeType === "CHUNKER") {
      chunkDuration += duration;
    } else if (nodeType === "INDEXER") {
      embedDuration += duration;
      persistDuration += duration;
    }
    if (log.success === false) {
      anyFailed = true;
      errorMessage = errorMessage || log.message || "";
    }
  }
  const totalDuration = extractDuration + chunkDuration + embedDuration;
  const aggregated: KnowledgeDocumentChunkLog = {
    id: "summary",
    nodeType: "summary",
    message: errorMessage || "",
    durationMs: totalDuration,
    success: !anyFailed,
    status: anyFailed ? "failed" : "success",
    processMode: "chunk",
    chunkDuration,
    extractDuration,
    embedDuration,
    persistDuration,
    otherDuration: 0,
    totalDuration,
    errorMessage: errorMessage || undefined
  };
  return {
    ...data,
    records: [aggregated]
  };
}

/** 从上传表单的 chunkConfig JSON 解析出后端所需的 chunkSize/overlapSize。 */
function parseChunkConfigForUpload(chunkConfig?: string): { chunkSize: number; overlapSize: number } {
  const fallback = { chunkSize: 512, overlapSize: 64 };
  if (!chunkConfig) return fallback;
  try {
    const parsed = JSON.parse(chunkConfig);
    if (parsed && typeof parsed === "object") {
      const obj = parsed as Record<string, unknown>;
      const chunkSize = typeof obj.chunkSize === "number" && obj.chunkSize > 0
        ? obj.chunkSize
        : fallback.chunkSize;
      const overlapSize = typeof obj.overlapSize === "number"
        ? obj.overlapSize
        : fallback.overlapSize;
      return { chunkSize, overlapSize };
    }
  } catch {
    // ignore
  }
  return fallback;
}
