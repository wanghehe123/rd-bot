import { mkdir, rename, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";

const require = createRequire(import.meta.url);
const MAX_BASE_URL_CHARS = 2048;
const MAX_SELECTOR_CHARS = 2048;
const MAX_ROUTE_CHARS = 512;
const MAX_ARIA_VALUE_CHARS = 2048;
const MAX_OUTPUT_BYTES = 64 * 1024;
const MAX_TIMEOUT_MILLIS = 120_000;
const ARIA_ATTRIBUTE = /^aria-[a-z][a-z0-9-]{0,62}$/;
const BROWSER_KINDS = new Set(["BROWSER_DOM", "BROWSER_ARIA", "BROWSER_VISIBLE", "BROWSER_ROUTE"]);

/**
 * Runs the fixed, Host-owned browser inspection protocol used by ContainerHostBrowserProbe.
 * It never evaluates caller-provided JavaScript and only writes the bounded snapshot schema.
 */
export async function runHostBrowserProbe(rawArgs, dependencies = {}) {
  const writeError = typeof dependencies.writeError === "function"
    ? dependencies.writeError
    : (message) => process.stderr.write(`[rd-host-browser-probe] ${message}\n`);
  let browser;
  try {
    const request = parseRequest(rawArgs, dependencies.outputRoot ?? "/work/output");
    const chromium = dependencies.chromium ?? resolveChromium();
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.route("**/*", (route) => continueSameOrigin(route, request.baseUrl));
    await page.goto(request.navigationUrl.toString(), {
      waitUntil: "domcontentloaded",
      timeout: request.timeoutMillis,
    });
    const currentUrl = new URL(page.url());
    if (currentUrl.origin !== request.baseUrl.origin) {
      throw new Error("browser navigation left the Host base URL");
    }
    const snapshot = await captureSnapshot(page, request, currentUrl);
    await writeSnapshot(request.outputPath, snapshot);
    return 0;
  } catch (error) {
    writeError(safeError(error));
    return 1;
  } finally {
    if (browser) {
      try {
        await browser.close();
      } catch {
        // The immutable probe result is already authoritative.
      }
    }
  }
}

function parseRequest(rawArgs, outputRoot) {
  const options = namedOptions(rawArgs);
  const kind = required(options, "kind");
  if (!BROWSER_KINDS.has(kind)) {
    throw new Error("browser kind is invalid");
  }
  const baseUrl = parseBaseUrl(required(options, "base-url"));
  const timeoutMillis = parseTimeout(required(options, "timeout-millis"));
  const outputPath = fixedOutputPath(required(options, "output-path"), outputRoot);
  const requestedRoutePath = kind === "BROWSER_ROUTE" ? normalizedRoutePath(options.get("route-path") ?? "") : "";
  const selector = kind === "BROWSER_ROUTE" ? "" : parseSelector(required(options, "selector"));
  const ariaAttribute = kind === "BROWSER_ARIA" ? parseAriaAttribute(options.get("aria-attribute") ?? "") : "";
  if (kind !== "BROWSER_ARIA" && options.has("aria-attribute")) {
    throw new Error("ARIA attribute is only accepted for BROWSER_ARIA");
  }
  const navigationUrl = requestedRoutePath ? new URL(requestedRoutePath, baseUrl) : new URL(baseUrl);
  if (navigationUrl.origin !== baseUrl.origin) {
    throw new Error("browser route target must not select another origin");
  }
  return {
    kind,
    baseUrl,
    timeoutMillis,
    outputPath,
    selector,
    routePath: requestedRoutePath,
    ariaAttribute,
    navigationUrl,
  };
}

function namedOptions(rawArgs) {
  if (!Array.isArray(rawArgs) || rawArgs.length === 0 || rawArgs.length % 2 !== 0) {
    throw new Error("browser probe arguments must be named option pairs");
  }
  const allowed = new Set([
    "--kind",
    "--base-url",
    "--selector",
    "--route-path",
    "--aria-attribute",
    "--timeout-millis",
    "--output-path",
  ]);
  const options = new Map();
  for (let index = 0; index < rawArgs.length; index += 2) {
    const rawKey = rawArgs[index];
    const rawValue = rawArgs[index + 1];
    if (typeof rawKey !== "string" || typeof rawValue !== "string" || !allowed.has(rawKey) || options.has(rawKey)) {
      throw new Error("browser probe arguments are invalid");
    }
    options.set(rawKey, rawValue);
  }
  return new Map([...options].map(([key, value]) => [key.slice(2), value]));
}

function required(options, name) {
  const value = options.get(name);
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`browser ${name} is required`);
  }
  return value.trim();
}

