#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const outputRoot = path.resolve(process.env.RD_QA_OUTPUT_DIR || "/work/output");
const evidenceRoot = path.join(outputRoot, "qa-evidence");
const repoRoot = path.resolve(process.env.RD_QA_REPO_DIR || "/work/repo");
const [action, ...args] = process.argv.slice(2);

if (action === "run") {
  process.exit(runCommand(args));
}
if (action === "manifest") {
  writeManifest();
  process.exit(0);
}
if (action === "redact") {
  redactEvidenceFiles(args);
  process.exit(0);
}
if (action === "trace") {
  packageTrace(args);
  process.exit(0);
}
if (action === "playwright") {
  process.exit(runPlaywright(args));
}

console.error("usage: rd-qa-evidence.mjs run --scope CURRENT|REGRESSION --id <id> -- <command> [args...]");
console.error("       rd-qa-evidence.mjs manifest");
console.error("       rd-qa-evidence.mjs redact <qa-evidence-file> [more-files...]");
console.error("       rd-qa-evidence.mjs trace --source <qa-work-trace-dir> --output <qa-evidence-trace.zip>");
console.error("       rd-qa-evidence.mjs playwright <playwright-cli-args...>");
process.exit(64);

function runCommand(rawArgs) {
  const separator = rawArgs.indexOf("--");
  if (separator < 0 || separator === rawArgs.length - 1) {
    throw new Error("run requires -- followed by a command");
  }
  const options = parseOptions(rawArgs.slice(0, separator));
  const command = rawArgs.slice(separator + 1);
  const startedAt = new Date();
  const result = spawnSync(command[0], command.slice(1), {
    cwd: repoRoot,
    env: process.env,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    timeout: Number(process.env.RD_QA_COMMAND_TIMEOUT_MILLIS || 1_200_000),
  });
  const endedAt = new Date();
  const exitCode = Number.isInteger(result.status) ? result.status : 1;
  const signal = result.signal || "";
  const log = [
    `scope=${options.scope}`,
    `id=${options.id}`,
    `startedAt=${startedAt.toISOString()}`,
    `endedAt=${endedAt.toISOString()}`,
    `durationMillis=${endedAt.getTime() - startedAt.getTime()}`,
    `command=${JSON.stringify(command.map((item) => redact(item)))}`,
    `exitCode=${exitCode}`,
    `signal=${signal}`,
    "",
    "[stdout]",
    sanitizeEvidenceText(result.stdout || ""),
    "",
    "[stderr]",
    sanitizeEvidenceText(result.stderr || result.error?.message || ""),
    "",
  ].join("\n");
  const directory = path.join(evidenceRoot, "commands");
  fs.mkdirSync(directory, { recursive: true });
  const logPath = writeUniqueCommandLog(directory, options.id, log);
  const artifactPath = path.relative(outputRoot, logPath).split(path.sep).join("/");
  fs.writeSync(process.stdout.fd, `qaEvidenceArtifact=${artifactPath}\n`);
  return exitCode;
}

function writeUniqueCommandLog(directory, id, log) {
  for (let attempt = 1; ; attempt += 1) {
    const suffix = attempt === 1 ? "" : `-attempt-${attempt}`;
    const target = path.join(directory, `${id}${suffix}.log`);
    try {
      fs.writeFileSync(target, log, { encoding: "utf8", flag: "wx" });
      return target;
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
    }
  }
}

function runPlaywright(args) {
  if (args.length === 0) {
    console.error("playwright requires a CLI command");
    return 64;
  }
  const result = spawnSync("playwright-cli", ["--json", ...args], {
    cwd: repoRoot,
    env: process.env,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    timeout: Number(process.env.RD_QA_COMMAND_TIMEOUT_MILLIS || 1_200_000),
  });
  if (result.stdout) process.stdout.write(sanitizeEvidenceText(result.stdout));
  if (result.stderr) process.stderr.write(sanitizeEvidenceText(result.stderr));
  if (result.error) {
    console.error(sanitizeEvidenceText(result.error.message));
    return 1;
  }
  if (result.status !== 0) return Number.isInteger(result.status) ? result.status : 1;
  try {
    const response = JSON.parse((result.stdout || "").trim());
    if (response?.isError === true) return 1;
    return 0;
  } catch {
    console.error("playwright-cli did not return a valid JSON result");
    return 1;
  }
}

