import { createHash } from "node:crypto";
import { lstat, readFile, readdir } from "node:fs/promises";
import { basename, dirname, relative, resolve, sep } from "node:path";

import {
  DefaultResourceLoader,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";

export const RESOURCE_MANIFEST_PROTOCOL = "rd-agent-resource-manifest/v1";
export const SKILL_MANIFEST_PROTOCOL = "rd-skill-manifest/v1";
export const VERIFIED_STATUS = "VERIFIED";
export const DEFAULT_EXTENSION_ROOT = "/work/input/extensions";
export const DEFAULT_SKILL_ROOT = "/work/input/skills";
export const DEFAULT_SKILL_MANIFEST_PATH = "/work/input/skill-manifest.json";

export const CONTEXT_POLICY_MODES = Object.freeze({
  LEGACY_OBSERVE_ONLY: "LEGACY_OBSERVE_ONLY",
  ROOT_ONLY: "ROOT_ONLY",
  ROOT_AND_ALLOWLISTED_NESTED: "ROOT_AND_ALLOWLISTED_NESTED",
});

export const DEFAULT_CONTEXT_POLICY_LIMITS = Object.freeze({
  maxRootFiles: 2,
  maxNestedFiles: 8,
  maxFileBytes: 32 * 1024,
  maxTotalBytes: 64 * 1024,
  maxDepth: 8,
});

export const EXCLUDED_CONTEXT_DIRS = Object.freeze([
  ".git",
  ".pi",
  ".agents",
  "node_modules",
  ".next",
  "dist",
  "build",
  "target",
  "coverage",
  "vendor",
]);

const CONTEXT_FILE_NAMES = new Set(["AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD"]);

const REJECT_REASON = Object.freeze({
  PATH_ESCAPE: "PATH_ESCAPE",
  SYMLINK: "SYMLINK",
  EXCLUDED_DIRECTORY: "EXCLUDED_DIRECTORY",
  NOT_ROOT_CONTEXT_FILE: "NOT_ROOT_CONTEXT_FILE",
  NOT_ALLOWLISTED: "NOT_ALLOWLISTED",
  DEPTH_EXCEEDED: "DEPTH_EXCEEDED",
  ROOT_FILE_LIMIT: "ROOT_FILE_LIMIT",
  NESTED_FILE_LIMIT: "NESTED_FILE_LIMIT",
  FILE_SIZE_EXCEEDED: "FILE_SIZE_EXCEEDED",
  TOTAL_SIZE_EXCEEDED: "TOTAL_SIZE_EXCEEDED",
  HASH_MISMATCH: "HASH_MISMATCH",
  READ_FAILED: "READ_FAILED",
});

/**
 * Validate the Java-produced resource manifest and re-hash every selected
 * path. The container treats the manifest as an allowlist, not as a path
 * hint: a resource outside the mounted root, a symlink, or a changed digest is
 * rejected before the Pi session is created.
 */
export async function validateResourceManifest(manifest, options = {}) {
  const root = resolve(options.root ?? DEFAULT_EXTENSION_ROOT);
  if (!isObject(manifest)) throw new Error("resource manifest must be an object");
  if (manifest.protocol !== RESOURCE_MANIFEST_PROTOCOL) {
    throw new Error(`unsupported resource manifest protocol: ${manifest.protocol}`);
  }
  requireText(manifest.extensionSetId, "extensionSetId");
  if (!Number.isInteger(manifest.extensionSetVersion) || manifest.extensionSetVersion <= 0) {
    throw new Error("extensionSetVersion must be a positive integer");
  }
  if (manifest.verificationStatus !== VERIFIED_STATUS) {
    throw new Error("resource manifest is not verified");
  }
  if (!Array.isArray(manifest.resources)) {
    throw new Error("resource manifest resources must be an array");
  }

  const seen = new Set();
  const extensionPaths = [];
  for (const resource of manifest.resources) {
    if (!isObject(resource) || resource.kind !== "extension") {
      throw new Error("resource manifest contains an unsupported resource");
    }
    if (resource.status !== VERIFIED_STATUS) {
      throw new Error("resource is not verified");
    }
    const resourcePath = requireText(resource.path, "resource.path");
    const absolutePath = resolve(resourcePath);
    if (!isWithin(absolutePath, root) || absolutePath === root) {
      throw new Error(`resource path escapes the extension root: ${resourcePath}`);
    }
    if (seen.has(absolutePath)) {
      throw new Error(`resource path is duplicated: ${absolutePath}`);
    }
    seen.add(absolutePath);
    if (!/^[0-9a-fA-F]{64}$/.test(String(resource.sha256 ?? ""))) {
      throw new Error(`resource sha256 is invalid: ${absolutePath}`);
    }
    const digest = await hashResourcePath(absolutePath);
    if (digest !== String(resource.sha256).toLowerCase()) {
      throw new Error(`resource digest mismatch: ${absolutePath}`);
    }
    extensionPaths.push(absolutePath);
  }

  return Object.freeze({
    manifest,
    extensionPaths: Object.freeze(extensionPaths.sort()),
  });
}

export async function loadResourceManifest(path, options = {}) {
  const content = await readFile(path, "utf8");
  let manifest;
  try {
    manifest = JSON.parse(content);
  } catch (error) {
    throw new Error(`invalid resource manifest JSON: ${error.message}`);
  }
  return validateResourceManifest(manifest, options);
}

/**
 * Validate the Java-produced skill manifest. Skills are loaded separately from
 * the extension resource manifest; only skillPaths under the skill root are
 * accepted.
 */
export function validateSkillManifest(manifest, options = {}) {
  const root = resolve(options.root ?? DEFAULT_SKILL_ROOT);
  if (!isObject(manifest)) throw new Error("skill manifest must be an object");
  if (manifest.protocol !== SKILL_MANIFEST_PROTOCOL) {
    throw new Error(`unsupported skill manifest protocol: ${manifest.protocol}`);
  }
  if (!Array.isArray(manifest.skillPaths)) {
    throw new Error("skill manifest skillPaths must be an array");
  }
  if (!Array.isArray(manifest.skills)) {
    throw new Error("skill manifest skills must be an array");
  }

  const skillPaths = [];
  for (const skillPath of manifest.skillPaths) {
    requireText(skillPath, "skillPaths[]");
    const absolutePath = resolve(skillPath);
    if (!isWithin(absolutePath, root)) {
      throw new Error(`skill path escapes the skill root: ${skillPath}`);
    }
    skillPaths.push(absolutePath);
  }

  const skills = [];
  for (const [index, skill] of manifest.skills.entries()) {
    if (!isObject(skill)) {
      throw new Error(`skill manifest skills[${index}] must be an object`);
    }
    const skillId = requireText(skill.skillId, `skills[${index}].skillId`);
    const forceGuide = skill.forceGuide === true;
    const guidePrompt = typeof skill.guidePrompt === "string" ? skill.guidePrompt : "";
    skills.push(Object.freeze({
      skillId,
      version: typeof skill.version === "string" ? skill.version : "",
      skillPath: typeof skill.skillPath === "string" ? skill.skillPath : "",
      forceGuide,
      guidePrompt,
      description: typeof skill.description === "string" ? skill.description : "",
    }));
  }

  return Object.freeze({
    skillPaths: Object.freeze(skillPaths),
    skills: Object.freeze(skills),
  });
}

export async function loadSkillManifest(path, options = {}) {
  const content = await readFile(path, "utf8");
  let manifest;
  try {
    manifest = JSON.parse(content);
  } catch (error) {
    throw new Error(`invalid skill manifest JSON: ${error.message}`);
  }
  return validateSkillManifest(manifest, options);
}

/**
 * Discover repo context files under a frozen runtime context policy. Returns
 * deterministic load order and explicit reject decisions for audit and bridge
 * preflight (P3-V2).
 */
export async function discoverContextFiles(repoRoot, policy = {}) {
  const normalizedPolicy = normalizeContextPolicy(policy);
  const root = resolve(repoRoot);
  const limits = normalizedPolicy.limits;
  const candidates = await collectContextCandidates(root);
  const allowlistedPaths = buildAllowlistSet(normalizedPolicy.expectedFiles);
  const expectedHashes = buildExpectedHashMap(normalizedPolicy.expectedFiles);

  const eligible = [];
  const rejected = [];

  for (const candidate of candidates) {
    const decision = await evaluateContextCandidate(candidate, root, normalizedPolicy.mode, {
      limits,
      allowlistedPaths,
      expectedHashes,
    });
    if (decision.loaded) {
      eligible.push(decision.entry);
    } else {
      rejected.push(decision.entry);
    }
  }

  const sorted = sortContextLoadOrder(eligible, root);

  if (normalizedPolicy.mode === CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY) {
    const loaded = sorted.map((entry) => ({
      path: entry.relativePath,
      sha256: entry.sha256,
      bytes: entry.bytes,
      content: entry.content,
    }));
    return Object.freeze({
      mode: normalizedPolicy.mode,
      loaded: Object.freeze(loaded),
      rejected: Object.freeze(rejected),
    });
  }

  const loaded = [];
  let rootCount = 0;
  let nestedCount = 0;
  let totalBytes = 0;

  for (const entry of sorted) {
    const atRoot = isRootContextPath(root, entry.absolutePath);
    if (atRoot) {
      if (rootCount >= limits.maxRootFiles) {
        rejected.push(rejectEntry(entry.relativePath, entry.sha256, entry.bytes, REJECT_REASON.ROOT_FILE_LIMIT));
        continue;
      }
    } else if (nestedCount >= limits.maxNestedFiles) {
      rejected.push(rejectEntry(entry.relativePath, entry.sha256, entry.bytes, REJECT_REASON.NESTED_FILE_LIMIT));
      continue;
    }
    if (entry.bytes > limits.maxFileBytes) {
      rejected.push(rejectEntry(entry.relativePath, entry.sha256, entry.bytes, REJECT_REASON.FILE_SIZE_EXCEEDED));
      continue;
    }
    if (totalBytes + entry.bytes > limits.maxTotalBytes) {
      rejected.push(rejectEntry(entry.relativePath, entry.sha256, entry.bytes, REJECT_REASON.TOTAL_SIZE_EXCEEDED));
      continue;
    }
    if (atRoot) {
      rootCount += 1;
    } else {
      nestedCount += 1;
    }
    totalBytes += entry.bytes;
    loaded.push({
      path: entry.relativePath,
      sha256: entry.sha256,
      bytes: entry.bytes,
      content: entry.content,
    });
  }

  return Object.freeze({
    mode: normalizedPolicy.mode,
    loaded: Object.freeze(loaded),
    rejected: Object.freeze(rejected),
  });
}

/**
 * Build a Pi loader with project resources disabled by default. Explicit
 * verified extension paths remain available through additionalExtensionPaths;
 * explicit skillPaths from the skill manifest use additionalSkillPaths while
 * noSkills stays true (defaults and project .agents/skills stay off).
 */
export function createApprovedResourceLoader({
  cwd = "/work/repo",
  agentDir = "/work/pi-agent",
  verifiedManifest,
  settingsManager,
  extensionFactories = [],
  additionalSkillPaths = [],
  contextPolicy,
  contextDiscovery,
} = {}) {
  if (!verifiedManifest || !Array.isArray(verifiedManifest.extensionPaths)) {
    throw new Error("verified resource manifest is required");
  }
  const skillPaths = Array.isArray(additionalSkillPaths)
    ? [...additionalSkillPaths]
    : [];
  const projectRoot = resolve(cwd);
  const manager = settingsManager ?? SettingsManager.inMemory(
    {
      compaction: { enabled: false },
      retry: { enabled: true, maxRetries: 2 },
      enableAnalytics: false,
      packages: [],
      extensions: [],
      skills: skillPaths.length > 0 ? skillPaths : [],
      prompts: [],
      themes: [],
    },
    { projectTrusted: false },
  );
  return new DefaultResourceLoader({
    cwd: projectRoot,
    agentDir: resolve(agentDir),
    settingsManager: manager,
    noExtensions: true,
    noSkills: true,
    noPromptTemplates: true,
    noThemes: true,
    noContextFiles: false,
    additionalExtensionPaths: [...verifiedManifest.extensionPaths],
    additionalSkillPaths: skillPaths,
    extensionFactories: [...extensionFactories],
    agentsFilesOverride: ({ agentsFiles }) => ({
      agentsFiles: resolveAgentsFilesForPolicy(
        agentsFiles,
        projectRoot,
        contextPolicy,
        contextDiscovery,
      ),
    }),
  });
}

export function contextFileMetadata(agentsFiles) {
  return (agentsFiles ?? []).map((file) => ({
    path: resolve(file.path),
    sha256: sha256Text(file.content ?? ""),
    bytes: Buffer.byteLength(file.content ?? "", "utf8"),
  }));
}

export async function hashResourcePath(path) {
  const absolutePath = resolve(path);
  const info = await lstat(absolutePath);
  if (info.isSymbolicLink()) {
    throw new Error(`resource symlinks are not allowed: ${absolutePath}`);
  }
  if (info.isFile()) {
    return hashFile(absolutePath);
  }
  if (!info.isDirectory()) {
    throw new Error(`resource path must be a regular file or directory: ${absolutePath}`);
  }
  const files = await listRegularFiles(absolutePath);
  const digest = createHash("sha256");
  for (const file of files) {
    const relativePath = relative(absolutePath, file).split(sep).join("/");
    digest.update(relativePath, "utf8");
    digest.update("\0", "utf8");
    digest.update(await readFile(file));
    digest.update("\n", "utf8");
  }
  return digest.digest("hex");
}

async function listRegularFiles(directory) {
  const entries = (await readdir(directory, { withFileTypes: true }))
    .sort((left, right) => left.name.localeCompare(right.name));
  const files = [];
  for (const entry of entries) {
    const path = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) {
      throw new Error(`resource symlinks are not allowed: ${path}`);
    }
    if (entry.isDirectory()) {
      files.push(...await listRegularFiles(path));
    } else if (entry.isFile()) {
      files.push(path);
    } else {
      throw new Error(`resource entry must be a regular file: ${path}`);
    }
  }
  return files;
}

