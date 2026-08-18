import type {
  ModelProviderMetadataInput,
  ModelProviderProfile,
  ModelProviderProtocol
} from "@/services/modelProviderService";

export const MODEL_PROVIDER_PROTOCOLS: readonly ModelProviderProtocol[] = [
  "OPENAI_CHAT_COMPLETIONS",
  "OPENAI_COMPLETIONS",
  "OPENAI_RESPONSES",
  "ANTHROPIC_COMPATIBLE",
  "ANTHROPIC_MESSAGES",
  "GOOGLE_GENERATIVE_AI"
] as const;

export const PROTOCOL_LABELS: Record<ModelProviderProtocol, string> = {
  OPENAI_CHAT_COMPLETIONS: "OpenAI Chat Completions",
  OPENAI_COMPLETIONS: "OpenAI Completions",
  OPENAI_RESPONSES: "OpenAI Responses",
  ANTHROPIC_COMPATIBLE: "Anthropic 兼容协议",
  ANTHROPIC_MESSAGES: "Anthropic Messages",
  GOOGLE_GENERATIVE_AI: "Google Generative AI"
};

const ENV_VAR_REGEX = /^[A-Z_][A-Z0-9_]*$/;

export function metadataError(input: ModelProviderMetadataInput & { providerId: string }): string {
  if (!input.providerId.trim()) return "请填写 providerId";
  if (!input.displayName.trim()) return "请填写显示名";
  if (!input.baseUrl.trim()) return "请填写 baseUrl";
  if (!input.modelId.trim()) return "请填写模型";
  if (!ENV_VAR_REGEX.test(input.credentialEnvironmentVariable.trim())) {
    return "环境变量名必须是大写字母、数字和下划线，不能填写密钥本身";
  }
  return "";
}

export function findDuplicateEnvVars(profiles: ModelProviderProfile[]): Set<string> {
  const counts = new Map<string, number>();
  for (const profile of profiles) {
    const env = profile.credentialEnvironmentVariable.trim();
    if (env) {
      counts.set(env, (counts.get(env) || 0) + 1);
    }
  }
  const duplicates = new Set<string>();
  for (const [env, count] of counts.entries()) {
    if (count > 1) {
      duplicates.add(env);
    }
  }
  return duplicates;
}

export function formatEpochMillis(epochMillis?: number | null): string {
  if (!epochMillis || typeof epochMillis !== "number" || epochMillis <= 0) return "";
  try {
    const date = new Date(epochMillis);
    const pad = (num: number) => String(num).padStart(2, "0");
    const y = date.getFullYear();
    const m = pad(date.getMonth() + 1);
    const d = pad(date.getDate());
    const h = pad(date.getHours());
    const min = pad(date.getMinutes());
    return `${y}-${m}-${d} ${h}:${min}`;
  } catch {
    return "";
  }
}
