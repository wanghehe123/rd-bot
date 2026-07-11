export type RuleSummaryItem = {
  scope: "GLOBAL" | "PROJECT";
  enabled: boolean;
};

export function scopeForNewRule(projectId: string): "GLOBAL" | "PROJECT" {
  return projectId.trim() && projectId !== "all" ? "PROJECT" : "GLOBAL";
}

export function retrievalRuleSummary(rules: readonly RuleSummaryItem[]) {
  const global = rules.filter((rule) => rule.scope === "GLOBAL").length;
  const enabled = rules.filter((rule) => rule.enabled).length;
  return {
    total: rules.length,
    global,
    project: rules.length - global,
    enabled,
    disabled: rules.length - enabled
  };
}

export function formatRuleTime(epochMillis: number): string {
  if (!Number.isFinite(epochMillis) || epochMillis <= 0) return "--";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(epochMillis));
}
