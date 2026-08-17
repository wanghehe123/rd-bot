export type RatioMetric = {
  available: boolean;
  noSample: boolean;
  numerator: number;
  denominator: number;
  sampleCount: number;
  value: number;
};

export type PercentileMetric = {
  available: boolean;
  noSample: boolean;
  sampleCount: number;
  p50Seconds: number;
  p95Seconds: number;
  p99Seconds: number;
  meanSeconds: number;
  p99Insufficient: boolean;
};

export type DataQualityStatus = {
  source: string;
  available: boolean;
  generatedAtEpochMillis: number;
  lastSuccessEpochMillis: number;
  stale: boolean;
  warning: string;
};

export type CapacityMetric = {
  resource: string;
  inUse: number;
  capacity: number;
  saturated: boolean;
};

export type UsageBucket = {
  runtime: string;
  provider: string;
  role: string;
  inputTokens: number;
  outputTokens: number;
  cacheTokens: number;
  estimatedCostCny: number;
};

export type DeliveryOverview = {
  projectId: string;
  window: string;
  windowStartEpochMillis: number;
  windowEndEpochMillis: number;
  generatedAtEpochMillis: number;
  acceptedCount: number;
  terminalCount: number;
  runningCount: number;
  successRate: RatioMetric;
  qaPassRate: RatioMetric;
  prCreationRate: RatioMetric;
  humanInterventionRate: RatioMetric;
  retryRate: RatioMetric;
  unknownFailureRate: RatioMetric;
  endToEndLatency: PercentileMetric;
  phaseLatency: Record<string, PercentileMetric>;
  capacities: CapacityMetric[];
  queueBacklog: number;
  oldestQueueAgeSeconds: number;
  invalidObservations: number;
  dataQuality: DataQualityStatus[];
  usageBuckets: UsageBucket[];
  failureCategories: Record<string, number>;
};

export type DeliveryTimeseries = {
  projectId: string;
  window: string;
  bucketSeconds: number;
  points: Array<{ startEpochMillis: number; accepted: number; success: number; failure: number }>;
  dataQuality: DataQualityStatus[];
};

export type DeliveryFailurePage = {
  projectId: string;
  window: string;
  page: number;
  pageSize: number;
  total: number;
  items: Array<{ category: string; count: number; share: number }>;
  dataQuality: DataQualityStatus[];
};

export type DeliveryTaskPage = {
  projectId: string;
  window: string;
  page: number;
  pageSize: number;
  total: number;
  items: Array<{
    taskId: string;
    title: string;
    projectId: string;
    status: string;
    role: string;
    durationSeconds: number;
    failureCategory: string;
    tracePath: string;
    taskPath: string;
  }>;
  dataQuality: DataQualityStatus[];
};

export type DeliveryQuery = {
  projectId?: string;
  window?: string;
  role?: string;
  runtime?: string;
  provider?: string;
  failureCategory?: string;
  page?: number;
  pageSize?: number;
};

export function serializeDeliveryQuery(query: DeliveryQuery = {}): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  const projectId = query.projectId?.trim();
  if (projectId && projectId !== "all") {
    params.projectId = projectId;
  }
  if (query.window?.trim()) {
    params.window = query.window.trim();
  }
  if (query.role?.trim()) {
    params.role = query.role.trim();
  }
  if (query.runtime?.trim()) {
    params.runtime = query.runtime.trim();
  }
  if (query.provider?.trim()) {
    params.provider = query.provider.trim();
  }
  if (query.failureCategory?.trim()) {
    params.failureCategory = query.failureCategory.trim();
  }
  if (query.page && query.page > 0) {
    params.page = query.page;
  }
  if (query.pageSize && query.pageSize > 0) {
    params.pageSize = query.pageSize;
  }
  return params;
}
