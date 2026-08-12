import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { runHostBrowserProbe } from "../src/host-browser-probe.mjs";

test("captures DOM, ARIA, visibility, and route from a fixed host browser request", async () => {
  const outputRoot = await mkdtemp(join(tmpdir(), "rd-host-browser-probe-"));
  const observed = { goto: "", selector: "", routed: false, closed: false };
  const page = {
    async route(pattern, handler) {
      observed.routed = pattern === "**/*";
      await handler({
        request: () => ({ url: () => "http://127.0.0.1:3000/assets/app.js" }),
        continue: async () => {},
        abort: async () => { throw new Error("same-origin request should not be blocked"); },
      });
    },
    async goto(url) {
      observed.goto = url;
    },
    url() {
      return "http://127.0.0.1:3000/orders/42?ignored=true";
    },
    locator(selector) {
      observed.selector = selector;
      return {
        first() {
          return this;
        },
        async count() {
          return 1;
        },
        async isVisible() {
          return true;
        },
        async getAttribute(attribute) {
          return attribute === "aria-label" ? "Save order" : null;
        },
      };
    },
  };
  const browser = {
    async newPage() {
      return page;
    },
    async close() {
      observed.closed = true;
    },
  };

  const exitCode = await runHostBrowserProbe([
    "--kind", "BROWSER_ARIA",
    "--base-url", "http://127.0.0.1:3000",
    "--selector", "[data-testid='save']",
    "--aria-attribute", "aria-label",
    "--timeout-millis", "1000",
    "--output-path", join(outputRoot, "result.json"),
  ], {
    chromium: { launch: async () => browser },
    outputRoot,
    writeError: () => {},
  });

  assert.equal(exitCode, 0);
  assert.equal(observed.goto, "http://127.0.0.1:3000/");
  assert.equal(observed.selector, "[data-testid='save']");
  assert.equal(observed.routed, true);
  assert.equal(observed.closed, true);
  assert.deepEqual(JSON.parse(await readFile(join(outputRoot, "result.json"), "utf8")), {
    version: 1,
    exists: true,
    visible: true,
    route: "/orders/42",
    ariaAttributes: { "aria-label": "Save order" },
  });
});

test("navigates only a same-origin route target", async () => {
  const outputRoot = await mkdtemp(join(tmpdir(), "rd-host-browser-route-"));
  let navigated = "";
  const exitCode = await runHostBrowserProbe([
    "--kind", "BROWSER_ROUTE",
    "--base-url", "https://example.test/app",
    "--route-path", "/orders/42",
    "--timeout-millis", "1000",
    "--output-path", join(outputRoot, "result.json"),
  ], {
    chromium: {
      launch: async () => ({
        newPage: async () => ({
          route: async () => {},
          goto: async (url) => { navigated = url; },
          url: () => "https://example.test/orders/42",
        }),
        close: async () => {},
      }),
    },
    outputRoot,
    writeError: () => {},
  });

  assert.equal(exitCode, 0);
  assert.equal(navigated, "https://example.test/orders/42");
  assert.equal(JSON.parse(await readFile(join(outputRoot, "result.json"), "utf8")).route, "/orders/42");
});

test("blocks redirected documents and subresources that leave the Host origin", async () => {
  const outputRoot = await mkdtemp(join(tmpdir(), "rd-host-browser-origin-"));
  const blocked = [];
  const exitCode = await runHostBrowserProbe([
    "--kind", "BROWSER_ROUTE",
    "--base-url", "https://example.test/",
    "--timeout-millis", "1000",
    "--output-path", join(outputRoot, "result.json"),
  ], {
    chromium: {
      launch: async () => ({
        newPage: async () => ({
          route: async (_pattern, handler) => handler({
            request: () => ({ url: () => "https://other.test/redirected.js" }),
            continue: async () => { throw new Error("cross-origin request must not continue"); },
            abort: async (reason) => { blocked.push(reason); },
          }),
          goto: async () => {},
          url: () => "https://example.test/",
        }),
        close: async () => {},
      }),
    },
    outputRoot,
    writeError: () => {},
  });

  assert.equal(exitCode, 0);
  assert.deepEqual(blocked, ["blockedbyclient"]);
});

test("rejects credential URLs and hostile selectors before launching a browser", async () => {
  const outputRoot = await mkdtemp(join(tmpdir(), "rd-host-browser-invalid-"));
  let launches = 0;
  const chromium = { launch: async () => { launches += 1; throw new Error("must not launch"); } };
  const errors = [];

  const credentialUrl = await runHostBrowserProbe([
    "--kind", "BROWSER_DOM",
    "--base-url", "http://user:password@127.0.0.1:3000",
    "--selector", "#save",
    "--timeout-millis", "1000",
    "--output-path", join(outputRoot, "result.json"),
  ], { chromium, outputRoot, writeError: (message) => errors.push(message) });
  const selector = await runHostBrowserProbe([
    "--kind", "BROWSER_DOM",
    "--base-url", "http://127.0.0.1:3000",
    "--selector", "javascript:alert(1)",
    "--timeout-millis", "1000",
    "--output-path", join(outputRoot, "result.json"),
  ], { chromium, outputRoot, writeError: (message) => errors.push(message) });

  assert.equal(credentialUrl, 1);
  assert.equal(selector, 1);
  assert.equal(launches, 0);
  assert.match(errors.join("\n"), /base URL|selector/i);
});

test("returns failure when browser navigation exceeds the fixed timeout", async () => {
  const outputRoot = await mkdtemp(join(tmpdir(), "rd-host-browser-timeout-"));
  const errors = [];
  const exitCode = await runHostBrowserProbe([
    "--kind", "BROWSER_VISIBLE",
    "--base-url", "http://127.0.0.1:3000",
    "--selector", "#save",
    "--timeout-millis", "1000",
    "--output-path", join(outputRoot, "result.json"),
  ], {
    chromium: {
      launch: async () => ({
        newPage: async () => ({
          route: async () => {},
          goto: async () => { throw new Error("Navigation timeout of 1000ms exceeded"); },
        }),
        close: async () => {},
      }),
    },
    outputRoot,
    writeError: (message) => errors.push(message),
  });

  assert.equal(exitCode, 1);
  assert.match(errors.join("\n"), /timed out|timeout/i);
});
