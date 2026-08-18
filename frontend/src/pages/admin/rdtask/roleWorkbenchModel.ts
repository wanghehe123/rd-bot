export const REQUIREMENT_ROLE_ORDER = [
  "REQUIREMENT_REVIEWER",
  "SOLUTION_ARCHITECT",
  "CODING_AGENT",
  "QA_AGENT"
] as const;

export const BUG_FIX_ROLE_ORDER = [
  "BUG_EVIDENCE_COLLECTOR",
  "BUG_RAG_RETRIEVER",
  "BUG_ACCEPTANCE_PLANNER",
  "BUG_CODING_AGENT"
] as const;

const FAILED_STAGE_STATUSES = new Set(["FAILED_RETRYABLE", "FAILED_NEEDS_HUMAN"]);
const RETRYABLE_REQUIREMENT_TASK_STATUSES = new Set([
  "REJECTED",
  "FAILED_RETRYABLE",
  "FAILED_NEEDS_HUMAN"
]);
const NON_SUBMITTABLE_REQUIREMENT_TASK_STATUSES = new Set([
  ...RETRYABLE_REQUIREMENT_TASK_STATUSES,
  "RECOVERING",
  "EXECUTING",
  "VALIDATING",
  "PR_CREATING",
  "WAITING_APPROVAL",
  "COMMITTED",
  "MERGED",
  "COMPLETED",
  "DELETED"
]);
const ACTIVE_STAGE_STATUSES = new Set([
  "CONTEXT_READY",
  "DISPATCHING",
  "RUNNING",
  "RESULT_COLLECTING",
  "VERIFYING",
  "RECOVERING"
]);

export interface RoleStageLike {
  stageRunId: string;
  role: string;
  status: string;
  attemptNo: number;
  updateTimeEpochMillis?: number;
  errorMessage?: string;
  errorCategory?: string;
  resultSummary?: string;
  resultPreview?: string;
  resultArtifactId?: string;
  promptArtifactId?: string;
  contextPackageId?: string;
  providerAttempts?: Record<string, unknown>[];
}

export interface RolePromptStageLike {
  stageRunId: string;
  role: string;
  status: string;
  attemptNo: number;
}

export interface RoleQaEvidenceLike {
  artifactId: string;
  stageRunId: string;
}

export interface RoleAttemptView<
  TStage extends RoleStageLike,
  TPrompt extends RolePromptStageLike
> {
  stageRunId: string;
  attemptNo: number;
  status: string;
  stage?: TStage;
  promptStage?: TPrompt;
  qaEvidenceIds: string[];
}

export interface RoleWorkbenchView<
  TStage extends RoleStageLike,
  TPrompt extends RolePromptStageLike
> {
  role: string;
  attempts: RoleAttemptView<TStage, TPrompt>[];
  latestStage?: TStage;
  status: string;
  blocker: string;
  issueCount: number;
  evidenceCount: number;
  unboundPromptCount: number;
}

export interface RoleAttemptSelection {
  role: string;
  attemptNo?: number;
  stageRunId: string;
}

export interface RoleResultProjection {
  available: boolean;
  headline: string;
  summary: string;
  problems: string[];
  risks: string[];
  acceptanceGaps: string[];
  failureCategory: string;
  retryRecommendation: string;
  facts: Array<{ label: string; value: string }>;
  qaChecks: Array<{
    criteria: string;
    scope: string;
    status: string;
    command: string;
    exitCode?: number;
    durationMillis?: number;
  }>;
  browserValidation?: {
    required: boolean;
    performed: boolean;
    browser: string;
    viewports: string[];
  };
}

export function buildRoleWorkbench<
  TStage extends RoleStageLike,
  TPrompt extends RolePromptStageLike,
  TEvidence extends RoleQaEvidenceLike
