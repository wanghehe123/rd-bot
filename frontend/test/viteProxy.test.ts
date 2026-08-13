import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import viteConfig from "../vite.config.ts";

const OPENVIKING_ADMIN_API_PATHS = [
  "/admin/knowledge-base/123/openviking/overview",
  "/admin/knowledge-base/123/openviking/documents",
  "/admin/knowledge-base/123/openviking/documents/2001",
  "/admin/knowledge-base/123/openviking/tree",
  "/admin/knowledge-base/123/openviking/health",
  "/admin/knowledge-base/123/openviking/dead-letters",
  "/admin/knowledge-base/123/openviking/tombstones",
  "/admin/knowledge-base/123/openviking/documents/2001/retry",
  "/admin/knowledge-base/123/openviking/documents/2001/verify",
  "/admin/knowledge-base/123/openviking/documents/2001/rebuild",
  "/admin/knowledge-base/123/openviking/dead-letters/8101/requeue",
  "/admin/knowledge-base/123/openviking/reconcile"
];

const OPENVIKING_INVENTORY_API_PATHS = [
  "/admin/knowledge-base/123/openviking/inventory",
  "/admin/knowledge-base/123/openviking/inventory/candidates",
  "/admin/knowledge-base/123/openviking/inventory/duplicates",
  "/admin/knowledge-base/123/openviking/inventory/drift",
  "/admin/knowledge-base/123/openviking/inventory/backfill",
  "/admin/knowledge-base/123/openviking/inventory/duplicates/resolve"
];

test("proxies task draft API requests to Spring Boot", () => {
  assert.equal(typeof viteConfig, "object");
  const proxy = viteConfig.server?.proxy as Record<string, string | { target?: string }> | undefined;
  const draftProxy = proxy?.["/admin/rd-task-drafts"];
  const target = typeof draftProxy === "string" ? draftProxy : draftProxy?.target;

  assert.equal(target, "http://127.0.0.1:18080");
});

test("proxies dashboard overview API requests without hijacking dashboard navigation", () => {
  const proxy = viteConfig.server?.proxy as Record<string, string | { target?: string }> | undefined;
  const dashboardProxy = proxy?.["/admin/dashboard/overview"];
  const target = typeof dashboardProxy === "string" ? dashboardProxy : dashboardProxy?.target;

  assert.equal(target, "http://127.0.0.1:18080");
});

test("proxies execution trace APIs to Spring Boot", () => {
  const proxy = viteConfig.server?.proxy as Record<string, string | { target?: string }> | undefined;

  for (const path of ["/admin/execution-traces", "/admin/operations"]) {
    const item = proxy?.[path];
    const target = typeof item === "string" ? item : item?.target;
    assert.equal(target, "http://127.0.0.1:18080", `${path} should use the backend proxy`);
  }
});

test("proxies retrieval run and admin knowledge-base map APIs to Spring Boot", () => {
  const proxy = viteConfig.server?.proxy as Record<string, string | { target?: string }> | undefined;

  for (const path of ["/admin/rag-retrieval-runs", "/admin/knowledge-base"]) {
    const item = proxy?.[path];
    const target = typeof item === "string" ? item : item?.target;
    assert.equal(target, "http://127.0.0.1:18080", `${path} should use the backend proxy`);
  }
});

test("proxies AI delivery-review APIs to Spring Boot", () => {
  const proxy = viteConfig.server?.proxy as Record<string, string | { target?: string }> | undefined;
  const item = proxy?.["/admin/ai-reviews"];
  const target = typeof item === "string" ? item : item?.target;
  assert.equal(target, "http://127.0.0.1:18080");
});

test("proxies Web evaluation APIs to Spring Boot", () => {
  const proxy = viteConfig.server?.proxy as Record<string, string | { target?: string }> | undefined;
  const item = proxy?.["/admin/evaluations"];
  const target = typeof item === "string" ? item : item?.target;
  assert.equal(target, "http://127.0.0.1:18080");
});

