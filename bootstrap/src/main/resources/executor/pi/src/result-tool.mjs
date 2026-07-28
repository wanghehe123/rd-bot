import { mkdir, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

// SUCCESS/FAILED/NEED_INFO/UNSAFE are the coding structured-result statuses.
// PASSED/SKIPPED are accepted for the QA_AGENT role protocol, whose report status
// vocabulary is PASSED/FAILED/SKIPPED (see AgentRoleResultValidator).
const STATUSES = new Set(["SUCCESS", "FAILED", "NEED_INFO", "UNSAFE", "PASSED", "SKIPPED"]);

// Mirrors the host-side AgentRoleResultValidator / StructuredResultValidator required
// fields so protocol violations are rejected while the agent can still fix them
// in-session, instead of failing after the container exits.
const REVIEW_DECISIONS = new Set(["APPROVED", "NEED_INFO", "REJECTED"]);
const FEASIBILITY_VALUES = new Set(["CAN_DO", "NEED_INFO", "UNSAFE"]);
const BUDGET_CONFIDENCE_VALUES = new Set(["LOW", "MEDIUM", "HIGH"]);
const CODING_STATUSES = new Set(["SUCCESS", "FAILED", "NEED_INFO", "UNSAFE"]);
const TEST_STATUSES = new Set(["PASSED", "FAILED", "SKIPPED"]);
const RISK_LEVELS = new Set(["LOW", "MEDIUM", "HIGH"]);
const QA_STATUSES = new Set(["PASSED", "FAILED", "SKIPPED"]);
const QA_FAILURE_CATEGORIES = new Set([
  "NONE", "PRODUCT_DEFECT", "REGRESSION", "ENVIRONMENT", "AUTHENTICATION",
  "QA_INFRASTRUCTURE", "REQUIREMENT_AMBIGUITY", "FLAKY",
]);
const QA_RETRY_RECOMMENDATIONS = new Set(["NONE", "CODING_AGENT", "HUMAN"]);
const QA_SCOPES = new Set(["CURRENT", "REGRESSION"]);
const QA_DECISION_SOURCES = new Set([
  "TASK_OVERRIDE", "PROJECT_PROFILE", "REPOSITORY_CONFIG", "AUTO_DETECTION", "NOT_APPLICABLE",
]);

export function validateResult(result) {
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    throw new Error("result must be a JSON object");
  }
  if (!STATUSES.has(result.status)) {
    throw new Error("result.status is invalid");
  }
  if (typeof result.summary !== "string") {
    throw new Error("result.summary must be a string");
  }
  return result;
}

/**
 * Role-aware protocol validation executed inside the container at submission
 * time. Returns the full error list so the agent can repair every violation
 * with a single follow-up rd_submit_result call.
 */
export function validateRoleResult(role, result) {
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    return ["result must be a JSON object"];
  }
  switch (role) {
    case "REQUIREMENT_REVIEWER":
      return validateRequirementReview(result);
    case "SOLUTION_ARCHITECT":
      return validateSolutionPlan(result);
    case "CODING_AGENT":
      return validateCodingResult(result);
    case "QA_AGENT":
      return validateQaReport(result);
    default:
      return [];
  }
}

function validateRequirementReview(result) {
  const errors = [];
  checkEnum(result, "decision", REVIEW_DECISIONS, errors);
  checkEnum(result, "feasibility", FEASIBILITY_VALUES, errors);
  checkArray(result, "missingInformation", false, errors);
  checkArray(result, "risks", false, errors);
  checkArray(result, "acceptanceCoverage", true, errors);
  const budget = result.budgetEstimate;
  if (!budget || typeof budget !== "object" || Array.isArray(budget)) {
    errors.push("budgetEstimate must be an object");
    return errors;
  }
  checkNonNegativeInteger(budget, "initialTokens", "budgetEstimate.initialTokens", errors);
  checkNonNegativeInteger(budget, "retryReserveTokens", "budgetEstimate.retryReserveTokens", errors);
  checkNonNegativeInteger(budget, "estimatedTotalTokens", "budgetEstimate.estimatedTotalTokens", errors);
  checkEnum(budget, "confidence", BUDGET_CONFIDENCE_VALUES, errors, "budgetEstimate.confidence");
  checkNonBlankString(budget, "basis", "budgetEstimate.basis", errors);
  checkArray(budget, "historicalSamples", false, errors, "budgetEstimate.historicalSamples");
  const total = Number(budget.estimatedTotalTokens);
  if (Number.isFinite(total) && total < Number(budget.initialTokens ?? 0) + Number(budget.retryReserveTokens ?? 0)) {
    errors.push("budgetEstimate.estimatedTotalTokens must cover initialTokens and retryReserveTokens");
  }
  if ((!Array.isArray(budget.historicalSamples) || budget.historicalSamples.length === 0)
      && budget.confidence !== "LOW") {
    errors.push("budgetEstimate.confidence must be LOW without historical samples");
  }
  return errors;
}

