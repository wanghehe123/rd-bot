import assert from "node:assert/strict";
import test from "node:test";

import { normalizeProjectKnowledgeBaseId, UNBOUND_KNOWLEDGE_BASE_VALUE } from "../src/pages/admin/project/projectKnowledgeBinding.ts";

const knowledgeBases = [
  { id: "waimai", name: "waimai", enabled: true },
  { id: "disabled", name: "历史归档", enabled: false }
];

test("allows an explicit unbound project knowledge base", () => {
  assert.equal(normalizeProjectKnowledgeBaseId(UNBOUND_KNOWLEDGE_BASE_VALUE, knowledgeBases), "");
  assert.equal(normalizeProjectKnowledgeBaseId("", knowledgeBases), "");
});

test("accepts enabled knowledge bases and rejects unavailable choices", () => {
  assert.equal(normalizeProjectKnowledgeBaseId(" waimai ", knowledgeBases), "waimai");
  assert.throws(() => normalizeProjectKnowledgeBaseId("disabled", knowledgeBases), /已停用/);
  assert.throws(() => normalizeProjectKnowledgeBaseId("missing", knowledgeBases), /不存在/);
});
