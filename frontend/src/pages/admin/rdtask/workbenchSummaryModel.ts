import type { RoleQaEvidenceLike, RoleStageLike } from "./roleWorkbenchModel.ts";
import type { AuditedRecord } from "@/services/rdTaskService.ts";

/**
 * 工作台四角色卡的一句话产物摘要。
 *
 * 只消费页面已加载的字段（stage 预览、任务级证据与审计记录），不发请求、
 * 不解析新的状态协议；完整结构化结果仍由 RoleDeliverablesPanel 按需读取。
 */

export type CheckStatusLike = { status: string };

export interface RoleCardSummaryInput {
  taskId: string;
  stage?: RoleStageLike;
  /** 任务级 QA 证据列表（页面已加载；按所选 stage 过滤）。 */
  qaEvidence?: readonly RoleQaEvidenceLike[];
  /** 任务最新 Host 审计 head（页面已加载）。 */
  auditedRecords?: readonly AuditedRecord[] | null;
  /** 绑定到该 Coding stage 的宿主验证结论（可选）。 */
  hostVerification?: {
    status?: string;
    codingStageRunId?: string;
    docsOnly?: boolean;
  } | null;
  /** 任务级 PR URL（RdTask.pullRequestUrl 权威来源）。 */
  taskPrUrl?: string;
}

const PASS_STATUSES = new Set(["PASS", "PASSED"]);

/** PASS/PASSED 计 passed；SKIPPED/BLOCKED/未知值保持 other，不得统一改名为“失败”。 */
export function summarizeChecks(checks: readonly CheckStatusLike[]): {
  total: number;
  passed: number;
  other: number;
} {
  const total = checks.length;
  const passed = checks.filter((check) => PASS_STATUSES.has((check.status || "").toUpperCase())).length;
  return { total, passed, other: total - passed };
}

/** 后端把未生成结构化结果的 resultSummary 写成 “ROLE result json” 占位。 */
export function isPlaceholderResultSummary(value?: string | null): boolean {
  const text = (value || "").trim();
  return /^[\w-]+\s+result\s+json$/i.test(text);
}

/** 一句话角色产物摘要；数据不可用时返回诚实的降级文案，不编造内容。 */
export function roleCardSummary(input: RoleCardSummaryInput): string {
  const { taskId, stage } = input;
  if (!stage) return "尚未创建";

  const rawJson = stage.resultPreview;
  const parsed = parseResultObject(rawJson);
  const role = stage.role;

  if (role === "QA_AGENT") {
    // 自报检查计数只在预览可解析时给出；截断预览不得冒充完整数量。
    // 审计完成数与阶段证据数来自任务级已加载数据，不依赖结果解析。
    const parts: string[] = [];
    if (parsed) {
      const acceptanceResults = Array.isArray(parsed.acceptanceResults) ? parsed.acceptanceResults : [];
      const checks = acceptanceResults.filter((item): item is Record<string, unknown> => (
        !!item && typeof item === "object"
      )).map((item) => ({ status: String(item.status || "") }));
      const summary = summarizeChecks(checks);
      if (summary.total > 0) parts.push(`自报检查 ${summary.total} 项（通过 ${summary.passed}）`);
    }
    const auditedCompleted = (input.auditedRecords || []).filter((record) => record.status === "COMPLETED").length;
    const stageEvidence = (input.qaEvidence || []).filter((ev) => ev.stageRunId === stage.stageRunId);
    if (auditedCompleted > 0) parts.push(`任务审计已完成 ${auditedCompleted} 项`);
    parts.push(`QA 证据 ${stageEvidence.length} 条`);
    return parts.join(" · ");
  }

  if (role === "CODING_AGENT" || role === "BUG_CODING_AGENT") {
    const parts: string[] = [];
    if (parsed) {
      const changedFiles = asStringList(parsed.changedFiles);
      if (changedFiles.length > 0) parts.push(`变更 ${changedFiles.length} 个文件`);
    }
    if (input.hostVerification && input.hostVerification.codingStageRunId === stage.stageRunId) {
      const hv = (input.hostVerification.status || "").toUpperCase();
      if (hv === "SUCCEEDED") parts.push(input.hostVerification.docsOnly ? "宿主验证跳过（文档模式）" : "宿主验证通过");
      else if (hv.startsWith("FAILED")) parts.push("宿主验证失败");
    }
    const pr = prLabel(input.taskPrUrl);
    if (pr) parts.push(pr);
    return parts.join(" · ") || "结构化摘要暂不可用";
  }

  if (!parsed) {
    if (stage.errorMessage?.trim()) return "执行未完成，暂无产物摘要";
    return "结构化摘要暂不可用";
  }

  if (role === "REQUIREMENT_REVIEWER") {
    const missing = asStringList(parsed.missingInformation);
    const coverage = asStringList(parsed.acceptanceCoverage);
    const parts: string[] = [];
    const feasibility = String(parsed.feasibility || "").trim();
    if (feasibility) parts.push(`可行性 ${feasibility.toUpperCase().includes("FEASIBLE") ? "通过" : "需关注"}`);
    if (coverage.length > 0) parts.push(`覆盖 ${coverage.length} 项验收`);
    if (missing.length > 0) parts.push(`待补充 ${missing.length} 项材料`);
    return parts.join(" · ") || "结构化摘要暂不可用";
  }

  if (role === "SOLUTION_ARCHITECT") {
    const affectedFiles = asStringList(parsed.affectedFiles);
    const steps = asStringList(parsed.implementationSteps);
    const tests = asStringList(parsed.testPlan);
    const parts: string[] = [];
    if (affectedFiles.length > 0) parts.push(`影响 ${affectedFiles.length} 个文件`);
    if (steps.length > 0) parts.push(`${steps.length} 步实施`);
    if (tests.length > 0) parts.push(`${tests.length} 项测试计划`);
    return parts.join(" · ") || "结构化摘要暂不可用";
  }

  return "结构化摘要暂不可用";
}

function prLabel(url?: string): string {
  const trimmed = (url || "").trim();
  if (!trimmed) return "";
  const match = /\/(?:pull|pulls|pull-requests|merge_requests)\/(\d+)/i.exec(trimmed);
  return match ? `PR #${Number(match[1])}` : "任务 PR 已创建";
}

function parseResultObject(value?: string | null): Record<string, unknown> | null {
  const text = value?.trim();
  if (!text) return null;
  try {
    let parsed: unknown = JSON.parse(text);
    if (typeof parsed === "string") parsed = JSON.parse(parsed);
    return parsed && typeof parsed === "object" ? parsed as Record<string, unknown> : null;
  } catch {
    // 截断的 JSON 预览解析失败：不能据此计算“完整数量”，交由完整结果读取后展示。
    return null;
  }
}

function asStringList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => {
      if (item && typeof item === "object") {
        const record = item as Record<string, unknown>;
        return String(record.criteria || record.title || record.summary || record.detail || "");
      }
      return String(item || "");
    }).filter(Boolean);
  }
  if (typeof value === "string" && value.trim()) return [value.trim()];
  return [];
}