async function hashFile(path) {
  const digest = createHash("sha256");
  digest.update(await readFile(path));
  return digest.digest("hex");
}

function normalizeContextPolicy(policy) {
  const mode = policy?.mode ?? CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY;
  if (!Object.hasOwn(CONTEXT_POLICY_MODES, mode)) {
    throw new Error(`unsupported context policy mode: ${mode}`);
  }
  const limits = { ...DEFAULT_CONTEXT_POLICY_LIMITS, ...(policy?.limits ?? {}) };
  const expectedFiles = Array.isArray(policy?.expectedFiles) ? policy.expectedFiles : [];
  return { mode, limits, expectedFiles };
}

/**
 * Reconcile Pi's per-directory context discovery with the frozen preflight load set.
 * Pi's loadContextFileFromDir returns only the first match (AGENTS.md before CLAUDE.md)
 * per directory; merge in any preflight-accepted files Pi omitted so the session and
 * post-reload manifest reflect the full policy load set.
 */
function resolveAgentsFilesForPolicy(agentsFiles, projectRoot, contextPolicy, contextDiscovery) {
  const mode = contextPolicy?.mode ?? CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY;
  if (mode === CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY || !contextDiscovery) {
    return (agentsFiles ?? []).filter((file) => isAllowedContextFile(file.path, projectRoot));
  }
  const piFilesByPath = new Map();
  for (const file of agentsFiles ?? []) {
    piFilesByPath.set(normalizeRepoRelative(projectRoot, file.path), file);
  }
  const resolved = [];
  for (const entry of contextDiscovery.loaded) {
    const relativePath = normalizeRepoRelative(projectRoot, resolve(projectRoot, entry.path));
    const piFile = piFilesByPath.get(relativePath);
    if (piFile) {
      resolved.push(piFile);
      continue;
    }
    resolved.push({
      path: resolve(projectRoot, entry.path),
      content: entry.content ?? "",
    });
  }
  return resolved;
}

