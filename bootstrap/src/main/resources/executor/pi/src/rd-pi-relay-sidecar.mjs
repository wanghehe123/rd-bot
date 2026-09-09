import http from "node:http";
import { pathToFileURL } from "node:url";

const DEFAULT_PORT = 8787;
const DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024;
const DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
const DEFAULT_TIMEOUT_MILLIS = 60_000;
const REQUEST_HEADER_ALLOWLIST = new Set([
  "accept",
  "content-type",
  "anthropic-version",
  "anthropic-beta",
  "openai-organization",
  "openai-project",
  "user-agent",
]);
const RESPONSE_HEADER_ALLOWLIST = new Set([
  "content-type",
  "cache-control",
  "request-id",
  "x-request-id",
  "anthropic-ratelimit-requests-limit",
  "anthropic-ratelimit-requests-remaining",
  "anthropic-ratelimit-tokens-limit",
  "anthropic-ratelimit-tokens-remaining",
]);

/**
 * Creates the task-local credential relay sidecar. It accepts a provider-shaped
 * Pi request, preserves only approved request headers, and forwards it to the
 * single Host proxy URL with bindings controlled by the sidecar environment.
 */
export function createRelayServer(configuration, dependencies = {}) {
  const settings = normalizeConfiguration(configuration);
  const fetchImpl = typeof dependencies.fetchImpl === "function" ? dependencies.fetchImpl : fetch;

  return http.createServer(async (request, response) => {
    try {
      if (request.method === "GET" && request.url === "/healthz") {
        writeEmpty(response, 204);
        return;
      }
      if (request.method !== "POST") {
        writeEmpty(response, 405, { allow: "POST" });
        return;
      }

      const path = providerPath(request.url);
      if (!path) {
        writeEmpty(response, 400);
        return;
      }
      const lease = bearerToken(request.headers.authorization);
      if (!lease) {
        writeEmpty(response, 401);
        return;
      }

      const requestBody = await readBoundedRequestBody(request, settings.maxRequestBytes);
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), settings.timeoutMillis);
      try {
        const upstream = await fetchImpl(settings.hostRelayUrl, {
          method: "POST",
          headers: hostHeaders(request.headers, lease, settings, path),
          body: requestBody,
          signal: controller.signal,
        });
        const responseBody = await readBoundedResponseBody(upstream, settings.maxResponseBytes);
        writeResponse(response, upstream.status, upstream.headers, responseBody);
      } finally {
        clearTimeout(timeout);
      }
    } catch (error) {
      if (error?.code === "REQUEST_TOO_LARGE") {
        writeEmpty(response, 413);
        return;
      }
      if (error?.code === "RESPONSE_TOO_LARGE") {
        writeEmpty(response, 502);
        return;
      }
      // A relay error is deliberately bodyless: provider diagnostics and Host
      // topology must not cross the untrusted Pi boundary.
      writeEmpty(response, 502);
    }
  });
}

function normalizeConfiguration(value) {
  const configuration = value && typeof value === "object" ? value : {};
  const hostRelayUrl = absoluteHttpUrl(configuration.hostRelayUrl, "hostRelayUrl");
  return {
    hostRelayUrl,
    taskId: requiredText(configuration.taskId, "taskId"),
    stageRunId: requiredText(configuration.stageRunId, "stageRunId"),
    providerId: requiredText(configuration.providerId, "providerId"),
    maxRequestBytes: positiveInteger(configuration.maxRequestBytes, DEFAULT_MAX_REQUEST_BYTES),
    maxResponseBytes: positiveInteger(configuration.maxResponseBytes, DEFAULT_MAX_RESPONSE_BYTES),
    timeoutMillis: positiveInteger(configuration.timeoutMillis, DEFAULT_TIMEOUT_MILLIS),
  };
}

function absoluteHttpUrl(value, field) {
  const text = requiredText(value, field);
  let parsed;
  try {
    parsed = new URL(text);
  } catch {
    throw new Error(`${field} must be an absolute HTTP(S) URL`);
  }
  if ((parsed.protocol !== "http:" && parsed.protocol !== "https:")
      || !parsed.hostname
      || parsed.username
      || parsed.password
      || parsed.search
      || parsed.hash) {
    throw new Error(`${field} must be an absolute HTTP(S) URL without credentials, query, or fragment`);
  }
  return parsed.toString();
}

function requiredText(value, field) {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text) throw new Error(`${field} must not be blank`);
  return text;
}

function positiveInteger(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error("relay bounds must be positive integers");
  }
  return parsed;
}

