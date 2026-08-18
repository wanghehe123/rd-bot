import { api } from "@/services/api";
import { asArray } from "@/services/jsonArray";

export type ModelProviderProtocol =
  | "ANTHROPIC_COMPATIBLE"
  | "ANTHROPIC_MESSAGES"
  | "OPENAI_CHAT_COMPLETIONS"
  | "OPENAI_COMPLETIONS"
  | "OPENAI_RESPONSES"
  | "GOOGLE_GENERATIVE_AI";

export interface ModelProviderProfile {
  providerId: string;
  displayName: string;
  protocol: ModelProviderProtocol;
  baseUrl: string;
  modelId: string;
  credentialEnvironmentVariable: string;
  authHeader?: boolean;
  enabled: boolean;
  version: number;
  credentialConfigured: boolean;
  credentialUpdatedAt: number | null;
}

export interface ModelProviderMetadataInput {
  displayName: string;
  protocol: ModelProviderProtocol;
  baseUrl: string;
  modelId: string;
  credentialEnvironmentVariable: string;
  authHeader: boolean;
  enabled: boolean;
  version: number;
}

export interface ModelProviderCredentialInput {
  apiKey: string;
}

export interface ModelProviderCredentialResult {
  providerId: string;
  configured: boolean;
  updatedAtEpochMillis?: number;
}

function normalizeProfile(raw: unknown): ModelProviderProfile {
  const item = (raw && typeof raw === "object" ? raw : {}) as Partial<ModelProviderProfile>;
  return {
    providerId: String(item.providerId || "").trim(),
    displayName: String(item.displayName || "").trim(),
    protocol: (item.protocol || "OPENAI_CHAT_COMPLETIONS") as ModelProviderProtocol,
    baseUrl: String(item.baseUrl || "").trim(),
    modelId: String(item.modelId || "").trim(),
    credentialEnvironmentVariable: String(item.credentialEnvironmentVariable || "").trim(),
    authHeader: Boolean(item.authHeader),
    enabled: item.enabled !== false,
    version: typeof item.version === "number" ? item.version : 1,
    credentialConfigured: Boolean(item.credentialConfigured),
    credentialUpdatedAt: typeof item.credentialUpdatedAt === "number" ? item.credentialUpdatedAt : null
  };
}

export async function listModelProviders(): Promise<ModelProviderProfile[]> {
  const rawList = asArray<unknown>(await api.get<unknown, unknown>("/admin/model-provider-profiles"));
  return rawList.map(normalizeProfile);
}

export async function upsertModelProvider(
  providerId: string,
  body: ModelProviderMetadataInput,
  mutationToken: string
): Promise<ModelProviderProfile> {
  const res = await api.put<ModelProviderProfile, ModelProviderProfile>(
    `/admin/model-provider-profiles/${encodeURIComponent(providerId)}`,
    body,
    {
      headers: { "X-RD-Agent-Runtime-Token": mutationToken }
    }
  );
  return normalizeProfile(res);
}

export async function putModelProviderCredential(
  providerId: string,
  apiKey: string,
  mutationToken: string
): Promise<ModelProviderCredentialResult> {
  return api.put<ModelProviderCredentialResult, ModelProviderCredentialResult>(
    `/admin/model-provider-profiles/${encodeURIComponent(providerId)}/credential`,
    { apiKey },
    {
      headers: { "X-RD-Agent-Runtime-Token": mutationToken }
    }
  );
}