>(
  stageRuns: readonly TStage[],
  promptStages: readonly TPrompt[],
  qaEvidence: readonly TEvidence[],
  taskType = "REQUIREMENT",
  latestHostVerification?: { status?: string; errorMessage?: string } | null
): RoleWorkbenchView<TStage, TPrompt>[] {
  const roleOrder: readonly string[] = taskType === "BUG_FIX" ? BUG_FIX_ROLE_ORDER : REQUIREMENT_ROLE_ORDER;
  const roles = new Set<string>(roleOrder);
  stageRuns.forEach((stage) => roles.add(stage.role));
  promptStages.forEach((stage) => roles.add(stage.role));

  const orderedRoles = [
    ...roleOrder,
    ...[...roles].filter((role) => !roleOrder.includes(role))
  ];

  return orderedRoles.map((role) => {
    const stages = stageRuns
      .filter((stage) => stage.role === role)
      .sort(compareAttemptDescending);
    const prompts = promptStages
      .filter((stage) => stage.role === role)
      .sort(compareAttemptDescending);
    const attempts = stages
      .map((stage) => {
        const stageRunId = stage.stageRunId;
        const promptStage = prompts.find((item) => item.stageRunId === stageRunId);
        return {
          stageRunId,
          attemptNo: stage.attemptNo,
          status: stage.status,
          stage,
          promptStage,
          qaEvidenceIds: qaEvidence
            .filter((item) => item.stageRunId === stageRunId)
            .map((item) => item.artifactId)
        } satisfies RoleAttemptView<TStage, TPrompt>;
      })
      .sort(compareAttemptDescending);
    const latestStage = stages[0];
    const status = latestStage?.status || "PENDING";
    let blocker = resolveBlocker(latestStage);
    if (!blocker && (role === "QA_AGENT" || role === "BUG_CODING_AGENT") && status === "PENDING" && latestHostVerification) {
      const hvStatus = latestHostVerification.status;
      if (hvStatus === "FAILED_RETRYABLE" || hvStatus === "FAILED_NEEDS_HUMAN") {
        blocker = "等待宿主验证通过";
      } else if (hvStatus === "BUILDING" || hvStatus === "STATIC_CHECKING" || hvStatus === "PREPARING") {
        blocker = "等待宿主验证完成";
      }
    }
    const latestEvidenceCount = attempts[0]?.qaEvidenceIds.length || 0;

    return {
      role,
      attempts,
      latestStage,
      status,
      blocker,
      issueCount: FAILED_STAGE_STATUSES.has(status) || blocker ? 1 : 0,
      evidenceCount: latestEvidenceCount,
      unboundPromptCount: prompts.filter((prompt) => (
        !stages.some((stage) => stage.stageRunId === prompt.stageRunId)
      )).length
    };
  });
}

export function selectRoleAttempt<
  TStage extends RoleStageLike,
  TPrompt extends RolePromptStageLike
>(
  roles: readonly RoleWorkbenchView<TStage, TPrompt>[],
  requestedRole: string,
  requestedAttempt?: number
): RoleAttemptSelection {
  const requested = roles.find((item) => item.role === requestedRole);
  const selectedRole = requested
    || roles.find((item) => FAILED_STAGE_STATUSES.has(item.status))
    || roles.find((item) => ACTIVE_STAGE_STATUSES.has(item.status))
    || latestUpdatedRole(roles)
    || roles[0];
  const selectedAttempt = selectedRole?.attempts.find((item) => item.attemptNo === requestedAttempt)
    || selectedRole?.attempts[0];

  return {
    role: selectedRole?.role || "",
    attemptNo: selectedAttempt?.attemptNo,
    stageRunId: selectedAttempt?.stageRunId || ""
  };
}

export function roleStageSignature(stageRuns: readonly RoleStageLike[]): string {
  return [...stageRuns]
    .sort((left, right) => (
      left.role.localeCompare(right.role)
      || left.attemptNo - right.attemptNo
      || left.stageRunId.localeCompare(right.stageRunId)
    ))
    .map((stage) => [
      stage.stageRunId,
      stage.status,
      stage.promptArtifactId || "",
      stage.contextPackageId || "",
      stage.resultArtifactId || ""
    ].join(":"))
    .join("|");
}