async function collectContextCandidates(repoRoot) {
  const candidates = [];
  await walkContextCandidates(repoRoot, repoRoot, candidates);
  return candidates;
}

async function walkContextCandidates(directory, repoRoot, candidates) {
  const entries = (await readdir(directory, { withFileTypes: true }))
    .sort((left, right) => left.name.localeCompare(right.name));
  for (const entry of entries) {
    const absolutePath = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) {
      if (isContextBasename(entry.name)) {
        candidates.push({ absolutePath, symlink: true });
      }
      continue;
    }
    if (entry.isDirectory()) {
      await walkContextCandidates(absolutePath, repoRoot, candidates);
      continue;
    }
    if (entry.isFile() && isContextBasename(entry.name)) {
      candidates.push({ absolutePath, symlink: false });
    }
  }
}

async function evaluateContextCandidate(candidate, repoRoot, mode, options) {
  const relativePath = normalizeRepoRelative(repoRoot, candidate.absolutePath);
  if (!relativePath || !isWithin(candidate.absolutePath, repoRoot)) {
    return {
      loaded: false,
      entry: rejectEntry(relativePath || basename(candidate.absolutePath), "", 0, REJECT_REASON.PATH_ESCAPE),
    };
  }
  if (candidate.symlink && mode !== CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY) {
    return {
      loaded: false,
      entry: rejectEntry(relativePath, "", 0, REJECT_REASON.SYMLINK),
    };
  }
  try {
    const info = await lstat(candidate.absolutePath);
    if (info.isSymbolicLink() && mode !== CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY) {
      return {
        loaded: false,
        entry: rejectEntry(relativePath, "", 0, REJECT_REASON.SYMLINK),
      };
    }
  } catch (error) {
    return {
      loaded: false,
      entry: rejectEntry(relativePath, "", 0, REJECT_REASON.READ_FAILED),
    };
  }
  if (mode !== CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY) {
    if (hasExcludedAncestor(relativePath)) {
      return {
        loaded: false,
        entry: rejectEntry(relativePath, "", 0, REJECT_REASON.EXCLUDED_DIRECTORY),
      };
    }
    const depth = parentDepth(relativePath);
    if (depth > options.limits.maxDepth) {
      return {
        loaded: false,
        entry: rejectEntry(relativePath, "", 0, REJECT_REASON.DEPTH_EXCEEDED),
      };
    }
  }

  const atRoot = isRootContextPath(repoRoot, candidate.absolutePath);
  if (mode === CONTEXT_POLICY_MODES.ROOT_ONLY && !atRoot) {
    return {
      loaded: false,
      entry: rejectEntry(relativePath, "", 0, REJECT_REASON.NOT_ROOT_CONTEXT_FILE),
    };
  }
  if (mode === CONTEXT_POLICY_MODES.ROOT_AND_ALLOWLISTED_NESTED) {
    if (!atRoot && !options.allowlistedPaths.has(relativePath)) {
      return {
        loaded: false,
        entry: rejectEntry(relativePath, "", 0, REJECT_REASON.NOT_ALLOWLISTED),
      };
    }
  }

  let content;
  try {
    content = await readFile(candidate.absolutePath, "utf8");
  } catch (error) {
    return {
      loaded: false,
      entry: rejectEntry(relativePath, "", 0, REJECT_REASON.READ_FAILED),
    };
  }
  const bytes = Buffer.byteLength(content, "utf8");
  const sha256 = sha256Text(content);
  const expectedHash = options.expectedHashes.get(relativePath);
  if (expectedHash && normalizeSha256(expectedHash) !== sha256) {
    return {
      loaded: false,
      entry: rejectEntry(relativePath, sha256, bytes, REJECT_REASON.HASH_MISMATCH),
    };
  }

  return {
    loaded: true,
    entry: {
      absolutePath: candidate.absolutePath,
      relativePath,
      sha256,
      bytes,
      content,
      atRoot,
    },
  };
}

