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