export function projectRoleResult(role: string, value?: string): RoleResultProjection {
  const result = parseResultObject(value);
  if (!result) return emptyResultProjection();

  const projection: RoleResultProjection = {
    available: true,
    headline: String(result.decision || result.status || "").trim(),
    summary: String(result.summary || "").trim(),
    problems: [],
    risks: asStringList(result.risks),
    acceptanceGaps: [],
    failureCategory: String(result.failureCategory || "").trim(),
    retryRecommendation: String(result.retryRecommendation || "").trim(),
    facts: [],
    qaChecks: []
  };

  if (role === "REQUIREMENT_REVIEWER") {
    projection.problems = asStringList(result.missingInformation);
    const acceptanceCoverage = asStringList(result.acceptanceCoverage);
    addFact(projection, "验收覆盖", acceptanceCoverage.join("；"));
    if (isBlockingHeadline(projection.headline)) {
      projection.acceptanceGaps = acceptanceCoverage;
    }
    addFact(projection, "可行性", result.feasibility);
    addFact(projection, "预算估算", formatBudgetEstimate(result.budgetEstimate));
  } else if (role === "SOLUTION_ARCHITECT") {
    addFact(projection, "影响文件", asStringList(result.affectedFiles).join("、"));
    addFact(projection, "实施步骤", asStringList(result.implementationSteps).join("；"));
    addFact(projection, "测试计划", asStringList(result.testPlan).join("；"));
    addFact(projection, "验收映射", asAcceptanceMappings(result.acceptanceMapping).join("；"));
  } else if (role === "CODING_AGENT" || role === "BUG_CODING_AGENT") {
    addFact(projection, "测试状态", result.testStatus);
    addFact(projection, "风险等级", result.riskLevel);
    addFact(projection, "变更文件", asStringList(result.changedFiles).join("、"));
    if (result.needHumanAction === true) projection.problems.push("当前结果要求人工处理");
    projection.problems.push(...asStringList(result.needHumanAction));
  } else if (role === "QA_AGENT") {
    const acceptanceResults = Array.isArray(result.acceptanceResults) ? result.acceptanceResults : [];
    projection.qaChecks = acceptanceResults.flatMap((item) => {
      if (!item || typeof item !== "object") return [];
      const check = item as Record<string, unknown>;
      return [{
        criteria: String(check.criteria || check.name || "未命名验收项"),
        scope: String(check.scope || ""),
        status: String(check.status || ""),
        command: String(check.command || ""),
        exitCode: typeof check.exitCode === "number" ? check.exitCode : undefined,
        durationMillis: typeof check.durationMillis === "number" ? check.durationMillis : undefined
      }];
    });
    projection.problems = projection.qaChecks.flatMap((check) => {
      const status = String(check.status || "").toUpperCase();
      return ["FAILED", "FAIL", "SKIPPED", "BLOCKED"].includes(status)
        ? [check.criteria]
        : [];
    });
    const browser = result.browserValidation && typeof result.browserValidation === "object"
      ? result.browserValidation as Record<string, unknown>
      : {};
    projection.browserValidation = {
      required: browser.required === true,
      performed: browser.performed === true,
      browser: String(browser.browser || ""),
      viewports: asStringList(browser.viewports)
    };
    if (browser.required === true && browser.performed !== true) {
      projection.problems.push("要求浏览器验证，但当前 attempt 未执行");
    }
  }

  return projection;
}

export function isFailedStageStatus(status: string): boolean {
  return FAILED_STAGE_STATUSES.has(status);
}

export function isActiveStageStatus(status: string): boolean {
  return ACTIVE_STAGE_STATUSES.has(status);
}

export function isRetryableRequirementTaskStatus(status: string): boolean {
  return RETRYABLE_REQUIREMENT_TASK_STATUSES.has(status);
}

const CHECKPOINT_RETRY_PREFIX = "checkpoint-bound retry:";
const RETRY_STAGE_LABEL: Record<string, string> = {
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案架构",
  CODING_AGENT: "编码",
  QA_AGENT: "质量验收"
};

export type TaskStatusNotice =
  | { kind: "none"; message: "" }
  | { kind: "blocker"; message: string }
  | { kind: "recovery"; message: string };

/** Classifies task.errorMessage so RECOVERING retry progress is not shown as a blocker. */
export function taskStatusNotice(task: { status: string; errorMessage?: string | null }): TaskStatusNotice {
  const message = (task.errorMessage || "").trim();
  if (!message) {
    return { kind: "none", message: "" };
  }
  if (task.status === "RECOVERING" && message.toLowerCase().startsWith(CHECKPOINT_RETRY_PREFIX)) {
    const stage = message.slice(CHECKPOINT_RETRY_PREFIX.length).trim();
    return { kind: "recovery", message: `正在从失败阶段恢复：${humanizeRetryStage(stage)}` };
  }
  if (RETRYABLE_REQUIREMENT_TASK_STATUSES.has(task.status) || task.status === "DEAD_LETTERED") {
    return { kind: "blocker", message };
  }
  if (task.status === "RECOVERING" || task.status === "EXECUTING") {
    return { kind: "none", message: "" };
  }
  return { kind: "blocker", message };
}

function humanizeRetryStage(stage: string): string {
  const role = stage.includes(":") ? stage.slice(stage.lastIndexOf(":") + 1) : stage;
  return RETRY_STAGE_LABEL[role] || role || stage;
}

export function canSubmitRequirementTask(task: { status: string; paused: boolean }): boolean {
  return !task.paused && !NON_SUBMITTABLE_REQUIREMENT_TASK_STATUSES.has(task.status);
}

