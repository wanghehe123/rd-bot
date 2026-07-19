import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const source = readFileSync(new URL("../src/components/ProjectScopeSelector.tsx", import.meta.url), "utf8");

test("project scope selector supports all-project, loading, empty, and unavailable states", () => {
  assert.match(source, /allowAll/);
  assert.match(source, /loading/);
  assert.match(source, /unavailable/);
  assert.match(source, /ALL_PROJECTS_SCOPE/);
  assert.match(source, /正在加载项目/);
  assert.match(source, /暂无启用项目/);
  assert.match(source, /项目服务不可用/);
});

test("project scope selector keeps its choice accessible and width-bounded", () => {
  assert.match(source, /aria-label="选择项目"/);
  assert.match(source, /min-w-\[11rem\]/);
  assert.match(source, /sm:w-\[15rem\]/);
});

test("project scope selector stays controlled while the async project choice loads", () => {
  assert.match(source, /<Select value=\{projectId\}/);
  assert.doesNotMatch(source, /projectId \|\| undefined/);
});
