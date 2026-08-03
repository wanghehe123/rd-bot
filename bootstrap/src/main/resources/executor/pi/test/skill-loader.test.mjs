import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { SettingsManager } from "@earendil-works/pi-coding-agent";

import {
  createApprovedResourceLoader,
  loadSkillManifest,
  validateSkillManifest,
  SKILL_MANIFEST_PROTOCOL,
} from "../src/resource-loader.mjs";
import { executionPrompt } from "../src/rd-pi-bridge.mjs";
import {
  SKILL_MANIFEST_PATH,
  validateRequest,
} from "../src/protocol.mjs";

const requestBase = {
  protocol: "rd-pi-request/v1",
  snapshotId: "snapshot-1",
  stageRunId: "stage-1",
  taskId: "task-1",
  role: "QA_AGENT",
  prompt: "run acceptance",
  provider: "anthropic",
  model: "claude-sonnet-4-5",
  repoPath: "/work/repo",
  inputPath: "/work/input",
  outputPath: "/work/output",
  resourceManifestPath: "/work/input/resource-manifest.json",
  toolPolicy: { allow: ["read", "bash", "rd_submit_result"] },
};

async function writeFixtureSkill(skillRoot, skillId) {
  const skillDir = join(skillRoot, skillId);
  await mkdir(skillDir, { recursive: true });
  await writeFile(
    join(skillDir, "SKILL.md"),
    [
      "---",
      `name: ${skillId}`,
      "description: Fixture skill used by RD-Bot skill-loader tests.",
      "---",
      "",
      `# ${skillId}`,
      "",
      "Follow the fixture instructions.",
      "",
    ].join("\n"),
    "utf8",
  );
  return skillDir;
}

test("loadSkillManifest requires protocol and skillPaths under skill root", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-skill-manifest-"));
  const skillDir = await writeFixtureSkill(root, "qa-playwright-cli");
  const manifestPath = join(root, "skill-manifest.json");
  await writeFile(
    manifestPath,
    JSON.stringify({
      protocol: SKILL_MANIFEST_PROTOCOL,
      role: "QA_AGENT",
      skills: [{
        skillId: "qa-playwright-cli",
        version: "1.0.0",
        skillPath: skillDir,
        forceGuide: true,
        guidePrompt: "Always use playwright-cli for browser checks.",
        description: "QA browser skill",
      }],
      skillPaths: [skillDir],
    }),
    "utf8",
  );

  const loaded = await loadSkillManifest(manifestPath, { root });
  assert.equal(loaded.skillPaths.length, 1);
  assert.equal(loaded.skills.length, 1);
  assert.equal(loaded.skills[0].forceGuide, true);
  assert.match(loaded.skills[0].guidePrompt, /playwright-cli/);

  assert.throws(() => validateSkillManifest({
    protocol: "wrong",
    skills: [],
    skillPaths: [],
  }, { root }));
  assert.throws(() => validateSkillManifest({
    protocol: SKILL_MANIFEST_PROTOCOL,
    skills: [],
    skillPaths: ["/tmp/escaped-skill"],
  }, { root }));
});

test("createApprovedResourceLoader loads skills from additionalSkillPaths with noSkills true", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-skill-loader-"));
  const skillDir = await writeFixtureSkill(root, "qa-playwright-cli");
  const agentDir = await mkdtemp(join(tmpdir(), "rd-pi-agent-"));
  const cwd = await mkdtemp(join(tmpdir(), "rd-pi-repo-"));

  const resourceLoader = createApprovedResourceLoader({
    cwd,
    agentDir,
    verifiedManifest: {
      manifest: { extensionSetId: "test-set", extensionSetVersion: 1 },
      extensionPaths: [],
    },
    additionalSkillPaths: [skillDir],
    settingsManager: SettingsManager.inMemory(
      {
        compaction: { enabled: false },
        retry: { enabled: true, maxRetries: 2 },
        enableAnalytics: false,
        packages: [],
        extensions: [],
        skills: [skillDir],
        prompts: [],
        themes: [],
      },
      { projectTrusted: false },
    ),
  });

  await resourceLoader.reload();
  const skillsResult = resourceLoader.getSkills();
  assert.ok(Array.isArray(skillsResult.skills));
  assert.ok(skillsResult.skills.length > 0);
  assert.equal(skillsResult.skills[0].name, "qa-playwright-cli");
});

test("executionPrompt appends forceGuide section after role instructions", () => {
  const prompt = executionPrompt(requestBase, {
    skills: [
      {
        skillId: "qa-playwright-cli",
        forceGuide: true,
        guidePrompt: "Prefer playwright-cli over ad-hoc scripts.",
      },
      {
        skillId: "ignored",
        forceGuide: true,
        guidePrompt: "   ",
      },
      {
        skillId: "optional",
        forceGuide: false,
        guidePrompt: "Should not appear.",
      },
    ],
  });
  assert.match(prompt, /# 强制启用的 Skill 引导/);
  assert.match(prompt, /- qa-playwright-cli: Prefer playwright-cli over ad-hoc scripts\./);
  assert.doesNotMatch(prompt, /Should not appear/);
  assert.ok(prompt.indexOf("QA_AGENT role protocol") < prompt.indexOf("# 强制启用的 Skill 引导"));
});

test("skillManifestPath is optional and fixed when present", () => {
  assert.equal(validateRequest(requestBase), requestBase);
  const withPath = { ...requestBase, skillManifestPath: SKILL_MANIFEST_PATH };
  assert.equal(validateRequest(withPath), withPath);
  assert.throws(() => validateRequest({
    ...requestBase,
    skillManifestPath: "/work/input/other-skill-manifest.json",
  }));
});
