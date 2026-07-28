import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const trace = readFileSync(
  new URL("../src/components/admin/rdtask/ReadableAgentTrace.tsx", import.meta.url),
  "utf8"
);

test("renders process, tools, and normalized event views without an input surface", () => {
  assert.match(trace, /TabsTrigger value="process"[^>]*>过程<\/TabsTrigger>/);
  assert.match(trace, /TabsTrigger value="tools"[^>]*>工具<\/TabsTrigger>/);
  assert.match(trace, /TabsTrigger value="raw"[^>]*>原始<\/TabsTrigger>/);
  assert.match(trace, /MarkdownRenderer/);
  assert.match(trace, /ArrowDown/);
  assert.match(trace, /回到最新/);
  assert.doesNotMatch(trace, /textarea|stdin|EventSource/);
});

test("keeps completed steps cheap to render and exposes trace pagination", () => {
  assert.match(trace, /contentVisibility/);
  assert.match(trace, /containIntrinsicSize/);
  assert.match(trace, /onLoadMore/);
  assert.match(trace, /加载更多记录/);
});

test("opens at the newest event and uses a GitHub-style developer log surface", () => {
  assert.match(trace, /scrollTop = container\.scrollHeight/);
  assert.match(trace, /最新记录/);
  assert.match(trace, /#0d1117/);
  assert.match(trace, /#30363d/);
  assert.match(trace, /只读执行记录/);
});