function parseBaseUrl(rawValue) {
  const value = rawValue.trim();
  if (value.length > MAX_BASE_URL_CHARS || hasControlCharacter(value)) {
    throw new Error("browser base URL exceeds Host limits");
  }
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("browser base URL is invalid");
  }
  if (!new Set(["http:", "https:"]).has(url.protocol)
      || url.username !== ""
      || url.password !== ""
      || url.hostname === ""
      || url.search !== ""
      || url.hash !== "") {
    throw new Error("browser base URL must be an absolute credential-free HTTP URL");
  }
  return url;
}

function parseSelector(rawValue) {
  const value = rawValue.trim();
  if (value.length === 0
      || value.length > MAX_SELECTOR_CHARS
      || hasControlCharacter(value)
      || /^(?:javascript|data|file):/i.test(value)) {
    throw new Error("browser selector is invalid or exceeds Host limits");
  }
  return value;
}

function normalizedRoutePath(rawValue) {
  const value = rawValue.trim();
  if (value === "") return "";
  if (value.length > MAX_ROUTE_CHARS
      || !value.startsWith("/")
      || value.startsWith("//")
      || value.includes("\\")
      || value.includes("#")
      || hasControlCharacter(value)) {
    throw new Error("browser route target must be a bounded same-origin absolute path");
  }
  return value;
}

function parseAriaAttribute(rawValue) {
  const value = rawValue.trim().toLowerCase() || "aria-label";
  if (!ARIA_ATTRIBUTE.test(value)) {
    throw new Error("browser ARIA attribute is invalid");
  }
  return value;
}

function parseTimeout(rawValue) {
  if (!/^\d+$/.test(rawValue)) {
    throw new Error("browser timeout must be a positive integer");
  }
  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value <= 0 || value > MAX_TIMEOUT_MILLIS) {
    throw new Error("browser timeout exceeds Host limits");
  }
  return value;
}

function fixedOutputPath(rawValue, rawOutputRoot) {
  const outputRoot = resolve(rawOutputRoot);
  const target = resolve(rawValue);
  if (target !== join(outputRoot, "result.json")) {
    throw new Error("browser output path must be the fixed Host result.json path");
  }
  return target;
}

async function continueSameOrigin(route, baseUrl) {
  let requested;
  try {
    requested = new URL(route.request().url());
  } catch {
    return route.abort("blockedbyclient");
  }
  if (requested.origin !== baseUrl.origin) {
    return route.abort("blockedbyclient");
  }
  return route.continue();
}

async function captureSnapshot(page, request, currentUrl) {
  const snapshot = {
    version: 1,
    exists: false,
    visible: false,
    route: currentUrl.pathname,
    ariaAttributes: {},
  };
  if (request.kind === "BROWSER_ROUTE") {
    return snapshot;
  }
  const locator = page.locator(request.selector).first();
  snapshot.exists = (await locator.count()) > 0;
  if (!snapshot.exists) {
    if (request.ariaAttribute) snapshot.ariaAttributes[request.ariaAttribute] = "";
    return snapshot;
  }
  snapshot.visible = Boolean(await locator.isVisible({ timeout: Math.min(request.timeoutMillis, 1_000) }));
  if (request.ariaAttribute) {
    const value = await locator.getAttribute(request.ariaAttribute);
    if (typeof value === "string" && (value.length > MAX_ARIA_VALUE_CHARS || hasControlCharacter(value))) {
      throw new Error("browser ARIA value exceeds Host limits");
    }
    snapshot.ariaAttributes[request.ariaAttribute] = typeof value === "string" ? value : "";
  }
  return snapshot;
}

async function writeSnapshot(target, snapshot) {
  const serialized = `${JSON.stringify(snapshot)}\n`;
  if (Buffer.byteLength(serialized, "utf8") > MAX_OUTPUT_BYTES) {
    throw new Error("browser snapshot exceeds Host output limits");
  }
  await mkdir(dirname(target), { recursive: true });
  const temporary = `${target}.tmp-${process.pid}`;
  await writeFile(temporary, serialized, { encoding: "utf8", mode: 0o600 });
  await rename(temporary, target);
}

function resolveChromium() {
  try {
    const playwright = require("/usr/local/lib/node_modules/@playwright/cli/node_modules/playwright");
    if (playwright?.chromium) return playwright.chromium;
  } catch {
    // The QA image has the fixed global path. The fallback supports developer image layouts only.
  }
  try {
    const playwright = require("playwright");
    if (playwright?.chromium) return playwright.chromium;
  } catch {
    // A clear generic failure is emitted below without exposing host paths.
  }
  throw new Error("Host browser runtime is unavailable");
}

function hasControlCharacter(value) {
  return /[\u0000-\u001f\u007f]/.test(value);
}

function safeError(error) {
  const message = error instanceof Error ? error.message : "";
  if (/timeout/i.test(message)) return "Host browser probe timed out";
  if (message.startsWith("browser ") || message.startsWith("Host browser")) return message;
  return "Host browser probe failed";
}