function sortContextLoadOrder(entries, repoRoot) {
  return [...entries].sort((left, right) => {
    const leftRoot = isRootContextPath(repoRoot, left.absolutePath);
    const rightRoot = isRootContextPath(repoRoot, right.absolutePath);
    if (leftRoot !== rightRoot) {
      return leftRoot ? -1 : 1;
    }
    if (leftRoot && rightRoot) {
      const leftAgents = isAgentsBasename(left.relativePath);
      const rightAgents = isAgentsBasename(right.relativePath);
      if (leftAgents !== rightAgents) {
        return leftAgents ? -1 : 1;
      }
    }
    const leftDepth = parentDepth(left.relativePath);
    const rightDepth = parentDepth(right.relativePath);
    if (leftDepth !== rightDepth) {
      return leftDepth - rightDepth;
    }
    return left.relativePath.localeCompare(right.relativePath);
  });
}

function buildAllowlistSet(expectedFiles) {
  const allowlisted = new Set();
  for (const entry of expectedFiles) {
    const path = normalizeRepoRelative("", entry?.path ?? "");
    if (path) {
      allowlisted.add(path);
    }
  }
  return allowlisted;
}

function buildExpectedHashMap(expectedFiles) {
  const hashes = new Map();
  for (const entry of expectedFiles) {
    const path = normalizeRepoRelative("", entry?.path ?? "");
    const hash = entry?.contentHash ?? entry?.sha256 ?? "";
    if (path && hash) {
      hashes.set(path, hash);
    }
  }
  return hashes;
}