function parseOptions(rawArgs) {
  const options = { scope: "", id: "" };
  for (let index = 0; index < rawArgs.length; index += 2) {
    const key = rawArgs[index];
    const value = rawArgs[index + 1] || "";
    if (key === "--scope") options.scope = value.toUpperCase();
    if (key === "--id") options.id = value;
  }
  if (!new Set(["CURRENT", "REGRESSION"]).has(options.scope)) {
    throw new Error("scope must be CURRENT or REGRESSION");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(options.id)) {
    throw new Error("id must contain only letters, numbers, dot, underscore or dash");
  }
  return options;
}

function writeManifest() {
  fs.mkdirSync(evidenceRoot, { recursive: true });
  const manifestPath = path.join(evidenceRoot, "manifest.json");
  const artifacts = walk(evidenceRoot)
    .filter((file) => file !== manifestPath)
    .map((file) => {
      const content = fs.readFileSync(file);
      return {
        path: path.relative(outputRoot, file).split(path.sep).join("/"),
        bytes: content.length,
        sha256: crypto.createHash("sha256").update(content).digest("hex"),
      };
    })
    .sort((left, right) => left.path.localeCompare(right.path));
  fs.writeFileSync(manifestPath, `${JSON.stringify({ version: 1, artifacts }, null, 2)}\n`, "utf8");
}

function redactEvidenceFiles(files) {
  if (files.length === 0) throw new Error("redact requires at least one evidence file");
  for (const file of files) {
    const target = path.resolve(file);
    if (!target.startsWith(`${evidenceRoot}${path.sep}`)) {
      throw new Error(`redact target must be under ${evidenceRoot}`);
    }
    const stat = fs.lstatSync(target);
    if (!stat.isFile() || stat.isSymbolicLink()) {
      throw new Error(`redact target must be a regular non-symlink file: ${target}`);
    }
    const content = fs.readFileSync(target, "utf8");
    fs.writeFileSync(target, sanitizeEvidenceText(content), "utf8");
  }
}

function packageTrace(rawArgs) {
  const options = namedOptions(rawArgs);
  const qaWorkRoot = path.join(outputRoot, "qa-work");
  const traceEvidenceRoot = path.join(evidenceRoot, "traces");
  const source = path.resolve(options.source || "");
  const target = path.resolve(options.output || "");
  if (!source.startsWith(`${qaWorkRoot}${path.sep}`)) {
    throw new Error(`trace source must be under ${qaWorkRoot}`);
  }
  if (!target.startsWith(`${traceEvidenceRoot}${path.sep}`) || !target.endsWith(".zip")) {
    throw new Error(`trace output must be a .zip under ${traceEvidenceRoot}`);
  }
  const sourceStat = fs.lstatSync(source);
  if (!sourceStat.isDirectory() || sourceStat.isSymbolicLink()) {
    throw new Error(`trace source must be a non-symlink directory: ${source}`);
  }
  fs.mkdirSync(path.dirname(target), { recursive: true });
  if (fs.existsSync(target)) fs.unlinkSync(target);
  fs.mkdirSync(qaWorkRoot, { recursive: true });
  const staging = fs.mkdtempSync(path.join(qaWorkRoot, "trace-sanitized-"));
  try {
    copySanitizedTraceTree(source, staging);
    const result = spawnSync("zip", ["-q", "-r", target, "."], {
      cwd: staging,
      env: process.env,
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
    });
    if (result.status !== 0) {
      throw new Error(`failed to package trace: ${result.stderr || result.stdout || result.error?.message || "zip failed"}`);
    }
    if (!fs.existsSync(target) || fs.statSync(target).size === 0) {
      throw new Error(`trace archive was not created: ${target}`);
    }
  } finally {
    fs.rmSync(staging, { recursive: true, force: true });
  }
}

