export type PageResponse<T> = {
  records?: T[];
  items?: T[];
  total?: number;
  size?: number;
  current?: number;
  pages?: number;
  pageNo?: number;
  pageSize?: number;
};

export type AnyRecord = Record<string, any>;

export type KnowledgeBase = {
  id: string;
  name: string;
  description?: string;
  enabled?: boolean;
  documentCount?: number;
  collectionName?: string;
  embeddingModel?: string;
  createTime?: string;
  updateTime?: string;
  createdAtEpochMillis?: number;
};

export type KnowledgeDocument = {
  id: string;
  knowledgeBaseId: string;
  sourceName: string;
  knowledgeType: string;
  mimeType?: string;
  status?: string;
  enabled?: boolean;
  chunkCount?: number;
  createdAtEpochMillis?: number;
  sourceType?: string;
  sourceToken?: string;
  sourceUrl?: string;
  revisionId?: string;
  checksum?: string;
  lastSyncedAtEpochMillis?: number;
  nextRefreshAtEpochMillis?: number;
};

export type KnowledgeChunk = {
  id: string;
  documentId: string;
  knowledgeBaseId: string;
  index: number;
  content: string;
  knowledgeType: string;
  sourceName: string;
  enabled?: boolean;
};

export type ManagedUser = {
  id: string;
  username: string;
  role: string;
  avatar?: string;
  createTime?: string;
  updateTime?: string;
};

export type ToastTone = "success" | "error" | "info";
