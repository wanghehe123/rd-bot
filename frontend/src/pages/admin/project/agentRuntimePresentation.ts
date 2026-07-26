type RuntimeEventLike = {
  eventType?: string;
  payload?: Record<string, unknown>;
};

const EVENT_LABELS: Record<string, string> = {
  RUNTIME_READY: "运行时就绪",
  AGENT_STARTED: "Agent 已启动",
  AGENT_SETTLED: "Agent 已完成",
  RUNTIME_STOPPED: "运行时已停止",
  TURN_STARTED: "回合开始",
  TURN_COMPLETED: "回合完成",
  ASSISTANT_TEXT_DELTA: "模型输出",
  ASSISTANT_TEXT_COMPLETED: "模型输出完成",
  TOOL_STARTED: "工具开始",
  TOOL_PROGRESS: "工具进展",
  TOOL_COMPLETED: "工具完成",
  TOOL_BLOCKED: "工具被阻止",
  PROVIDER_REQUESTED: "Provider 请求",
  PROVIDER_RESPONDED: "Provider 响应",
  PROVIDER_RETRYING: "Provider 重试",
  COMPACTION_STARTED: "上下文压缩开始",
  COMPACTION_COMPLETED: "上下文压缩完成",
  RESOURCES_LOADED: "扩展资源已加载",
  EXTENSION_FAILED: "扩展加载失败",
  USAGE_UPDATED: "用量更新",
  RESULT_SUBMITTED: "结果已提交",
  RESULT_REJECTED: "结果被拒绝",
  ARTIFACT_WRITTEN: "产物已写入",
  PROTOCOL_ERROR: "协议错误"
};

export function runtimeEventLabel(event: RuntimeEventLike): string {
  const type = event.eventType?.trim() || "AGENT_EVENT";
  return EVENT_LABELS[type] || type;
}

export function runtimeEventDetail(event: RuntimeEventLike): string {
  const payload = event.payload || {};
  for (const key of ["toolName", "delta", "summary", "error", "reason", "status", "stopReason"]) {
    const value = payload[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  if (typeof payload.attempt === "number") {
    const maxAttempts = typeof payload.maxAttempts === "number" ? `/${payload.maxAttempts}` : "";
    return `第 ${payload.attempt}${maxAttempts} 次`;
  }
  return "";
}

export type SnapshotFieldSource = Record<string, unknown>;

export function snapshotFields(snapshot: SnapshotFieldSource): Record<string, string> {
  const value = (key: string) => {
    const raw = snapshot[key];
    return raw === undefined || raw === null ? "" : String(raw).trim();
  };
  const versioned = (idKey: string, versionKey: string) => {
    const id = value(idKey);
    const version = value(versionKey);
    return id && version && version !== "0" ? `${id}@${version}` : id;
  };
  return {
    runtime: value("runtimeType") || "-",
    provider: value("providerProfileId") || "-",
    model: value("modelOverride") || value("providerModelId") || "-",
    protocol: value("providerProtocol") || "-",
    toolPolicy: versioned("toolPolicyId", "toolPolicyVersion") || "-",
    extensionSet: versioned("extensionSetId", "extensionSetVersion") || "未启用",
    resolvedFrom: value("resolvedFrom") || "-",
    credentialVariable: value("credentialEnvironmentVariable") || "-"
  };
}
