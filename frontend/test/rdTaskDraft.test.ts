import assert from "node:assert/strict";
import test from "node:test";

import {
  clearTaskCreateDraft,
  formatDraftTime,
  isTaskDraftDirty,
  loadTaskCreateDraft,
  saveTaskCreateDraft,
  RD_TASK_CREATE_DRAFT_STORAGE_KEY,
  type TaskCreateDraft
} from "../src/pages/admin/rdtask/rdTaskDraft.ts";

function createMockStorage(): Storage {
  const store = new Map<string, string>();
  return {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key: string) {
      return store.has(key) ? store.get(key)! : null;
    },
    key(index: number) {
      return Array.from(store.keys())[index] || null;
    },
    removeItem(key: string) {
      store.delete(key);
    },
    setItem(key: string, value: string) {
      store.set(key, String(value));
    }
  };
}

test("detects dirty task draft properly", () => {
  assert.equal(isTaskDraftDirty({}), false);
  assert.equal(isTaskDraftDirty({ title: "   " }), false);
  assert.equal(isTaskDraftDirty({ title: "修复下单 500" }), true);
  assert.equal(isTaskDraftDirty({ bugActualBehavior: "报错卡住" }), true);
  assert.equal(isTaskDraftDirty({ manualRequirementText: "需求文档说明" }), true);
  assert.equal(isTaskDraftDirty({ acceptanceCriteriaText: "测试通过" }), true);
});

test("saves and loads draft from storage", () => {
  const storage = createMockStorage();

  const draft: Partial<TaskCreateDraft> = {
    taskKind: "BUG_FIX",
    title: "订单支付超时处理",
    ticketTitle: "支付接口超时",
    selectedProjectId: "proj-123",
    priority: "P1",
    baseBranch: "release/v2",
    bugActualBehavior: "支付回调未响应",
    bugExpectedBehavior: "支付成功并更新状态",
    bugReproductionSteps: "1. 下单 2. 支付",
    bugErrorLog: "TimeoutException",
    bugAffectedScope: "支付模块",
    promptSnapshot: "配置 rd-bot:tickets",
    autoExecute: false
  };

  saveTaskCreateDraft(draft, storage);

  const loaded = loadTaskCreateDraft(storage);
  assert.ok(loaded !== null);
  assert.equal(loaded?.title, "订单支付超时处理");
  assert.equal(loaded?.ticketTitle, "支付接口超时");
  assert.equal(loaded?.selectedProjectId, "proj-123");
  assert.equal(loaded?.priority, "P1");
  assert.equal(loaded?.baseBranch, "release/v2");
  assert.equal(loaded?.bugActualBehavior, "支付回调未响应");
  assert.equal(loaded?.bugExpectedBehavior, "支付成功并更新状态");
  assert.equal(loaded?.bugReproductionSteps, "1. 下单 2. 支付");
  assert.equal(loaded?.bugErrorLog, "TimeoutException");
  assert.equal(loaded?.bugAffectedScope, "支付模块");
  assert.equal(loaded?.promptSnapshot, "配置 rd-bot:tickets");
  assert.equal(loaded?.autoExecute, false);
  assert.ok(typeof loaded?.savedAt === "number");
});

test("clears draft from storage on demand or when empty", () => {
  const storage = createMockStorage();

  saveTaskCreateDraft({ title: "临时草稿" }, storage);
  assert.ok(storage.getItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY) !== null);

  clearTaskCreateDraft(storage);
  assert.equal(storage.getItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY), null);
  assert.equal(loadTaskCreateDraft(storage), null);

  // Saving empty draft should clean storage
  saveTaskCreateDraft({ title: "临时草稿2" }, storage);
  assert.ok(storage.getItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY) !== null);

  saveTaskCreateDraft({ title: "  " }, storage);
  assert.equal(storage.getItem(RD_TASK_CREATE_DRAFT_STORAGE_KEY), null);
});

test("formats draft time into HH:MM:SS", () => {
  const date = new Date(2026, 7, 17, 14, 30, 45);
  const timeStr = formatDraftTime(date.getTime());
  assert.equal(timeStr, "14:30:45");
});
