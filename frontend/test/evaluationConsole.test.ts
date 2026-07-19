import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const layout = readFileSync(new URL("../src/components/AdminLayout.tsx", import.meta.url), "utf8");
const page = readFileSync(new URL("../src/pages/admin/evaluation/EvaluationPage.tsx", import.meta.url), "utf8");
const service = readFileSync(new URL("../src/services/evaluationService.ts", import.meta.url), "utf8");
const presentation = readFileSync(new URL("../src/pages/admin/evaluation/evaluationPresentation.ts", import.meta.url), "utf8");
const tracePage = readFileSync(new URL("../src/pages/admin/trace/ExecutionTracePage.tsx", import.meta.url), "utf8");

test("registers evaluation as a first-class management route and navigation item", () => {
  assert.match(app, /path="evaluations"/);
  assert.match(layout, /\/admin\/evaluations/);
  assert.match(layout, /评测/);
});

test("offers structured local evaluation configuration without arbitrary commands", () => {
  assert.match(page, /数据集/);
  assert.match(page, /录制模式/);
  assert.match(page, /Judge/);
  assert.match(page, /样本上限/);
  assert.match(page, /严格缺失检查/);
  assert.match(page, /基线 Run/);
  assert.doesNotMatch(page, /shellCommand|任意命令/);
});

test("shows process, metrics, failures and artifacts with terminal-aware polling", () => {
  assert.match(page, /执行日志/);
  assert.match(page, /指标门禁/);
  assert.match(page, /失败样本/);
  assert.match(page, /评测产物/);
  assert.match(page, /setInterval/);
  assert.match(page, /2_000/);
  assert.match(page, /isEvaluationRunActive/);
  assert.match(service, /\/admin\/evaluations\/runs\/\$\{runId\}\/logs/);
  assert.match(service, /\/admin\/evaluations\/runs\/\$\{runId\}\/retry/);
  assert.match(service, /\/admin\/evaluations\/runs\/\$\{runId\}\/cancel/);
});

test("renders explainable metric help and a backward-compatible human summary", () => {
  assert.match(service, /export type EvaluationMetric/);
  assert.match(service, /label\?: string/);
  assert.match(service, /purpose\?: string/);
  assert.match(service, /calculation\?: string/);
  assert.match(page, /parseEvaluationMetricsPayload/);
  assert.match(page, /CircleHelp/);
  assert.match(page, /TooltipProvider/);
  assert.match(page, /TooltipContent/);
  assert.match(page, /指标说明/);
  assert.match(page, /简要评测报告/);
  assert.match(page, /failedMetrics/);
  assert.match(page, /nextActions/);
});

test("keeps the three configured Judge choices available in the management form", () => {
  assert.match(page, /\(\["NONE", "RAGAS", "OPENAI_COMPATIBLE"\] as EvaluationJudgeProvider\[\]\)\.map/);
});

test("separates execution, gate and Judge conclusions and labels dataset purpose", () => {
  assert.match(service, /EvaluationDatasetKind/);
  assert.match(service, /gateStatus/);
  assert.match(service, /judgeStatus/);
  assert.match(page, /evaluationConclusion/);
  assert.match(page, /评测不完整/);
  assert.match(presentation, /门禁未通过/);
  assert.match(page, /evaluationJudgeStatusLabel/);
  assert.match(page, /evaluationDatasetKindLabel/);
  assert.match(page, /数据类型/);
  assert.doesNotMatch(page, /run\.overallPassed \? "PASS" : "NOT OK"/);
});

test("delegates evaluation history filters and page boundaries to the backend", () => {
  assert.match(page, /historyKeyword/);
  assert.match(page, /historyDatasetKind/);
  assert.match(page, /historyExecutionStatus/);
  assert.match(page, /historyGateStatus/);
  assert.match(page, /historyJudgeStatus/);
  assert.match(page, /getEvaluationRuns\(query\)/);
  assert.match(page, /refreshRuns\(\{ \.\.\.historyQuery, page: 1 \}\)/);
  assert.match(service, /export type EvaluationRunHistoryQuery/);
  assert.match(service, /export type EvaluationRunPage/);
  assert.match(service, /params: query/);
  assert.match(page, /setHistoryPage\(1\)/);
  assert.match(page, /每页/);
  assert.match(page, /上一页/);
  assert.match(page, /下一页/);
  assert.doesNotMatch(page, /const filteredRuns/);
  assert.doesNotMatch(page, /const pagedRuns/);
  assert.doesNotMatch(page, /filteredRuns\.slice/);
});

test("supports evaluating one persisted execution directly from the trace page", () => {
  assert.match(service, /"FIXTURE" \| "RAG_HTTP" \| "TASK_RUN"/);
  assert.match(service, /taskId: string/);
  assert.match(service, /\/admin\/rd-tasks\/\$\{taskId\}\/evaluations/);
  assert.match(tracePage, /评测本次执行/);
  assert.match(tracePage, /createTaskRunEvaluation/);
  assert.match(tracePage, /FlaskConical/);
  assert.match(page, /useSearchParams/);
  assert.match(page, /TASK_RUN/);
  assert.match(page, /任务 ID/);
});
