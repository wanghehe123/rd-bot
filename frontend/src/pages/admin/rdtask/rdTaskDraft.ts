export interface TaskCreateDraft {
  savedAt: number;
  taskKind: "BUG_FIX" | "REQUIREMENT";
  title: string;
  ticketTitle: string;
  selectedProjectId: string;
  priority: string;
  baseBranch: string;
  bugActualBehavior: string;
  bugExpectedBehavior: string;
  bugReproductionSteps: string;
  bugErrorLog: string;
  bugAffectedScope: string;
  promptSnapshot: string;
  materialSourceType: "MANUAL_TEXT" | "FEISHU_DOC" | "LOCAL_UPLOAD";
  manualRequirementText: string;
  feishuDocumentUrl: string;
  expectedResult: string;
  acceptanceCriteriaText: string;
  tokenBudgetOverride: string;
  autoExecute: boolean;
}

export const RD_TASK_CREATE_DRAFT_STORAGE_KEY = "rd_task_create_draft_v1";

/**
 * Checks if the draft contains non-empty user input.
 */
export function isTaskDraftDirty(draft: Partial<TaskCreateDraft>): boolean {
  if (!draft) return false;
  return Boolean(
    (draft.title && draft.title.trim()) ||
      (draft.ticketTitle && draft.ticketTitle.trim()) ||
      (draft.bugActualBehavior && draft.bugActualBehavior.trim()) ||
      (draft.bugExpectedBehavior && draft.bugExpectedBehavior.trim()) ||
      (draft.bugReproductionSteps && draft.bugReproductionSteps.trim()) ||
      (draft.bugErrorLog && draft.bugErrorLog.trim()) ||
      (draft.bugAffectedScope && draft.bugAffectedScope.trim()) ||
      (draft.promptSnapshot && draft.promptSnapshot.trim()) ||
      (draft.manualRequirementText && draft.manualRequirementText.trim()) ||
      (draft.feishuDocumentUrl && draft.feishuDocumentUrl.trim()) ||
      (draft.expectedResult && draft.expectedResult.trim()) ||
      (draft.acceptanceCriteriaText && draft.acceptanceCriteriaText.trim())
  );
}

function getStorage(storage?: Storage): Storage | null {
  if (storage) return storage;
  if (typeof window !== "undefined" && window.localStorage) {
    return window.localStorage;
  }
  return null;
}

/**
 * Safely loads the saved task creation draft. Returns null if not found or invalid.
 */
export function loadTaskCreateDraft(storage?: Storage): TaskCreateDraft | null {
  const s = getStorage(storage);
  if (!s) return null;
  try {
    const raw = s.getItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<TaskCreateDraft>;
    if (!parsed || typeof parsed !== "object" || !isTaskDraftDirty(parsed)) {
      return null;
    }
    return {
      savedAt: Number(parsed.savedAt) || Date.now(),
      taskKind: parsed.taskKind === "REQUIREMENT" ? "REQUIREMENT" : "BUG_FIX",
      title: String(parsed.title || ""),
      ticketTitle: String(parsed.ticketTitle || ""),
      selectedProjectId: String(parsed.selectedProjectId || ""),
      priority: String(parsed.priority || "P2"),
      baseBranch: String(parsed.baseBranch || "main"),
      bugActualBehavior: String(parsed.bugActualBehavior || ""),
      bugExpectedBehavior: String(parsed.bugExpectedBehavior || ""),
      bugReproductionSteps: String(parsed.bugReproductionSteps || ""),
      bugErrorLog: String(parsed.bugErrorLog || ""),
      bugAffectedScope: String(parsed.bugAffectedScope || ""),
      promptSnapshot: String(parsed.promptSnapshot || ""),
      materialSourceType:
        parsed.materialSourceType === "FEISHU_DOC" || parsed.materialSourceType === "LOCAL_UPLOAD"
          ? parsed.materialSourceType
          : "MANUAL_TEXT",
      manualRequirementText: String(parsed.manualRequirementText || ""),
      feishuDocumentUrl: String(parsed.feishuDocumentUrl || ""),
      expectedResult: String(parsed.expectedResult || ""),
      acceptanceCriteriaText: String(parsed.acceptanceCriteriaText || ""),
      tokenBudgetOverride: String(parsed.tokenBudgetOverride || "0"),
      autoExecute: parsed.autoExecute ?? true
    };
  } catch {
    return null;
  }
}

/**
 * Saves task draft to storage. If not dirty, clears any existing draft.
 */
export function saveTaskCreateDraft(
  draft: Partial<TaskCreateDraft>,
  storage?: Storage
): void {
  const s = getStorage(storage);
  if (!s) return;
  try {
    if (!isTaskDraftDirty(draft)) {
      s.removeItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY);
      return;
    }
    const fullDraft: TaskCreateDraft = {
      savedAt: Date.now(),
      taskKind: draft.taskKind === "REQUIREMENT" ? "REQUIREMENT" : "BUG_FIX",
      title: draft.title || "",
      ticketTitle: draft.ticketTitle || "",
      selectedProjectId: draft.selectedProjectId || "",
      priority: draft.priority || "P2",
      baseBranch: draft.baseBranch || "main",
      bugActualBehavior: draft.bugActualBehavior || "",
      bugExpectedBehavior: draft.bugExpectedBehavior || "",
      bugReproductionSteps: draft.bugReproductionSteps || "",
      bugErrorLog: draft.bugErrorLog || "",
      bugAffectedScope: draft.bugAffectedScope || "",
      promptSnapshot: draft.promptSnapshot || "",
      materialSourceType: draft.materialSourceType || "MANUAL_TEXT",
      manualRequirementText: draft.manualRequirementText || "",
      feishuDocumentUrl: draft.feishuDocumentUrl || "",
      expectedResult: draft.expectedResult || "",
      acceptanceCriteriaText: draft.acceptanceCriteriaText || "",
      tokenBudgetOverride: draft.tokenBudgetOverride || "0",
      autoExecute: draft.autoExecute ?? true
    };
    s.setItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY, JSON.stringify(fullDraft));
  } catch {
    // Ignore storage quota/security errors
  }
}

/**
 * Clears task create draft from storage.
 */
export function clearTaskCreateDraft(storage?: Storage): void {
  const s = getStorage(storage);
  if (!s) return;
  try {
    s.removeItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY);
  } catch {
    // Ignore
  }
}

/**
 * Formats draft timestamp into human readable text.
 */
export function formatDraftTime(savedAt: number): string {
  if (!savedAt) return "";
  const date = new Date(savedAt);
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  const seconds = String(date.getSeconds()).padStart(2, "0");
  return `${hours}:${minutes}:${seconds}`;
}
