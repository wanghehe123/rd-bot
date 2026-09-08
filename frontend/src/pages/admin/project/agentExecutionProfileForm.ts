export type AgentProfileProviderOption = {
  providerId?: string;
  enabled?: boolean;
};

export function piSupportsRole(role: string): boolean {
  return role === "REQUIREMENT_REVIEWER"
    || role === "SOLUTION_ARCHITECT"
    || role === "CODING_AGENT"
    || role === "QA_AGENT";
}

export function firstEnabledProviderId(
  providers: AgentProfileProviderOption[],
  currentId = ""
): string {
  const list = Array.isArray(providers) ? providers : [];
  const current = currentId.trim();
  if (current && list.some((item) => item.enabled && item.providerId === current)) {
    return current;
  }
  const first = list.find((item) => item.enabled && typeof item.providerId === "string" && item.providerId.trim());
  return first?.providerId?.trim() || "";
}

export function agentProfileSaveError(input: {
  mutationToken: string;
  profileId: string;
  name: string;
  providerProfileId: string;
  toolPolicyId: string;
}): string | null {
  if (!input.mutationToken.trim()) return "请输入 Agent 运行时操作令牌";
  if (!input.profileId.trim()) return "请填写 Profile ID";
  if (!input.name.trim()) return "请填写名称";
  if (!input.providerProfileId.trim()) return "请选择 Provider Profile";
  if (!input.toolPolicyId.trim()) return "请填写 Tool Policy";
  return null;
}

export function agentRuntimeMutationError(error: unknown): string | null {
  const status = httpStatus(error);
  if (status === 403) {
    return "操作令牌不正确。请输入部署环境配置的 RD_AGENT_RUNTIME_MUTATION_TOKEN";
  }
  if (status === 503) {
    return "后端未配置 Agent 运行时操作令牌（RD_AGENT_RUNTIME_MUTATION_TOKEN）";
  }
  return null;
}

function httpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== "object") return undefined;
  const response = (error as { response?: { status?: unknown } }).response;
  const status = response?.status;
  return typeof status === "number" ? status : undefined;
}

export function runtimeImageDialogHint(runtimeTypes: string[]): string {
  const types = Array.isArray(runtimeTypes) ? runtimeTypes : [];
  if (types.includes("PI")) {
    return "该角色已注册 Pi Agent。Pi 使用全局镜像 rd-bot/pi-agent:local，不能在此更换。这里只上传 Claude Code 的 Dockerfile；「构建并启用」在选中 Dockerfile 并填写运行镜像令牌后才会亮起。";
  }
  return "这里只配置 Claude Code 的角色级 Dockerfile，不能改成 Pi。要让该角色跑 Pi，请关闭此窗，打开「Agent 执行策略」注册并保存。";
}