function validateSolutionPlan(result) {
  const errors = [];
  checkNonBlankString(result, "summary", "summary", errors);
  checkArray(result, "affectedFiles", true, errors);
  checkArray(result, "implementationSteps", true, errors);
  checkArray(result, "acceptanceMapping", true, errors);
  checkArray(result, "testPlan", true, errors);
  return errors;
}

function validateCodingResult(result) {
  const errors = [];
  if (!CODING_STATUSES.has(result.status)) {
    errors.push("status must be one of SUCCESS, FAILED, NEED_INFO, UNSAFE");
  }
  checkNonBlankString(result, "summary", "summary", errors);
  if (!Array.isArray(result.changedFiles)) {
    errors.push("changedFiles must be present");
  } else if (result.changedFiles.length === 0 && result.status !== "NEED_INFO" && result.status !== "FAILED") {
    errors.push("changedFiles may be empty only when status is NEED_INFO or FAILED");
  }
  if (!Array.isArray(result.testCommands)) {
    errors.push("testCommands must be present");
  } else if (result.testCommands.length === 0 && result.testStatus !== "SKIPPED") {
    errors.push("testCommands may be empty only when testStatus is SKIPPED");
  }
  if (!TEST_STATUSES.has(result.testStatus)) {
    errors.push("testStatus must be one of PASSED, FAILED, SKIPPED");
  }
  if (!RISK_LEVELS.has(result.riskLevel)) {
    errors.push("riskLevel must be one of LOW, MEDIUM, HIGH");
  }
  if (result.status === "SUCCESS" && (typeof result.prBody !== "string" || result.prBody.trim() === "")) {
    errors.push("prBody must not be blank when status is SUCCESS");
  }
  if (typeof result.needHumanAction !== "boolean") {
    errors.push("needHumanAction must be boolean");
  } else if (!result.needHumanAction
      && (result.status === "NEED_INFO" || result.status === "UNSAFE" || result.testStatus === "FAILED")) {
    errors.push("needHumanAction must be true when status is NEED_INFO/UNSAFE or testStatus is FAILED");
  }
  return errors;
}

