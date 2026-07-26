import { createHash } from "node:crypto";
import { lstat, readFile, readdir } from "node:fs/promises";
import { basename, relative, resolve, sep } from "node:path";

import {
  DefaultResourceLoader,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";

export const RESOURCE_MANIFEST_PROTOCOL = "rd-agent-resource-manifest/v1";
export const VERIFIED_STATUS = "VERIFIED";
export const DEFAULT_EXTENSION_ROOT = "/work/input/extensions";

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
 * Build a Pi loader with project resources disabled by default. Explicit
 * verified extension paths remain available through additionalExtensionPaths;
 * project .pi settings, skills, prompts, themes, and extensions are not.
 */
export function createApprovedResourceLoader({
  cwd = "/work/repo",
  agentDir = "/work/pi-agent",
  verifiedManifest,
  settingsManager,
  extensionFactories = [],
} = {}) {
  if (!verifiedManifest || !Array.isArray(verifiedManifest.extensionPaths)) {
    throw new Error("verified resource manifest is required");
  }
  const projectRoot = resolve(cwd);
  const manager = settingsManager ?? SettingsManager.inMemory(
    {
      compaction: { enabled: false },
      retry: { enabled: true, maxRetries: 2 },
      enableAnalytics: false,
      packages: [],
      extensions: [],
      skills: [],
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
    extensionFactories: [...extensionFactories],
    agentsFilesOverride: ({ agentsFiles }) => ({
      agentsFiles: agentsFiles.filter((file) => isAllowedContextFile(file.path, projectRoot)),
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

function isAllowedContextFile(path, projectRoot) {
  const absolutePath = resolve(path);
  if (!isWithin(absolutePath, projectRoot)) return false;
  return new Set(["AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD"]).has(basename(absolutePath));
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
