import assert from "node:assert/strict";
import { chmod, mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("../../main/resources/executor/pi/rd-qa-evidence.mjs", import.meta.url));

test("records real command status and redacts credential values", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "rd-qa-evidence-"));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  await mkdir(repo, { recursive: true });
  const result = spawnSync(process.execPath, [
    script,
    "run",
    "--scope",
    "CURRENT",
    "--id",
    "current-api",
    "--",
    "/bin/sh",
    "-c",
    "printf '%s' \"$SECRET_TOKEN\""
  ], {
    encoding: "utf8",
    env: {
      ...process.env,
      RD_QA_OUTPUT_DIR: output,
      RD_QA_REPO_DIR: repo,
      SECRET_TOKEN: "plain-secret-value"
    }
  });

  assert.equal(result.status, 0, result.stderr);
  const log = await readFile(path.join(output, "qa-evidence/commands/current-api.log"), "utf8");
  assert.match(log, /exitCode=0/);
  assert.match(log, /\[REDACTED:SECRET_TOKEN\]/);
  assert.doesNotMatch(log, /plain-secret-value/);
});

test("preserves every command attempt instead of overwriting prior evidence", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "rd-qa-command-attempts-"));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  await mkdir(repo, { recursive: true });
  const env = { ...process.env, RD_QA_OUTPUT_DIR: output, RD_QA_REPO_DIR: repo };
  const commandPrefix = [script, "run", "--scope", "REGRESSION", "--id", "regression-flow", "--", "/bin/sh", "-c"];

  const first = spawnSync(process.execPath, [...commandPrefix, "printf first; exit 7"], {
    encoding: "utf8",
    env
  });
  const second = spawnSync(process.execPath, [...commandPrefix, "printf second"], {
    encoding: "utf8",
    env
  });

  assert.equal(first.status, 7, first.stderr);
  assert.equal(second.status, 0, second.stderr);
  assert.match(first.stdout, /qaEvidenceArtifact=qa-evidence\/commands\/regression-flow\.log/);
  assert.match(second.stdout, /qaEvidenceArtifact=qa-evidence\/commands\/regression-flow-attempt-2\.log/);
  const firstLog = await readFile(path.join(output, "qa-evidence/commands/regression-flow.log"), "utf8");
  const secondLog = await readFile(
    path.join(output, "qa-evidence/commands/regression-flow-attempt-2.log"),
    "utf8"
  );
  assert.match(firstLog, /exitCode=7/);
  assert.match(firstLog, /first/);
  assert.match(secondLog, /exitCode=0/);
  assert.match(secondLog, /second/);
});

test("writes a deterministic integrity manifest for nested evidence", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "rd-qa-manifest-"));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  await mkdir(repo, { recursive: true });
  await mkdir(path.join(output, "qa-evidence/screenshots"), { recursive: true });
  await writeFile(path.join(output, "qa-evidence/screenshots/current.png"), "png");

  const result = spawnSync(process.execPath, [script, "manifest"], {
    encoding: "utf8",
    env: { ...process.env, RD_QA_OUTPUT_DIR: output, RD_QA_REPO_DIR: repo }
  });

  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(await readFile(path.join(output, "qa-evidence/manifest.json"), "utf8"));
  assert.deepEqual(manifest.artifacts.map((artifact) => artifact.path), [
    "qa-evidence/screenshots/current.png"
  ]);
  assert.equal(manifest.artifacts[0].bytes, 3);
  assert.match(manifest.artifacts[0].sha256, /^[a-f0-9]{64}$/);
});