test("only bypasses real task SPA routes while proxying nested task content APIs", () => {
  type TaskProxy = {
    bypass?: (request: {
      method?: string;
      url?: string;
      headers: Record<string, string>;
    }) => string | undefined;
  };
  const proxy = viteConfig.server?.proxy as Record<string, string | TaskProxy> | undefined;
  const taskProxy = proxy?.["/admin/rd-tasks"];
  assert.equal(typeof taskProxy, "object");
  const bypass = (taskProxy as TaskProxy).bypass;
  assert.equal(typeof bypass, "function");
  const navigation = (url: string) => ({
    method: "GET",
    url,
    headers: { accept: "text/html,application/xhtml+xml" }
  });

  assert.equal(bypass?.(navigation("/admin/rd-tasks")), "/admin/rd-tasks");
  assert.equal(bypass?.(navigation("/admin/rd-tasks/7480495920010891264?tab=qa")),
    "/admin/rd-tasks/7480495920010891264?tab=qa");
  for (const apiPath of [
    "/admin/rd-tasks/7480495920010891264/qa-evidence/artifact-1/content",
    "/admin/rd-tasks/7480495920010891264/materials/material-1/content",
    "/admin/rd-tasks/7480495920010891264/execution-overview"
  ]) {
    assert.equal(bypass?.(navigation(apiPath)), undefined, `${apiPath} must reach Spring Boot`);
  }
});

test("bypasses OpenViking knowledge SPA navigation but never nested knowledge-base APIs", () => {
  type ProxyEntry = {
    target?: string;
    bypass?: (request: {
      method?: string;
      url?: string;
      headers: Record<string, string>;
    }) => string | undefined;
  };
  const proxy = viteConfig.server?.proxy as Record<string, string | ProxyEntry> | undefined;
  const knowledgeBaseProxy = proxy?.["/admin/knowledge-base"];
  assert.equal(typeof knowledgeBaseProxy, "string", "/admin/knowledge-base must always reach Spring Boot");
  assert.equal(knowledgeBaseProxy, "http://127.0.0.1:18080");

  const knowledgeSpaKey = Object.keys(proxy || {}).find((key) => key.includes("/admin/knowledge") && key.includes("^"));
  assert.equal(typeof knowledgeSpaKey, "string");
  const knowledgeSpa = proxy?.[knowledgeSpaKey as string] as ProxyEntry;
  assert.equal(knowledgeSpa.target, "http://127.0.0.1:18080");
  const bypass = knowledgeSpa.bypass;
  assert.equal(typeof bypass, "function");

  const navigation = (url: string) => ({
    method: "GET",
    url,
    headers: { accept: "text/html,application/xhtml+xml" }
  });
  assert.equal(bypass?.(navigation("/admin/knowledge")), "/admin/knowledge");
  assert.equal(bypass?.(navigation("/admin/knowledge/123")), "/admin/knowledge/123");
  assert.equal(bypass?.(navigation("/admin/knowledge/123/docs/doc-1")), "/admin/knowledge/123/docs/doc-1");
  assert.equal(bypass?.(navigation("/admin/knowledge/123/openviking")), "/admin/knowledge/123/openviking");
  assert.equal(bypass?.(navigation("/admin/knowledge/123/openviking/")), "/admin/knowledge/123/openviking/");

  for (const apiPath of [...OPENVIKING_ADMIN_API_PATHS, ...OPENVIKING_INVENTORY_API_PATHS]) {
    assert.equal(bypass?.(navigation(apiPath)), undefined, `${apiPath} must not match the knowledge SPA bypass`);
  }
});

test("OpenViking service and SPA route stay aligned with the 12 nested admin APIs", () => {
  assert.equal(OPENVIKING_ADMIN_API_PATHS.length, 12);
  const service = readFileSync(new URL("../src/services/openVikingKnowledgeService.ts", import.meta.url), "utf8");
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /knowledge\/:kbId\/openviking/);
  assert.match(service, /\/admin\/knowledge-base\/\$\{kbId\}\/openviking/);
  for (const fragment of [
    "/overview",
    "/documents",
    "/tree",
    "/health",
    "/dead-letters",
    "/tombstones",
    "/retry",
    "/verify",
    "/rebuild",
    "/requeue",
    "/reconcile"
  ]) {
    assert.match(service, new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), fragment);
  }
  assert.doesNotMatch(service, /\/admin\/knowledge\/\$\{kbId\}\/openviking/);
});
