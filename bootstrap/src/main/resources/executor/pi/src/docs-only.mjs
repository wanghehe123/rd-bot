/**
 * Fail-closed docs-only classifier mirrored from
 * exec/.../qa/QaDocsOnlyChangeClassifier.java.
 *
 * Prompt, bridge pre-validation, and host QaEvidenceBundleValidator must use the
 * same allowlist and undeterminable→full-profile semantics.
 */

const DOC_BASENAME_PREFIXES = [
  "readme",
  "changelog",
  "changes",
  "history",
  "authors",
  "contributors",
  "license",
  "licence",
  "notice",
  "copying",
  "code_of_conduct",
  "security",
  "contributing",
];

const DOC_TEXT_EXTENSIONS = [".md", ".mdx", ".markdown", ".txt", ".rst", ".adoc"];
const DOC_ASSET_EXTENSIONS = [".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"];
const RUNTIME_EXTENSIONS = [
  ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".java", ".kt", ".go", ".py", ".rb",
  ".rs", ".c", ".cc", ".cpp", ".h", ".hpp", ".cs", ".php", ".sh", ".bash", ".zsh",
  ".json", ".yml", ".yaml", ".toml", ".xml", ".gradle", ".properties", ".env",
  ".dockerfile",
];

export const DOCS_ONLY_DECISION = Object.freeze({
  DOCS_ONLY: "DOCS_ONLY",
  NOT_DOCS_ONLY: "NOT_DOCS_ONLY",
  UNDETERMINABLE: "UNDETERMINABLE",
});

/**
 * @param {unknown} changedFiles
 * @returns {"DOCS_ONLY"|"NOT_DOCS_ONLY"|"UNDETERMINABLE"}
 */
export function classifyDocsOnlyChange(changedFiles) {
  if (!Array.isArray(changedFiles)) {
    return DOCS_ONLY_DECISION.UNDETERMINABLE;
  }
  const normalized = [];
  for (const raw of changedFiles) {
    const path = normalizePath(raw);
    if (path) normalized.push(path);
  }
  if (normalized.length === 0) {
    return DOCS_ONLY_DECISION.UNDETERMINABLE;
  }
  for (const path of normalized) {
    if (!isDocsOnlyPath(path)) {
      return DOCS_ONLY_DECISION.NOT_DOCS_ONLY;
    }
  }
  return DOCS_ONLY_DECISION.DOCS_ONLY;
}

/**
 * @param {unknown} path
 * @returns {boolean}
 */
export function isDocsOnlyPath(path) {
  const normalized = normalizePath(path);
  if (!normalized || normalized.startsWith("/") || normalized.includes("..") || normalized.includes("//")) {
    return false;
  }
  const lowerPath = normalized.toLowerCase();
  const baseName = lowerPath.includes("/") ? lowerPath.slice(lowerPath.lastIndexOf("/") + 1) : lowerPath;
  if (matchesDocBasename(baseName) && !hasRuntimeExtension(baseName)) {
    return true;
  }
  if (DOC_TEXT_EXTENSIONS.some((extension) => lowerPath.endsWith(extension))) {
    return true;
  }
  if (isUnderDocsDirectory(lowerPath)
      && DOC_ASSET_EXTENSIONS.some((extension) => lowerPath.endsWith(extension))) {
    return true;
  }
  return false;
}

function matchesDocBasename(baseName) {
  return DOC_BASENAME_PREFIXES.some((prefix) => (
    baseName === prefix
      || baseName.startsWith(`${prefix}.`)
      || baseName.startsWith(`${prefix}-`)
      || baseName.startsWith(`${prefix}_`)
  ));
}

function hasRuntimeExtension(baseName) {
  if (RUNTIME_EXTENSIONS.some((extension) => baseName.endsWith(extension))) {
    return true;
  }
  return baseName === "dockerfile" || baseName.startsWith("dockerfile.");
}

function isUnderDocsDirectory(lowerPath) {
  return lowerPath === "docs" || lowerPath.startsWith("docs/") || lowerPath.includes("/docs/");
}

function normalizePath(path) {
  if (typeof path !== "string") {
    return "";
  }
  let normalized = path.trim().replace(/\\/g, "/");
  while (normalized.startsWith("./")) {
    normalized = normalized.slice(2);
  }
  return normalized;
}