test("redacts structured secrets and HTTP credential headers before evidence is manifested", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "rd-qa-redact-"));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  const evidenceFile = path.join(output, "qa-evidence/http/current.json");
  await mkdir(repo, { recursive: true });
  await mkdir(path.dirname(evidenceFile), { recursive: true });
  await writeFile(evidenceFile, JSON.stringify({
    authorization: "Bearer raw-bearer-value",
    nested: { cookie: "session=raw-cookie", safe: "kept" },
    response: "raw-environment-secret"
  }));

  const result = spawnSync(process.execPath, [script, "redact", evidenceFile], {
    encoding: "utf8",
    env: {
      ...process.env,
      RD_QA_OUTPUT_DIR: output,
      RD_QA_REPO_DIR: repo,
      SECRET_TOKEN: "raw-environment-secret"
    }
  });

  assert.equal(result.status, 0, result.stderr);
  const redacted = await readFile(evidenceFile, "utf8");
  const parsed = JSON.parse(redacted);
  assert.equal(parsed.authorization, "[REDACTED]");
  assert.equal(parsed.nested.cookie, "[REDACTED]");
  assert.equal(parsed.nested.safe, "kept");
  assert.doesNotMatch(redacted, /raw-bearer-value|raw-cookie|raw-environment-secret/);
});

test("packages a Playwright trace only after redacting embedded credentials", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "rd-qa-trace-"));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  const source = path.join(output, "qa-work/playwright/traces");
  const target = path.join(output, "qa-evidence/traces/current.zip");
  await mkdir(repo, { recursive: true });
  await mkdir(path.join(source, "resources"), { recursive: true });
  await writeFile(path.join(source, "trace.trace"),
    '{"name":"Authorization","value":"Bearer trace-bearer-secret"}\n');
  await writeFile(path.join(source, "trace.network"), [
    '{"name":"Cookie","value":"session=trace-cookie-secret"}',
    '{"name":"token","value":"trace-query-secret"}'
  ].join("\n"));
  await writeFile(path.join(source, "resources/body.json"),
    '{"apiKey":"trace-api-key-secret","safe":"kept"}');

  const result = spawnSync(process.execPath, [
    script,
    "trace",
    "--source",
    source,
    "--output",
    target
  ], {
    encoding: "utf8",
    env: { ...process.env, RD_QA_OUTPUT_DIR: output, RD_QA_REPO_DIR: repo }
  });

  assert.equal(result.status, 0, result.stderr);
  const traceText = spawnSync("unzip", ["-p", target, "trace.trace"], { encoding: "utf8" });
  const networkText = spawnSync("unzip", ["-p", target, "trace.network"], { encoding: "utf8" });
  const bodyText = spawnSync("unzip", ["-p", target, "resources/body.json"], { encoding: "utf8" });
  assert.equal(traceText.status, 0, traceText.stderr);
  assert.equal(networkText.status, 0, networkText.stderr);
  assert.equal(bodyText.status, 0, bodyText.stderr);
  const combined = traceText.stdout + networkText.stdout + bodyText.stdout;
  assert.doesNotMatch(combined, /trace-bearer-secret|trace-cookie-secret|trace-query-secret|trace-api-key-secret/);
  assert.match(combined, /\[REDACTED\]/);
  assert.match(bodyText.stdout, /kept/);
});

test("turns Playwright CLI JSON errors into a non-zero process status", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "rd-qa-playwright-wrapper-"));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  const bin = path.join(root, "bin");
  const fakeCli = path.join(bin, "playwright-cli");
  await mkdir(repo, { recursive: true });
  await mkdir(bin, { recursive: true });
  await writeFile(fakeCli,
    "#!/bin/sh\nprintf '%s\\n' '{\"isError\":true,\"error\":\"assertion failed Bearer wrapper-secret\"}'\n");
  await chmod(fakeCli, 0o755);

  const result = spawnSync(process.execPath, [script, "playwright", "run-code", "throw"], {
    encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      RD_QA_OUTPUT_DIR: output,
      RD_QA_REPO_DIR: repo
    }
  });

  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stdout, /assertion failed/);
  assert.match(result.stdout, /Bearer \[REDACTED\]/);
  assert.doesNotMatch(result.stdout, /wrapper-secret/);
});