function rejectEntry(path, sha256, bytes, reason) {
  const entry = { path, reason };
  if (sha256) entry.sha256 = sha256;
  if (bytes > 0) entry.bytes = bytes;
  return entry;
}

function isAllowedContextFile(path, projectRoot) {
  const absolutePath = resolve(path);
  if (!isWithin(absolutePath, projectRoot)) return false;
  return isContextBasename(basename(absolutePath));
}

function isContextBasename(name) {
  return CONTEXT_FILE_NAMES.has(name);
}

function isAgentsBasename(relativePath) {
  const name = basename(relativePath);
  return name === "AGENTS.md" || name === "AGENTS.MD";
}

function isRootContextPath(repoRoot, absolutePath) {
  return resolve(dirname(absolutePath)) === resolve(repoRoot);
}

function normalizeRepoRelative(repoRoot, absolutePath) {
  if (!absolutePath) return "";
  const root = repoRoot ? resolve(repoRoot) : "";
  const normalized = root
    ? relative(root, resolve(absolutePath))
    : String(absolutePath).replace(/\\/g, "/");
  return normalized.split(sep).join("/").replace(/^\/+/, "");
}

function parentDepth(relativePath) {
  const parent = dirname(relativePath);
  if (parent === "." || parent === "") return 0;
  return parent.split("/").filter(Boolean).length;
}

function hasExcludedAncestor(relativePath) {
  const segments = relativePath.split("/");
  if (segments.length <= 1) return false;
  return segments.slice(0, -1).some((segment) => EXCLUDED_CONTEXT_DIRS.includes(segment));
}

function normalizeSha256(value) {
  const normalized = String(value).trim().toLowerCase();
  return normalized.startsWith("sha256:") ? normalized.slice(7) : normalized;
}

function isWithin(path, root) {
  const relativePath = relative(root, path);
  return relativePath === "" || (!relativePath.startsWith(`..${sep}`) && relativePath !== ".." && !relativePath.includes(`..${sep}`));
}

function sha256Text(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function requireText(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} must be a non-empty string`);
  }
  return value;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
