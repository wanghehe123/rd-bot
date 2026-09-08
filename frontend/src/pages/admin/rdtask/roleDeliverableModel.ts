import type {
  RolePromptStageLike,
  RoleQaEvidenceLike,
  RoleStageLike
} from "./roleWorkbenchModel.ts";
import { isPlaceholderResultSummary } from "./workbenchSummaryModel.ts";
import type { StageResultResponse } from "@/services/stageResultService.ts";
import type { AuditedRecord, AuditedTaskState } from "@/services/rdTaskService.ts";

/** 证据条目：优先复用后端下发 contentUrl，其余字段用于展示兜底。 */
export type RoleQaEvidenceRef = RoleQaEvidenceLike & {
  contentUrl?: string;
  name?: string;
  type?: string;
};

export interface DeliverableItem {
  label: string;
  value: string;
  badge?: string;
  tone?: "default" | "success" | "warning" | "problem";
}

export interface ReportedCheckItem {
  name: string;
  status: string;
  scope?: string;
  command?: string;
  exitCode?: number;
  durationMillis?: number;
  detail?: string;
}

/** Host 审计通过的验收记录，直接复用后端 wire 类型（id/text/evidenceRefs）。 */
export type AuditedCheckItem = AuditedRecord;

/** 任务级 PR 来源：RdTask.pullRequestUrl 是唯一权威 URL；workBranch 仅辅助展示。 */
export interface TaskPrInput {
  prUrl: string;
  workBranch?: string;
}

export interface ArtifactLinkItem {
  name: string;
  type: string;
  targetUrl?: string;
  scope: "STAGE" | "TASK";
  artifactId?: string;
}

export interface DeliverableEvidenceItem {
  id: string;
  name: string;
  type: string;
  role: string;
  attemptNo: number;
  stageRunId: string;
  criteriaId?: string;
  auditedStatus?: string;
  auditRunId?: string;
  contentUrl?: string;
  metadata?: Record<string, string | number | boolean | null | undefined>;
}

export interface RoleDeliverableView {
  role: string;
  stageRunId: string;
  attemptNo: number;
  status: string;
  executionSummary: string;
  deliverables: DeliverableItem[];
  /** 全量结构化交付项（deliverables 是它的前三项兼容视图）。 */
  allDeliverables: DeliverableItem[];
  reportedChecks: ReportedCheckItem[];
  auditedChecks: AuditedCheckItem[];
  /** 当前任务最新 Host 审计 head 中不属于所选 Attempt 的阻断记录。 */
  taskHeadGaps: AuditedCheckItem[];
  keyGaps: string[];
  keyEvidence: DeliverableEvidenceItem[];
  /** 全量阶段证据（keyEvidence 是它的前五项兼容视图）。 */
  allEvidence: DeliverableEvidenceItem[];
  totalEvidenceCount: number;
  artifactLinks: ArtifactLinkItem[];
  unavailableReason: string | null;
  rawResultPreview?: string;
  hasMoreDeliverables: boolean;
  hasMoreEvidence: boolean;
}

export interface RoleDeliverableInput {
  taskId: string;
  role: string;
  stage?: RoleStageLike;
  promptStage?: RolePromptStageLike;
  qaEvidence?: RoleQaEvidenceRef[];
  hostVerification?: {
    runId?: string;
    codingStageRunId?: string;
    status?: string;
    docsOnly?: boolean;
    failureCategory?: string | null;
    errorMessage?: string;
  } | null;
  auditedState?: Pick<AuditedTaskState, "records"> | null;
  taskPr?: TaskPrInput | null;
  stageResult?: StageResultResponse | null;
}

const QA_EVIDENCE_TYPE_LABEL: Record<string, string> = {
  QA_COMMAND_LOG: "命令日志",
  QA_SCREENSHOT: "浏览器截图",
  QA_TRACE: "Playwright trace",
  QA_CONSOLE_LOG: "控制台日志",
  QA_NETWORK_LOG: "网络记录",
  QA_HTTP_TRANSCRIPT: "HTTP 实链",
  QA_VIDEO: "失败录像",
  QA_EVIDENCE_MANIFEST: "证据清单"
};