function validateQaReport(result) {
  const errors = [];
  checkEnum(result, "status", QA_STATUSES, errors);
  checkNonBlankString(result, "summary", "summary", errors);
  checkEnum(result, "failureCategory", QA_FAILURE_CATEGORIES, errors);
  checkEnum(result, "retryRecommendation", QA_RETRY_RECOMMENDATIONS, errors);
  checkNonBlankString(result, "evidenceManifestArtifactId", "evidenceManifestArtifactId", errors);
  const browser = result.browserValidation;
  if (!browser || typeof browser !== "object" || Array.isArray(browser)) {
    errors.push("browserValidation must be an object");
  } else {
    if (typeof browser.required !== "boolean") errors.push("browserValidation.required must be boolean");
    if (typeof browser.performed !== "boolean") errors.push("browserValidation.performed must be boolean");
    checkEnum(browser, "decisionSource", QA_DECISION_SOURCES, errors, "browserValidation.decisionSource");
    if (browser.required === true && String(browser.browser ?? "").toLowerCase() !== "chromium") {
      errors.push("browserValidation.browser must be chromium");
    }
    if (browser.required === true && browser.performed !== true && result.status === "PASSED") {
      errors.push("browserValidation.required requires browserValidation.performed true");
    }
    if (browser.required === true && browser.performed === true) {
      checkNonBlankString(browser, "baseUrl", "browserValidation.baseUrl", errors);
    }
  }
  const acceptance = result.acceptanceResults;
  if (!Array.isArray(acceptance) || acceptance.length === 0) {
    errors.push("acceptanceResults must be a non-empty array");
    return errors;
  }
  let failedCount = 0;
  let nonPassedCount = 0;
  let currentPresent = false;
  let regressionPresent = false;
  acceptance.forEach((item, index) => {
    const prefix = `acceptanceResults[${index}]`;
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      errors.push(`${prefix} must be an object`);
      return;
    }
    checkNonBlankString(item, "criteria", `${prefix}.criteria`, errors);
    checkEnum(item, "scope", QA_SCOPES, errors, `${prefix}.scope`);
    checkNonBlankString(item, "command", `${prefix}.command`, errors);
    checkEnum(item, "status", QA_STATUSES, errors, `${prefix}.status`);
    if (!Number.isInteger(item.exitCode)) errors.push(`${prefix}.exitCode must be an integer`);
    if (!Number.isInteger(item.durationMillis) || item.durationMillis < 0) {
      errors.push(`${prefix}.durationMillis must be a non-negative integer`);
    }
    checkNonBlankString(item, "logArtifactId", `${prefix}.logArtifactId`, errors);
    if (!Array.isArray(item.evidenceArtifactIds) || item.evidenceArtifactIds.length === 0
        || item.evidenceArtifactIds.some((id) => typeof id !== "string" || id.trim() === "")) {
      errors.push(`${prefix}.evidenceArtifactIds must be a non-empty array of non-blank strings`);
    }
    if (item.scope === "CURRENT") currentPresent = true;
    if (item.scope === "REGRESSION") regressionPresent = true;
    if (item.status === "PASSED" && Number.isInteger(item.exitCode) && item.exitCode !== 0) {
      errors.push(`${prefix}.status PASSED requires exitCode 0`);
    }
    if (item.status === "FAILED") failedCount += 1;
    if (item.status !== "PASSED") nonPassedCount += 1;
  });
  if (!currentPresent) errors.push("acceptanceResults must include CURRENT scope evidence");
  if (!regressionPresent) errors.push("acceptanceResults must include REGRESSION scope evidence");
  if (result.status === "PASSED" && nonPassedCount > 0) {
    errors.push("status PASSED requires all acceptanceResults to be PASSED");
  }
  if (result.status === "FAILED" && failedCount === 0) {
    errors.push("status FAILED requires at least one FAILED acceptanceResults item");
  }
  if (result.status === "PASSED" && result.failureCategory !== "NONE") {
    errors.push("status PASSED requires failureCategory NONE");
  }
  if (result.status === "PASSED" && result.retryRecommendation !== "NONE") {
    errors.push("status PASSED requires retryRecommendation NONE");
  }
  if (result.status === "FAILED" && result.failureCategory === "NONE") {
    errors.push("status FAILED requires a non-NONE failureCategory");
  }
  if (result.status === "FAILED" && result.retryRecommendation === "NONE") {
    errors.push("status FAILED requires a retryRecommendation");
  }
  return errors;
}

function checkEnum(node, field, allowed, errors, label = field) {
  const value = node[field];
  if (typeof value !== "string" || !allowed.has(value)) {
    errors.push(`${label} must be one of ${[...allowed].join(", ")}`);
  }
}

function checkNonBlankString(node, field, label, errors) {
  const value = node[field];
  if (typeof value !== "string" || value.trim() === "") {
    errors.push(`${label} must be a non-blank string`);
  }
}

function checkNonNegativeInteger(node, field, label, errors) {
  const value = node[field];
  if (!Number.isInteger(value) || value < 0) {
    errors.push(`${label} must be a non-negative integer`);
  }
}

function checkArray(node, field, requireNonEmpty, errors, label = field) {
  const value = node[field];
  if (!Array.isArray(value)) {
    errors.push(`${label} must be an array`);
    return;
  }
  if (requireNonEmpty && value.length === 0) {
    errors.push(`${label} must not be empty`);
  }
}

export async function writeResultAtomically(path, result) {
  validateResult(result);
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}`;
  await writeFile(temporary, `${JSON.stringify(result)}\n`, { encoding: "utf8", mode: 0o600 });
  await rename(temporary, path);
  return path;
}