function namedOptions(rawArgs) {
  const options = {};
  for (let index = 0; index < rawArgs.length; index += 2) {
    const key = rawArgs[index];
    const value = rawArgs[index + 1] || "";
    if (key === "--source") options.source = value;
    if (key === "--output") options.output = value;
  }
  return options;
}

function copySanitizedTraceTree(source, target) {
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const sourcePath = path.join(source, entry.name);
    const targetPath = path.join(target, entry.name);
    if (entry.isSymbolicLink()) {
      throw new Error(`trace source contains a symlink: ${sourcePath}`);
    }
    if (entry.isDirectory()) {
      fs.mkdirSync(targetPath, { recursive: true });
      copySanitizedTraceTree(sourcePath, targetPath);
      continue;
    }
    if (!entry.isFile()) {
      throw new Error(`trace source contains an unsupported entry: ${sourcePath}`);
    }
    const content = fs.readFileSync(sourcePath);
    if (isProbablyText(content)) {
      fs.writeFileSync(targetPath, sanitizeEvidenceText(content.toString("utf8")), "utf8");
    } else {
      fs.copyFileSync(sourcePath, targetPath);
    }
  }
}

function isProbablyText(content) {
  if (content.length === 0) return true;
  const sample = content.subarray(0, Math.min(content.length, 8192));
  if (sample.includes(0)) return false;
  let controls = 0;
  for (const byte of sample) {
    if (byte < 9 || (byte > 13 && byte < 32)) controls += 1;
  }
  return controls / sample.length < 0.02;
}

function sanitizeEvidenceText(value) {
  try {
    const parsed = JSON.parse(value);
    return `${JSON.stringify(redactJson(parsed), null, 2)}\n`;
  } catch {
    return redactSensitiveInputValues(redact(value))
      .replace(/("name"\s*:\s*"(?:Authorization|Cookie|Set-Cookie|token|api[_-]?key|access[_-]?key|secret|password)"\s*,\s*"value"\s*:\s*)"[^"]*"/gi,
        "$1\"[REDACTED]\"")
      .replace(/^(Authorization|Cookie|Set-Cookie)\s*:\s*.*$/gim, "$1: [REDACTED]")
      .replace(/([?&](?:token|api[_-]?key|access[_-]?key|secret|password)=)[^&\s]+/gi, "$1[REDACTED]")
      .replace(/("(?:password|secret|token|apiKey|accessKey|authorization|cookie)"\s*:\s*)"[^"]*"/gi,
        "$1\"[REDACTED]\"");
  }
}

function redactSensitiveInputValues(value) {
  return String(value).replace(/<input\b[^>]*>/gi, (input) => {
    if (!/\btype\s*=\s*["']password["']/i.test(input)
        && !/\bautocomplete\s*=\s*["'][^"']*password[^"']*["']/i.test(input)) {
      return input;
    }
    return input.replace(/\bvalue\s*=\s*(["'])[^"']*\1/gi, "value=\"[REDACTED]\"");
  });
}

function redactJson(value) {
  if (Array.isArray(value)) return value.map(redactJson);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, entry]) => [
      key,
      /(password|secret|token|api[_-]?key|access[_-]?key|authorization|cookie)/i.test(key)
        ? "[REDACTED]"
        : redactJson(entry),
    ]));
  }
  return typeof value === "string" ? redact(value) : value;
}

function walk(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) return [];
    if (entry.isDirectory()) return walk(target);
    return entry.isFile() ? [target] : [];
  });
}

function redact(value) {
  let redacted = String(value);
  for (const [name, secret] of Object.entries(process.env)) {
    if (/(TOKEN|KEY|SECRET|PASSWORD|CREDENTIAL)/i.test(name) && secret && secret.length >= 4) {
      redacted = redacted.split(secret).join(`[REDACTED:${name}]`);
    }
  }
  return redacted
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [REDACTED]")
    .replace(/\b(?:sk|ghp|github_pat)_[A-Za-z0-9_-]{8,}\b/g, "[REDACTED]");
}
