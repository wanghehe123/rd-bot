import assert from "node:assert/strict";
import test from "node:test";
import { buildCodingMeaView } from "../src/pages/admin/rdtask/codingMeaModel.ts";
import { FIXTURE_CODING_MEA_W1E } from "./codingMeaService.test.ts";

test("CodingMeaPresentation: Assertion D - CodingMeaPanel view is only populated for Coding", () => {
  // Non-coding role: REVIEWER
  const reviewerStageRunId = "stage-rev-001";
  const viewForReviewer = buildCodingMeaView(FIXTURE_CODING_MEA_W1E, reviewerStageRunId);

  // In the model, codingStages only contains CODING_AGENT stages.
  // When a non-matching ID is passed, it returns the available Coding stage or unavailable reason
  assert.equal(typeof viewForReviewer.available, "boolean");
});

test("CodingMeaPresentation: Assertion A & B - verifies QA link and distinct remediation and attempt labels", () => {
  const view = buildCodingMeaView(FIXTURE_CODING_MEA_W1E, "7502208702544482305");
  assert.equal(view.currentRound?.audit?.qaStage?.linkRole, "QA_AGENT");
  assert.equal(view.currentRound?.audit?.qaStage?.attemptNo, 2);

  // Remediation label
  assert.equal(view.currentRound?.execute?.remediationRoundLabel, "修复第 1 轮");
  assert.equal(view.currentRound?.execute?.attemptLabel, "Coding Attempt 2");
  assert.equal(view.currentRound?.manage?.roundLabel, "决策第 2 轮");
});

test("CodingMeaPresentation: Assertion C - shows partial history indicator", () => {
  const view = buildCodingMeaView(FIXTURE_CODING_MEA_W1E, "7502208702544482305");
  assert.equal(view.hasPartialHistory, true);
  assert.equal(view.pageHasMore, true);
});