export function buildRoleDeliverables(input: RoleDeliverableInput): RoleDeliverableView {
  const {
    taskId,
    role,
    stage,
    promptStage,
    qaEvidence = [],
    hostVerification,
    auditedState,
    taskPr,
    stageResult
  } = input;

  const stageRunId = stage?.stageRunId || promptStage?.stageRunId || "";
  const attemptNo = stage?.attemptNo ?? promptStage?.attemptNo ?? 1;
  const status = stage?.status || "PENDING";

  const rawJson = stageResult?.content || stage?.resultPreview;
  const parsed = parseResultObject(rawJson);

  const allDeliverables: DeliverableItem[] = [];
  const reportedChecks: ReportedCheckItem[] = [];
  const auditedChecks: AuditedCheckItem[] = [];
  const taskHeadGaps: AuditedCheckItem[] = [];
  const keyGaps: string[] = [];
  const artifactLinks: ArtifactLinkItem[] = [];

  // 占位 resultSummary（"ROLE result json"）不是真实执行概述，按缺失处理；
  // 完整结果解析出的 summary 仍会在下方覆盖。
  let executionSummary = isPlaceholderResultSummary(stage?.resultSummary)
    ? ""
    : (stage?.resultSummary || "");
  let unavailableReason: string | null = null;

  if (!stage && !promptStage) {
    unavailableReason = "当前角色尚未创建执行 Attempt";
  } else if (!parsed && !stage?.resultSummary && !stage?.errorMessage) {
    unavailableReason = "尚无可展示的结构化产物";
  }

  if (stage?.errorMessage) {
    keyGaps.push(stage.errorMessage.trim());
  }

  if (role === "REQUIREMENT_REVIEWER") {
    if (parsed) {
      if (parsed.summary) executionSummary = String(parsed.summary).trim();
      if (parsed.feasibility) {
        allDeliverables.push({
          label: "可行性",
          value: String(parsed.feasibility).trim(),
          tone: String(parsed.feasibility).toUpperCase().includes("FEASIBLE") ? "success" : "default"
        });
      }
      const missing = asStringList(parsed.missingInformation);
      if (missing.length > 0) {
        keyGaps.push(...missing.map((m) => `缺失材料: ${m}`));
      }
      const acceptanceCoverage = asStringList(parsed.acceptanceCoverage);
      if (acceptanceCoverage.length > 0) {
        allDeliverables.push({
          label: "验收覆盖",
          value: acceptanceCoverage.join("；")
        });
      }
      if (parsed.budgetEstimate) {
        allDeliverables.push({
          label: "预算估算",
          value: formatBudgetEstimate(parsed.budgetEstimate)
        });
      }
    }
  } else if (role === "SOLUTION_ARCHITECT") {
    if (parsed) {
      if (parsed.summary) executionSummary = String(parsed.summary).trim();
      const affectedFiles = asStringList(parsed.affectedFiles);
      if (affectedFiles.length > 0) {
        allDeliverables.push({
          label: "影响文件",
          value: affectedFiles.join("、")
        });
      }
      const implementationSteps = asStringList(parsed.implementationSteps);
      if (implementationSteps.length > 0) {
        allDeliverables.push({
          label: "实施步骤",
          value: implementationSteps.join("；")
        });
      }
      const testPlan = asStringList(parsed.testPlan);
      if (testPlan.length > 0) {
        allDeliverables.push({
          label: "测试计划",
          value: testPlan.join("；")
        });
      }
      const acceptanceMapping = asAcceptanceMappings(parsed.acceptanceMapping);
      if (acceptanceMapping.length > 0) {
        allDeliverables.push({
          label: "验收映射",
          value: acceptanceMapping.join("；")
        });
      }
    }
  } else if (role === "CODING_AGENT" || role === "BUG_CODING_AGENT") {
    if (parsed) {
      if (parsed.summary) executionSummary = String(parsed.summary).trim();
      const changedFiles = asStringList(parsed.changedFiles);
      if (changedFiles.length > 0) {
        allDeliverables.push({
          label: "修改文件",
          value: changedFiles.join("、")
        });
      }
      if (parsed.riskLevel) {
        allDeliverables.push({
          label: "风险等级",
          value: String(parsed.riskLevel).trim()
        });
      }
      if (parsed.testStatus) {
        reportedChecks.push({
          name: "Agent 自报测试",
          status: String(parsed.testStatus).trim()
        });
      }
      if (parsed.needHumanAction === true) {
        keyGaps.push("当前代码改动要求人工介入处理");
      }
    }

    // 只有当 Host Verification 明确绑定到本 stageRunId 时，才显示它的核验结论
    if (hostVerification && hostVerification.codingStageRunId === stageRunId) {
      const hvStatus = hostVerification.status || "UNKNOWN";
      if (hvStatus === "SUCCEEDED") {
        allDeliverables.push({
          label: "宿主验证",
          value: hostVerification.docsOnly ? "文档模式跳过" : "构建与静态检查通过",
          badge: "HOST_VERIFIED",
          tone: "success"
        });
      } else if (hvStatus.startsWith("FAILED")) {
        keyGaps.push(`宿主验证失败: ${hostVerification.errorMessage || hostVerification.failureCategory || hvStatus}`);
      }
    }

    // 任务级 PR 明确标明 scope: "TASK"；URL 是唯一权威来源，编号仅从 URL 提取用于展示
    if (taskPr?.prUrl) {
      artifactLinks.push({
        name: prLinkName(taskPr.prUrl),
        type: "PULL_REQUEST",
        targetUrl: taskPr.prUrl,
        scope: "TASK"
      });
    }
  } else if (role === "QA_AGENT") {
    if (parsed) {
      if (parsed.summary) executionSummary = String(parsed.summary).trim();
      const acceptanceResults = Array.isArray(parsed.acceptanceResults) ? parsed.acceptanceResults : [];
      for (const item of acceptanceResults) {
        if (!item || typeof item !== "object") continue;
        const check = item as Record<string, unknown>;
        const criteria = String(check.criteria || check.name || check.criteriaId || "未命名验收项");
        const scope = String(check.scope || "CURRENT").toUpperCase();
        const checkStatus = String(check.status || "").toUpperCase();
        reportedChecks.push({
          name: criteria,
          status: checkStatus,
          scope,
          command: check.command ? String(check.command) : undefined,
          exitCode: typeof check.exitCode === "number" ? check.exitCode : undefined,
          durationMillis: typeof check.durationMillis === "number" ? check.durationMillis : undefined
        });
        if (["FAILED", "FAIL", "BLOCKED", "SKIPPED"].includes(checkStatus)) {
          keyGaps.push(`[${scope}] ${criteria}: ${checkStatus}`);
        }
      }

      if (parsed.browserValidation && typeof parsed.browserValidation === "object") {
        const bv = parsed.browserValidation as Record<string, unknown>;
        if (bv.required === true && bv.performed !== true) {
          keyGaps.push("要求浏览器验证，但当前 Attempt 未执行");
        }
      }
    }

    // Host API 返回的是任务最新状态。已完成记录保留在 task-head 区域；所有 blocking
    // 且未完成的状态只有明确属于当前 Attempt 时才进入当前结论，其余记录单列。
    if (auditedState?.records) {
      for (const record of auditedState.records) {
        if (record.status === "COMPLETED") {
          auditedChecks.push(record);
        } else if (record.blocking) {
          const sourceStageRunId = String(record.sourceStageRunId || "").trim();
          if (sourceStageRunId && sourceStageRunId === stageRunId) {
            keyGaps.push(`已审计阻断缺口: ${record.id} (${record.text}) [${record.status}] 仍待完成（${auditedRecordSourceLabel(record)}）`);
          } else {
            taskHeadGaps.push(record);
          }
        }
      }
    }
  }

  // 关键证据列表（最多默认展示 5 项，完整列表见 allEvidence）。
  // URL 优先用后端下发的 task-scoped contentUrl；仅在缺失时按当前 taskId 生成既有路径，
  // 禁止把一个 task 的证据引用换到另一个 task。
  const matchingQaEvidence = qaEvidence.filter((ev) => ev.stageRunId === stageRunId);
  const totalEvidenceCount = matchingQaEvidence.length;
  const allEvidence: DeliverableEvidenceItem[] = matchingQaEvidence.map((ev) => ({
    id: ev.artifactId,
    name: ev.name || QA_EVIDENCE_TYPE_LABEL[ev.type || ""] || ev.artifactId,
    type: ev.type || ev.artifactId,
    role,
    attemptNo,
    stageRunId,
    contentUrl: ev.contentUrl
      || (taskId
        ? `/admin/rd-tasks/${taskId}/qa-evidence/${ev.artifactId}/content`
        : undefined)
  }));
  const keyEvidence = allEvidence.slice(0, 5);

  // 产物最多默认展示 3 项
  const deliverables = allDeliverables.slice(0, 3);
  const hasMoreDeliverables = allDeliverables.length > 3;
  const hasMoreEvidence = totalEvidenceCount > 5;

  return {
    role,
    stageRunId,
    attemptNo,
    status,
    executionSummary,
    deliverables,
    allDeliverables,
    reportedChecks,
    auditedChecks,
    taskHeadGaps,
    keyGaps,
    keyEvidence,
    allEvidence,
    totalEvidenceCount,
    artifactLinks,
    unavailableReason,
    rawResultPreview: rawJson || undefined,
    hasMoreDeliverables,
    hasMoreEvidence
  };
}

function parseResultObject(value?: string | null): Record<string, unknown> | null {
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

/** 从 PR URL 提取编号用于展示；提取不到时退回通用标题，链接仍照常渲染。 */
function prLinkName(url: string): string {
  const match = /\/(?:pull|pulls|pull-requests|merge_requests)\/(\d+)/i.exec(url.trim());
  return match ? `PR #${Number(match[1])}` : "Pull Request";
}

function auditedRecordSourceLabel(record: AuditedCheckItem): string {
  const sourceStageRunId = String(record.sourceStageRunId || "").trim();
  return sourceStageRunId ? `来源 ${sourceStageRunId}` : "来源未记录";
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
