import assert from "node:assert/strict";
import test from "node:test";

import viteConfig from "../vite.config.ts";

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