function compareAttemptDescending(left: { attemptNo: number }, right: { attemptNo: number }) {
  return right.attemptNo - left.attemptNo;
}

function resolveBlocker(stage?: RoleStageLike): string {
  if (!stage) return "";
  const error = stage.errorMessage?.trim();
  if (error) return error;
  if (!FAILED_STAGE_STATUSES.has(stage.status)) return "";
  return stage.resultSummary?.trim() || extractStructuredBlocker(stage.resultPreview) || "该角色需要处理后才能继续";
}

function extractStructuredBlocker(value?: string): string {
  const text = value?.trim();
  if (!text) return "";
  try {
    let parsed: unknown = JSON.parse(text);
    if (typeof parsed === "string") parsed = JSON.parse(parsed);
    if (!parsed || typeof parsed !== "object") return "";
    const result = parsed as Record<string, unknown>;
    const missing = Array.isArray(result.missingInformation) ? result.missingInformation : [];
    const firstMissing = missing.map(String).find(Boolean);
    return firstMissing
      || String(result.summary || "").trim()
      || String(result.failureCategory || "").trim();
  } catch {
    return "";
  }
}

function latestUpdatedRole<
  TStage extends RoleStageLike,
  TPrompt extends RolePromptStageLike
>(roles: readonly RoleWorkbenchView<TStage, TPrompt>[]) {
  return [...roles]
    .filter((item) => item.latestStage)
    .sort((left, right) => (
      (right.latestStage?.updateTimeEpochMillis || 0) - (left.latestStage?.updateTimeEpochMillis || 0)
    ))[0];
}

function parseResultObject(value?: string): Record<string, unknown> | null {
  const text = value?.trim();
  if (!text) return null;
  try {
    let parsed: unknown = JSON.parse(text);
    if (typeof parsed === "string") parsed = JSON.parse(parsed);
    return parsed && typeof parsed === "object" ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function emptyResultProjection(): RoleResultProjection {
  return {
    available: false,
    headline: "",
    summary: "",
    problems: [],
    risks: [],
    acceptanceGaps: [],
    failureCategory: "",
    retryRecommendation: "",
    facts: [],
    qaChecks: []
  };
}

function asStringList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => {
      if (item && typeof item === "object") {
        const record = item as Record<string, unknown>;
        return String(record.criteria || record.title || record.summary || record.detail || JSON.stringify(item));
      }
      return String(item || "");
    }).filter(Boolean);
  }
  if (typeof value === "string" && value.trim()) return [value.trim()];
  return [];
}

function addFact(projection: RoleResultProjection, label: string, value: unknown) {
  const text = String(value || "").trim();
  if (text) projection.facts.push({ label, value: text });
}

function isBlockingHeadline(value: string): boolean {
  return ["NEED_INFO", "NEEDS_HUMAN", "UNSAFE", "REJECTED", "FAILED", "SKIPPED", "BLOCKED"]
    .includes(value.trim().toUpperCase());
}

function asAcceptanceMappings(value: unknown): string[] {
  if (!Array.isArray(value)) return asStringList(value);
  return value.map((item) => {
    if (!item || typeof item !== "object") return String(item || "").trim();
    const mapping = item as Record<string, unknown>;
    const criteria = String(mapping.criteria || mapping.title || "").trim();
    const validation = String(mapping.validation || mapping.verify || mapping.method || "").trim();
    if (criteria && validation) return `${criteria} -> ${validation}`;
    return criteria || validation || JSON.stringify(item);
  }).filter(Boolean);
}

function formatBudgetEstimate(value: unknown): string {
  if (!value || typeof value !== "object" || Array.isArray(value)) return String(value || "").trim();
  const budget = value as Record<string, unknown>;
  const total = toFiniteNumber(budget.estimatedTotalTokens);
  const initial = toFiniteNumber(budget.initialTokens);
  const reserve = toFiniteNumber(budget.retryReserveTokens);
  const confidence = String(budget.confidence || "").trim();
  const parts = [
    total === undefined ? "" : `总计 ${total.toLocaleString("en-US")}`,
    initial === undefined ? "" : `初始 ${initial.toLocaleString("en-US")}`,
    reserve === undefined ? "" : `重试预留 ${reserve.toLocaleString("en-US")}`,
    confidence ? `置信度 ${confidence}` : ""
  ].filter(Boolean);
  return parts.join(" · ") || JSON.stringify(value);
}

function toFiniteNumber(value: unknown): number | undefined {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number : undefined;
}
