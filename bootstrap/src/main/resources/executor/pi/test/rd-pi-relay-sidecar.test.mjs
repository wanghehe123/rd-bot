import assert from "node:assert/strict";
import test from "node:test";

import { createRelayServer } from "../src/rd-pi-relay-sidecar.mjs";

test("forwards only a bounded Pi POST to the fixed Host proxy with sidecar-owned bindings", async (t) => {
  const forwarded = [];
  const server = createRelayServer({
    hostRelayUrl: "http://host.docker.internal:18080/internal/pi/credential-relay/proxy",
    taskId: "task-1",
    stageRunId: "stage-1",
    providerId: "provider-1",
    maxRequestBytes: 128,
    maxResponseBytes: 128,
    timeoutMillis: 1_000,
  }, {
    fetchImpl: async (url, init) => {
      forwarded.push({ url, init });
      return new Response("{\"id\":\"reply-1\"}", {
        status: 200,
        headers: {
          "content-type": "application/json",
          "x-upstream-secret": "must-not-return",
        },
      });
    },
  });
  const address = await listen(server);
  t.after(() => close(server));

  const response = await fetch(`${address}/chat/completions`, {
    method: "POST",
    headers: {
      authorization: "Bearer pcl_opaque_lease",
      "content-type": "application/json",
      "x-rd-pi-relay-task-id": "attacker-controlled",
      "x-untrusted": "discard-me",
    },
    body: "{}",
  });

  assert.equal(response.status, 200);
  assert.equal(await response.text(), "{\"id\":\"reply-1\"}");
  assert.equal(response.headers.get("content-type"), "application/json");
  assert.equal(response.headers.get("x-upstream-secret"), null);
  assert.equal(forwarded.length, 1);
  assert.equal(forwarded[0].url, "http://host.docker.internal:18080/internal/pi/credential-relay/proxy");
  assert.equal(forwarded[0].init.method, "POST");
  assert.equal(forwarded[0].init.headers.authorization, "Bearer pcl_opaque_lease");
  assert.equal(forwarded[0].init.headers["x-rd-pi-relay-task-id"], "task-1");
  assert.equal(forwarded[0].init.headers["x-rd-pi-relay-stage-run-id"], "stage-1");
  assert.equal(forwarded[0].init.headers["x-rd-pi-relay-provider-id"], "provider-1");
  assert.equal(forwarded[0].init.headers["x-rd-pi-relay-method"], "POST");
  assert.equal(forwarded[0].init.headers["x-rd-pi-relay-path"], "/chat/completions");
  assert.equal(forwarded[0].init.headers["x-untrusted"], undefined);
});

test("rejects arbitrary methods, query routes, missing leases, and oversized bodies before the Host proxy", async (t) => {
  let calls = 0;
  const server = createRelayServer({
    hostRelayUrl: "http://host.docker.internal:18080/internal/pi/credential-relay/proxy",
    taskId: "task-1",
    stageRunId: "stage-1",
    providerId: "provider-1",
    maxRequestBytes: 4,
    maxResponseBytes: 32,
    timeoutMillis: 1_000,
  }, {
    fetchImpl: async () => {
      calls += 1;
      return new Response("{}", { status: 200 });
    },
  });
  const address = await listen(server);
  t.after(() => close(server));

  assert.equal((await fetch(`${address}/healthz`)).status, 204);
  assert.equal((await fetch(`${address}/chat/completions`)).status, 405);
  assert.equal((await fetch(`${address}/chat/completions?escape=true`, {
    method: "POST",
    headers: { authorization: "Bearer pcl_opaque_lease" },
    body: "{}",
  })).status, 400);
  assert.equal((await fetch(`${address}/chat/completions`, {
    method: "POST",
    body: "{}",
  })).status, 401);
  assert.equal((await fetch(`${address}/chat/completions`, {
    method: "POST",
    headers: { authorization: "Bearer pcl_opaque_lease" },
    body: "12345",
  })).status, 413);
  assert.equal(calls, 0);
});

test("bounds Host responses and hides upstream failures from the Pi runtime", async (t) => {
  const server = createRelayServer({
    hostRelayUrl: "http://host.docker.internal:18080/internal/pi/credential-relay/proxy",
    taskId: "task-1",
    stageRunId: "stage-1",
    providerId: "provider-1",
    maxRequestBytes: 128,
    maxResponseBytes: 4,
    timeoutMillis: 1_000,
  }, {
    fetchImpl: async () => new Response("oversized", {
      status: 500,
      headers: { "content-type": "text/plain" },
    }),
  });
  const address = await listen(server);
  t.after(() => close(server));

  const response = await fetch(`${address}/chat/completions`, {
    method: "POST",
    headers: { authorization: "Bearer pcl_opaque_lease" },
    body: "{}",
  });

  assert.equal(response.status, 502);
  assert.equal(await response.text(), "");
  assert.equal(response.headers.get("content-type"), null);
});

test("returns a bodyless gateway error when the fixed Host proxy cannot be reached", async (t) => {
  const server = createRelayServer({
    hostRelayUrl: "http://host.docker.internal:18080/internal/pi/credential-relay/proxy",
    taskId: "task-1",
    stageRunId: "stage-1",
    providerId: "provider-1",
    maxRequestBytes: 128,
    maxResponseBytes: 128,
    timeoutMillis: 1_000,
  }, {
    fetchImpl: async () => {
      throw new Error("upstream failed with provider-secret");
    },
  });
  const address = await listen(server);
  t.after(() => close(server));

  const response = await fetch(`${address}/chat/completions`, {
    method: "POST",
    headers: { authorization: "Bearer pcl_opaque_lease" },
    body: "{}",
  });

  assert.equal(response.status, 502);
  assert.equal(await response.text(), "");
});

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  return `http://127.0.0.1:${address.port}`;
}

async function close(server) {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}
