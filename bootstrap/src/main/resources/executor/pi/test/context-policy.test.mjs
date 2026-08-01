import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  CONTEXT_POLICY_MODES,
  discoverContextFiles,
} from "../src/resource-loader.mjs";

const testDir = dirname(fileURLToPath(import.meta.url));
const fixtureRepo = join(testDir, "fixtures", "context-policy-repo");

function sha256Text(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function rejectedPaths(result) {
  return result.rejected.map((entry) => entry.path);
}

function rejectedReasons(result, path) {
  return result.rejected.filter((entry) => entry.path === path).map((entry) => entry.reason);
}

test("ROOT_ONLY loads only root AGENTS.md and CLAUDE.md from fixture repo", async () => {
  const result = await discoverContextFiles(fixtureRepo, {
    mode: CONTEXT_POLICY_MODES.ROOT_ONLY,
  });

  assert.equal(result.mode, CONTEXT_POLICY_MODES.ROOT_ONLY);
  assert.deepEqual(result.loaded.map((entry) => entry.path), ["AGENTS.md", "CLAUDE.md"]);
  assert.ok(result.loaded.every((entry) => entry.sha256 && entry.bytes > 0));
  assert.match(result.loaded[0].content, /SENTINEL_ROOT_AGENTS/);
  assert.match(result.loaded[1].content, /SENTINEL_ROOT_CLAUDE/);

  assert.ok(rejectedPaths(result).includes("src/AGENTS.md"));
  assert.ok(rejectedPaths(result).includes("src/feature/CLAUDE.md"));
  assert.deepEqual(rejectedReasons(result, "src/AGENTS.md"), ["NOT_ROOT_CONTEXT_FILE"]);
  assert.deepEqual(rejectedReasons(result, "node_modules/pkg/AGENTS.md"), ["EXCLUDED_DIRECTORY"]);
  assert.deepEqual(rejectedReasons(result, "build/CLAUDE.md"), ["EXCLUDED_DIRECTORY"]);
  assert.deepEqual(rejectedReasons(result, ".pi/extensions/AGENTS.md"), ["EXCLUDED_DIRECTORY"]);
  assert.deepEqual(rejectedReasons(result, "linked/AGENTS.md"), ["SYMLINK"]);
});

test("ROOT_AND_ALLOWLISTED_NESTED loads one allowlisted nested path", async () => {
  const nestedPath = "src/feature/CLAUDE.md";
  const nestedContent = await readFile(join(fixtureRepo, nestedPath), "utf8");
  const result = await discoverContextFiles(fixtureRepo, {
    mode: CONTEXT_POLICY_MODES.ROOT_AND_ALLOWLISTED_NESTED,
    expectedFiles: [{ path: nestedPath, contentHash: `sha256:${sha256Text(nestedContent)}` }],
  });

  assert.deepEqual(result.loaded.map((entry) => entry.path), ["AGENTS.md", "CLAUDE.md", nestedPath]);
  assert.match(result.loaded[2].content, /SENTINEL_SRC_FEATURE_CLAUDE/);
  assert.deepEqual(rejectedReasons(result, "src/AGENTS.md"), ["NOT_ALLOWLISTED"]);
});

test("ROOT_AND_ALLOWLISTED_NESTED rejects hash mismatch for expected nested file", async () => {
  const result = await discoverContextFiles(fixtureRepo, {
    mode: CONTEXT_POLICY_MODES.ROOT_AND_ALLOWLISTED_NESTED,
    expectedFiles: [{
      path: "src/feature/CLAUDE.md",
      contentHash: "sha256:0000000000000000000000000000000000000000000000000000000000000000",
    }],
  });

  assert.deepEqual(result.loaded.map((entry) => entry.path), ["AGENTS.md", "CLAUDE.md"]);
  assert.deepEqual(rejectedReasons(result, "src/feature/CLAUDE.md"), ["HASH_MISMATCH"]);
  const rejected = result.rejected.find((entry) => entry.path === "src/feature/CLAUDE.md");
  assert.ok(rejected.sha256);
  assert.ok(rejected.bytes > 0);
});

test("excluded directories are rejected even when allowlisted", async () => {
  const result = await discoverContextFiles(fixtureRepo, {
    mode: CONTEXT_POLICY_MODES.ROOT_AND_ALLOWLISTED_NESTED,
    expectedFiles: [{ path: "build/CLAUDE.md" }],
  });

  assert.deepEqual(rejectedReasons(result, "build/CLAUDE.md"), ["EXCLUDED_DIRECTORY"]);
  assert.equal(result.loaded.some((entry) => entry.path === "build/CLAUDE.md"), false);
});

test("load order is stable: root agents, root claude, nested by depth then path", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-context-order-"));
  await writeFile(join(root, "AGENTS.md"), "root-agents\n", "utf8");
  await writeFile(join(root, "CLAUDE.md"), "root-claude\n", "utf8");
  await mkdir(join(root, "a", "b"), { recursive: true });
  await mkdir(join(root, "z"), { recursive: true });
  await writeFile(join(root, "a", "AGENTS.md"), "a-agents\n", "utf8");
  await writeFile(join(root, "a", "b", "CLAUDE.md"), "ab-claude\n", "utf8");
  await writeFile(join(root, "z", "AGENTS.md"), "z-agents\n", "utf8");

  const result = await discoverContextFiles(root, {
    mode: CONTEXT_POLICY_MODES.ROOT_AND_ALLOWLISTED_NESTED,
    expectedFiles: [
      { path: "a/AGENTS.md" },
      { path: "a/b/CLAUDE.md" },
      { path: "z/AGENTS.md" },
    ],
  });

  assert.deepEqual(
    result.loaded.map((entry) => entry.path),
    ["AGENTS.md", "CLAUDE.md", "a/AGENTS.md", "z/AGENTS.md", "a/b/CLAUDE.md"],
  );
});

test("LEGACY_OBSERVE_ONLY keeps nested observe behavior including excluded dirs", async () => {
  const result = await discoverContextFiles(fixtureRepo, {
    mode: CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY,
  });

  assert.ok(result.loaded.some((entry) => entry.path === "src/AGENTS.md"));
  assert.ok(result.loaded.some((entry) => entry.path === "node_modules/pkg/AGENTS.md"));
  assert.ok(result.loaded.some((entry) => entry.path === "linked/AGENTS.md"));
  assert.equal(result.rejected.length, 0);
});

test("symlink context file fails closed under ROOT_ONLY", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-context-symlink-"));
  const outside = join(dirname(root), `rd-context-outside-${Date.now()}.md`);
  await writeFile(outside, "outside\n", "utf8");
  await mkdir(join(root, "linked"), { recursive: true });
  const { symlink } = await import("node:fs/promises");
  await symlink(outside, join(root, "linked", "AGENTS.md"));

  const result = await discoverContextFiles(root, {
    mode: CONTEXT_POLICY_MODES.ROOT_ONLY,
  });
  assert.deepEqual(rejectedReasons(result, "linked/AGENTS.md"), ["SYMLINK"]);
});
