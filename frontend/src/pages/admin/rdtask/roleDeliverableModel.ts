import type {
  RolePromptStageLike,
  RoleQaEvidenceLike,
  RoleStageLike
} from "./roleWorkbenchModel.ts";
import type { StageResultResponse } from "@/services/stageResultService.ts";

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

export interface AuditedCheckItem {
  recordId: string;
  title: string;
  status: string;
  evidenceIds: string[];
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
  reportedChecks: ReportedCheckItem[];
  auditedChecks: AuditedCheckItem[];
  keyGaps: string[];
  keyEvidence: DeliverableEvidenceItem[];
  totalEvidenceCount: number;
  artifactLinks: ArtifactLinkItem[];
  unavailableReason: string | null;
  rawResultPreview?: string;
  hasMoreDeliverables: boolean;
  hasMoreEvidence: boolean;
}

export interface RoleDeliverableInput {
  role: string;
  stage?: RoleStageLike;
  promptStage?: RolePromptStageLike;
  qaEvidence?: RoleQaEvidenceLike[];
  hostVerification?: {
    runId?: string;
    codingStageRunId?: string;
    status?: string;
    docsOnly?: boolean;
    failureCategory?: string | null;
    errorMessage?: string;
  } | null;
  auditedState?: {
    records?: Array<{
      recordId: string;
      title: string;
      status: string;
      blocking: boolean;
      evidenceIds?: string[];
      sourceStageRunId?: string;
    }>;
  } | null;
  taskPr?: {
    prNumber?: number;
    prUrl?: string;
    workBranch?: string;
  } | null;
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
  const keyGaps: string[] = [];
  const artifactLinks: ArtifactLinkItem[] = [];

  let executionSummary = stage?.resultSummary || "";
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

    // 任务级 PR 明确标明 scope: "TASK"
    if (taskPr && taskPr.prNumber) {
      artifactLinks.push({
        name: `PR #${taskPr.prNumber}`,
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

    // Host 审计状态与已审计记录关联
    if (auditedState?.records) {
      for (const record of auditedState.records) {
        if (record.status === "COMPLETED") {
          auditedChecks.push({
            recordId: record.recordId,
            title: record.title,
            status: "COMPLETED",
            evidenceIds: record.evidenceIds || []
          });
        } else if (record.blocking && record.status === "PENDING") {
          keyGaps.push(`已审计阻断缺口: ${record.recordId} (${record.title}) 仍待完成`);
        }
      }
    }
  }

  // 关键证据列表（最多默认展示 5 项）
  const matchingQaEvidence = qaEvidence.filter((ev) => ev.stageRunId === stageRunId);
  const totalEvidenceCount = matchingQaEvidence.length;
  const keyEvidence: DeliverableEvidenceItem[] = matchingQaEvidence.slice(0, 5).map((ev) => ({
    id: ev.artifactId,
    name: QA_EVIDENCE_TYPE_LABEL[ev.artifactId] || ev.artifactId,
    type: ev.artifactId,
    role,
    attemptNo,
    stageRunId,
    contentUrl: `/admin/rd-tasks/qa-evidence/${ev.artifactId}/content`
  }));

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
    reportedChecks,
    auditedChecks,
    keyGaps,
    keyEvidence,
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
