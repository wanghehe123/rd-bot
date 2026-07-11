import assert from "node:assert/strict";
import test from "node:test";

import { normalizeAlertRecipients } from "../src/pages/admin/project/alertRecipients.ts";

test("normalizes separate group and user recipient lists without empty rows", () => {
  assert.deepEqual(
    normalizeAlertRecipients([" oc_group ", "", "oc_group"], ["ou_user", " ou_user "]),
    [
      { type: "CHAT_ID", value: "oc_group" },
      { type: "OPEN_ID", value: "ou_user" }
    ]
  );
});

test("allows both recipient lists to be empty", () => {
  assert.deepEqual(normalizeAlertRecipients([], ["", "  "]), []);
});

test("rejects an ID entered in the wrong recipient list", () => {
  assert.throws(() => normalizeAlertRecipients(["ou_user"], []), /oc_/);
  assert.throws(() => normalizeAlertRecipients([], ["oc_group"]), /ou_/);
});
