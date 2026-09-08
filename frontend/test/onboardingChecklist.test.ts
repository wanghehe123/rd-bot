import assert from "node:assert/strict";
import test from "node:test";

import {
  hasUsableProvider,
  onboardingChecklist,
  onboardingComplete,
  providerConfigBlockReason,
  type OnboardingProjectSnapshot,
  type OnboardingProviderSnapshot
} from "../src/pages/dashboard/onboarding.ts";

const readyProvider: OnboardingProviderSnapshot = { enabled: true, credentialConfigured: true };
const unconfiguredProvider: OnboardingProviderSnapshot = { enabled: true, credentialConfigured: false };
const disabledProvider: OnboardingProviderSnapshot = { enabled: false, credentialConfigured: true };
const authorizedProject: OnboardingProjectSnapshot = {
  enabled: true,
  repositoryUrl: "https://github.com/example-org/hello-rd-bot.git"
};
const bareProject: OnboardingProjectSnapshot = { enabled: true, repositoryUrl: "" };

test("hasUsableProvider requires enabled and configured credentials", () => {
  assert.equal(hasUsableProvider([]), false);
  assert.equal(hasUsableProvider([unconfiguredProvider]), false);
  assert.equal(hasUsableProvider([disabledProvider]), false);
  assert.equal(hasUsableProvider([disabledProvider, unconfiguredProvider]), false);
  assert.equal(hasUsableProvider([readyProvider]), true);
});

test("providerConfigBlockReason names the missing configuration instead of failing silently", () => {
  assert.equal(providerConfigBlockReason([readyProvider]), null);
  assert.match(String(providerConfigBlockReason([])), /尚未配置模型供应商/);
  assert.match(String(providerConfigBlockReason([unconfiguredProvider])), /缺少 API Key/);
  assert.match(String(providerConfigBlockReason([disabledProvider])), /缺少 API Key/);
});

test("onboardingChecklist tracks provider, project, repository and requirement steps", () => {
  const empty = onboardingChecklist({ providers: [], projects: [] });
  assert.deepEqual(
    empty.map((step) => step.id),
    ["provider", "project", "repository", "requirement"]
  );
  assert.equal(empty.every((step) => !step.done || step.id === "requirement"), true);
  assert.equal(empty[0].href, "/admin/model-providers");

  const partial = onboardingChecklist({
    providers: [readyProvider],
    projects: [bareProject]
  });
  assert.equal(partial.find((step) => step.id === "provider")?.done, true);
  assert.equal(partial.find((step) => step.id === "project")?.done, true);
  assert.equal(partial.find((step) => step.id === "repository")?.done, false);

  const full = onboardingChecklist({ providers: [readyProvider], projects: [authorizedProject] });
  assert.equal(full.find((step) => step.id === "repository")?.done, true);
});

test("onboardingComplete ignores the user-driven requirement step", () => {
  const steps = onboardingChecklist({ providers: [readyProvider], projects: [authorizedProject] });
  assert.equal(onboardingComplete(steps), true);
  const incomplete = onboardingChecklist({ providers: [readyProvider], projects: [bareProject] });
  assert.equal(onboardingComplete(incomplete), false);
});
