export const DELIVERY_ROLES = [
  "REQUIREMENT_REVIEWER",
  "SOLUTION_ARCHITECT",
  "CODING_AGENT",
  "QA_AGENT"
] as const;

export type DeliveryRole = (typeof DELIVERY_ROLES)[number];

export const ROLE_LABELS: Record<DeliveryRole, string> = {
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证"
};

export type StrategyRuntimeType = "PI" | "CLAUDE_CODE" | "MODEL_ONLY";
export type StrategyImageMode = "LOCAL_DEFAULT" | "CUSTOM";

export const RUNTIME_OPTIONS: Array<{ value: StrategyRuntimeType; label: string }> = [
  { value: "PI", label: "Pi Agent" },
  { value: "CLAUDE_CODE", label: "Claude Code" },
  { value: "MODEL_ONLY", label: "仅模型" }
];

export type StrategySlotDraft = {
  role: DeliveryRole;
  runtimeType: StrategyRuntimeType;
  providerProfileId: string;
  modelOverride: string;
  extensionSetId: string;
  extensionSetVersion: number;
  toolPolicyId: string;
  toolPolicyVersion: number;
  imageMode: StrategyImageMode;
  image: string;
  dockerfileName: string;
  dockerfileSha256: string;
  dockerfileArtifactUri: string;
  dockerfileText: string;
  pendingDockerfile?: File | null;
};

export type StrategyDraft = {
  strategyId: string;
  name: string;
  enabled: boolean;
  version: number;
  bindAsDefault: boolean;
  mutationToken: string;
  roles: StrategySlotDraft[];
};

export type StrategySaveErrors = {
  form?: string;
  roles: Partial<Record<DeliveryRole, string>>;
};

export type DefaultImages = {
  defaultPiImage: string;
  defaultPiQaImage: string;
  defaultClaudeImage: string;
};

export function emptySlot(role: DeliveryRole, providerId = ""): StrategySlotDraft {
  return {
    role,
    runtimeType: "PI",
    providerProfileId: providerId,
    modelOverride: "",
    extensionSetId: "",
    extensionSetVersion: 0,
    toolPolicyId: "legacy-host-bound",
    toolPolicyVersion: 1,
    imageMode: "LOCAL_DEFAULT",
    image: "",
    dockerfileName: "",
    dockerfileSha256: "",
    dockerfileArtifactUri: "",
    dockerfileText: "",
    pendingDockerfile: null
  };
}

export function defaultStrategyDraft(providerId = ""): StrategyDraft {
  return {
    strategyId: "default",
    name: "默认策略",
    enabled: true,
    version: 1,
    bindAsDefault: true,
    mutationToken: "",
    roles: DELIVERY_ROLES.map((role) => emptySlot(role, providerId))
  };
}

export function localDefaultImageLabel(
  runtimeType: StrategyRuntimeType,
  role: string,
  images: DefaultImages
): string {
  if (runtimeType === "MODEL_ONLY") return "";
  if (runtimeType === "CLAUDE_CODE") return images.defaultClaudeImage || "rd-bot/claude-code:local";
  if (role === "QA_AGENT") return images.defaultPiQaImage || "rd-bot/pi-agent-qa:local";
  return images.defaultPiImage || "rd-bot/pi-agent:local";
}

export function agentStrategySaveError(draft: StrategyDraft): StrategySaveErrors | null {
  const roles: Partial<Record<DeliveryRole, string>> = {};
  let form: string | undefined;
  if (!draft.mutationToken.trim()) form = "请输入 Agent 运行时操作令牌";
  else if (!draft.strategyId.trim()) form = "请填写策略 ID";
  else if (draft.strategyId.trim().length > 64) form = "策略 ID 最多 64 个字符";
  else if (!/^[A-Za-z0-9._-]+$/.test(draft.strategyId.trim())) form = "策略 ID 只能包含字母、数字、点、下划线和短横线";
  else if (draft.strategyId.trim() === "legacy-current") form = "请另存为新的策略 ID，不能写入 legacy-current";
  else if (!draft.name.trim()) form = "请填写策略名称";

  for (const role of DELIVERY_ROLES) {
    const slot = draft.roles.find((item) => item.role === role);
    if (!slot) {
      roles[role] = "缺少该角色配置";
      continue;
    }
    if (!slot.runtimeType) {
      roles[role] = "请选择执行器";
      continue;
    }
    if (!slot.providerProfileId.trim()) {
      roles[role] = "请选择 Provider";
      continue;
    }
    if (slot.runtimeType !== "MODEL_ONLY"
        && slot.imageMode === "CUSTOM"
        && !slot.dockerfileName.trim()
        && !slot.pendingDockerfile) {
      roles[role] = "请选择 Dockerfile，或改回本地默认镜像";
    }
  }

  if (!form && Object.keys(roles).length === 0) return null;
  return { form, roles };
}
