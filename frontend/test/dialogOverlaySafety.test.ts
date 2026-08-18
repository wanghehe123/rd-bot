import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const dialogSource = readFileSync(
  new URL("../src/components/ui/dialog.tsx", import.meta.url),
  "utf8"
);

const rdTaskSource = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskListPage.tsx", import.meta.url),
  "utf8"
);

test("DialogContent prevents backdrop outside pointer down by default to protect forms", () => {
  assert.match(dialogSource, /preventBackdropClose = true/);
  assert.match(dialogSource, /onPointerDownOutside=\{/);
  assert.match(dialogSource, /if \(preventBackdropClose\) \{\s*event\.preventDefault\(\);/);
  assert.match(dialogSource, /onInteractOutside=\{/);
});

test("RdTaskListPage integrates task draft persistence and recovery banner", () => {
  assert.match(rdTaskSource, /loadTaskCreateDraft/);
  assert.match(rdTaskSource, /saveTaskCreateDraft/);
  assert.match(rdTaskSource, /clearTaskCreateDraft/);
  assert.match(rdTaskSource, /已自动恢复上次未提交的草稿/);
  assert.match(rdTaskSource, /清空草稿/);
  assert.match(rdTaskSource, /草稿已自动保存，再次打开即可继续编辑/);
});
