import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { serializeDeliveryQuery } from "../src/services/deliveryObservabilityQuery.ts";
import {
  deliveryVisualState,
  formatPercentile,
  formatRatio,
  isolateStaleResponse
} from "../src/pages/admin/observability/deliveryObservabilityPresentation.ts";

test("does not send projectId=all and serializes allowlisted filters", () => {
  const params = serializeDeliveryQuery({
    projectId: "all",
    window: "24h",
    role: "QA_AGENT",
    runtime: "pi",
    failureCategory: "TIMEOUT",
    page: 2,
    pageSize: 20
  });
  assert.equal(params.projectId, undefined);
  assert.equal(params.window, "24h");
  assert.equal(params.role, "QA_AGENT");
  assert.equal(params.page, 2);
});

test("formats unavailable, no-sample, stale and insufficient P99 distinctly", () => {
  assert.equal(formatRatio({ available: false, noSample: false, numerator: 0, denominator: 0, sampleCount: 0, value: NaN }), "不可用");
  assert.equal(formatRatio({ available: true, noSample: true, numerator: 0, denominator: 0, sampleCount: 0, value: NaN }), "无样本");
  assert.equal(formatRatio({ available: true, noSample: false, numerator: 4, denominator: 5, sampleCount: 5, value: 0.8 }), "80.0% (4/5)");
  assert.match(formatPercentile({
    available: true,
    noSample: false,
    sampleCount: 12,
    p50Seconds: 1,
    p95Seconds: 2,
    p99Seconds: NaN,
    meanSeconds: 1.2,
    p99Insufficient: true
  }, "24h"), /P99样本不足/);
  assert.equal(deliveryVisualState([{ source: "delivery_ledger", available: false, generatedAtEpochMillis: 1, lastSuccessEpochMillis: 0, stale: false, warning: "down" }]), "collector-failed");
  assert.equal(deliveryVisualState([{ source: "delivery_ledger", available: false, generatedAtEpochMillis: 1, lastSuccessEpochMillis: 1, stale: true, warning: "stale" }]), "stale");
  assert.equal(deliveryVisualState([{ source: "delivery_ledger", available: true, generatedAtEpochMillis: 1, lastSuccessEpochMillis: 1, stale: false, warning: "" }], true), "no-sample");
});

test("clears the previous overview when project or window changes", () => {
  const previous = { projectId: "project-a", window: "24h" };
  assert.equal(isolateStaleResponse(previous, "project-b", "24h"), null);
  assert.equal(isolateStaleResponse(previous, "project-a", "7d"), null);
  assert.equal(isolateStaleResponse(previous, "project-a", "24h"), previous);
});

test("service functions target the four read-only delivery routes", () => {
  const service = readFileSync(new URL("../src/services/deliveryObservabilityService.ts", import.meta.url), "utf8");
  assert.match(service, /\/admin\/observability\/delivery\/overview/);
  assert.match(service, /\/admin\/observability\/delivery\/timeseries/);
  assert.match(service, /\/admin\/observability\/delivery\/failures/);
  assert.match(service, /\/admin\/observability\/delivery\/tasks/);
  assert.match(service, /serializeDeliveryQuery/);
});