function providerPath(rawUrl) {
  if (typeof rawUrl !== "string" || rawUrl === "" || rawUrl.includes("#")) return "";
  let parsed;
  try {
    parsed = new URL(rawUrl, "http://rd-pi-relay.invalid");
  } catch {
    return "";
  }
  // Anthropic SDK >= 5.30 appends `?beta=true` to beta message routes. The query
  // never propagates (the Host proxy rebuilds the upstream URL from the path
  // header alone), so a query is stripped here instead of rejected — rejecting
  // would break every SDK release that appends benign query params.
  if (!parsed.pathname.startsWith("/") || parsed.pathname.includes("//")) {
    return "";
  }
  const path = parsed.pathname;
  if (path.split("/").some((segment) => segment === "." || segment === "..")) return "";
  return path;
}

function bearerToken(value) {
  const header = Array.isArray(value) ? value[0] : value;
  if (typeof header !== "string" || !header.startsWith("Bearer ")) return "";
  const token = header.slice("Bearer ".length).trim();
  return token && !/\s/.test(token) ? token : "";
}

function hostHeaders(incomingHeaders, lease, settings, path) {
  const headers = {
    authorization: `Bearer ${lease}`,
    "x-rd-pi-relay-task-id": settings.taskId,
    "x-rd-pi-relay-stage-run-id": settings.stageRunId,
    "x-rd-pi-relay-provider-id": settings.providerId,
    "x-rd-pi-relay-method": "POST",
    "x-rd-pi-relay-path": path,
  };
  for (const [name, value] of Object.entries(incomingHeaders ?? {})) {
    const normalizedName = name.toLowerCase();
    if (!REQUEST_HEADER_ALLOWLIST.has(normalizedName)) continue;
    const normalizedValue = headerValue(value);
    if (normalizedValue) headers[normalizedName] = normalizedValue;
  }
  return headers;
}

function headerValue(value) {
  const candidate = Array.isArray(value) ? value[0] : value;
  if (typeof candidate !== "string") return "";
  const normalized = candidate.trim();
  return normalized.includes("\r") || normalized.includes("\n") ? "" : normalized;
}

async function readBoundedRequestBody(request, maxBytes) {
  const contentLength = Number(request.headers["content-length"] ?? 0);
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    request.resume();
    throw boundedError("REQUEST_TOO_LARGE");
  }
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    bytes += buffer.length;
    if (bytes > maxBytes) {
      request.resume();
      throw boundedError("REQUEST_TOO_LARGE");
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks, bytes);
}

async function readBoundedResponseBody(upstream, maxBytes) {
  const contentLength = Number(upstream.headers?.get?.("content-length") ?? 0);
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    throw boundedError("RESPONSE_TOO_LARGE");
  }
  if (!upstream.body) return Buffer.alloc(0);
  const reader = upstream.body.getReader();
  const chunks = [];
  let bytes = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      const chunk = Buffer.from(next.value);
      bytes += chunk.length;
      if (bytes > maxBytes) {
        await reader.cancel();
        throw boundedError("RESPONSE_TOO_LARGE");
      }
      chunks.push(chunk);
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks, bytes);
}

function boundedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function writeResponse(response, status, upstreamHeaders, body) {
  const headers = {};
  for (const [name, value] of upstreamHeaders?.entries?.() ?? []) {
    const normalizedName = name.toLowerCase();
    if (!RESPONSE_HEADER_ALLOWLIST.has(normalizedName)) continue;
    const normalizedValue = headerValue(value);
    if (normalizedValue) headers[normalizedName] = normalizedValue;
  }
  response.writeHead(validStatus(status), headers);
  response.end(body);
}

function writeEmpty(response, status, headers = {}) {
  response.writeHead(status, headers);
  response.end();
}

function validStatus(status) {
  const parsed = Number(status);
  return Number.isInteger(parsed) && parsed >= 100 && parsed <= 599 ? parsed : 502;
}

function configurationFromEnvironment(env = process.env) {
  return {
    hostRelayUrl: env.RD_PI_RELAY_HOST_URL,
    taskId: env.RD_PI_RELAY_TASK_ID,
    stageRunId: env.RD_PI_RELAY_STAGE_RUN_ID,
    providerId: env.RD_PI_RELAY_PROVIDER_ID,
    maxRequestBytes: env.RD_PI_RELAY_MAX_REQUEST_BYTES,
    maxResponseBytes: env.RD_PI_RELAY_MAX_RESPONSE_BYTES,
    timeoutMillis: env.RD_PI_RELAY_TIMEOUT_MILLIS,
  };
}

function portFromEnvironment(env = process.env) {
  return positiveInteger(env.RD_PI_RELAY_PORT, DEFAULT_PORT);
}

function isMainModule() {
  return process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
}

if (isMainModule()) {
  const server = createRelayServer(configurationFromEnvironment());
  server.listen(portFromEnvironment(), "0.0.0.0");
}
