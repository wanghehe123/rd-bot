# RD-Bot REQUIREMENT Lifecycle Atlas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: execute this plan inline and verify each task before moving on. Repository rules prohibit commits unless the user explicitly requests them.

**Goal:** Build a polished, source-backed, standalone interactive HTML atlas for the RD-Bot REQUIREMENT task and Agent-stage state machines.

**Architecture:** One HTML file contains the structured state/transition dataset, semantic document markup, CSS, inline SVG rendering, and interaction logic. The main task machine and per-role stage machine remain separate; every item carries an `ACTUAL`, `LEGAL`, `DECLARED`, or `CONTROL_EVENT` truth label and source references.

**Tech Stack:** HTML5, CSS3, vanilla JavaScript, inline SVG, browser DOM APIs, print CSS, in-app browser QA.

---

## File Structure

- Create: `docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`
  - The only deliverable file.
  - Owns content data, rendering, interactions, responsive layout, accessibility, and print output.
- Reference only: `docs/superpowers/specs/2026-07-11-rd-bot-requirement-lifecycle-atlas-design.md`
- Reference only: current Java, SQL, AGENTS.md, RULE.md, and MEMORY sources listed in the design.

## Task 1: Establish the source-backed data contract

**Files:**
- Create: `docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`

- [ ] **Step 1: Verify the deliverable does not already exist**

Run:

```bash
test ! -f docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html
```

Expected: exit code `0` before creation.

- [ ] **Step 2: Create the semantic shell and structured page truth**

The HTML must expose a stable page QA object:

```js
window.__RD_ATLAS__ = {
  generatedAt: "2026-07-11",
  scope: "REQUIREMENT",
  taskStates,
  taskTransitions,
  stageStates,
  stageTransitions,
  roles,
  findings,
  extensions,
  sources
};
```

Required task state count: `24`.

Required stage state count: `12`.

Required role order:

```js
["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
```

Each task transition uses this complete shape:

```js
{
  id: "waiting-policy--executing",
  from: "WAITING_POLICY",
  to: "EXECUTING",
  truth: "ACTUAL",
  phase: "policy",
  trigger: "策略放行，或审批事件已经存在",
  guard: "policyDecision.allowed() == true",
  writes: ["status", "promptSnapshot", "rd_task_status_events"],
  artifacts: ["RequirementPolicyDecision", "Prompt snapshot"],
  failure: "执行阶段失败后进入 REJECTED 或 FAILED_NEEDS_HUMAN",
  recovery: "失败终态重新提交时为失败角色创建更大的 attemptNo",
  source: ["RequirementDeliveryEngine.java:635", "RagStreamTaskRegistry.java:1006"]
}
```

- [ ] **Step 3: Verify the source snapshot contains every enum value**

Run:

```bash
for state in CREATED MATERIAL_COLLECTING MATERIAL_READY CONTEXT_BUILDING CONTEXT_READY PLAN_GENERATING PLAN_GENERATED WAITING_POLICY WAITING_APPROVAL SEARCHING EXECUTING VALIDATING PR_CREATING COMMITTED MERGED REPORTING COMPLETED REJECTED FAILED_RETRYABLE FAILED_NEEDS_HUMAN CANCELLED DEAD_LETTERED RECOVERING DELETED; do rg -q "id: \"$state\"" docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html || exit 1; done
```

Expected: exit code `0`.

## Task 2: Build the technology-control-plane visual system

**Files:**
- Modify: `docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`

- [ ] **Step 1: Add the fixed control header and chapter rail**

Required top-level regions:

```html
<header class="control-header">...</header>
<nav class="chapter-rail" aria-label="文档章节">...</nav>
<main id="main-content">...</main>
<aside class="inspector" aria-live="polite">...</aside>
```

The first viewport must show the task graph, not a marketing hero.

- [ ] **Step 2: Add the approved visual language**

Use semantic CSS variables:

```css
:root {
  --bg: #080d10;
  --panel: #0d1518;
  --line: #263f41;
  --text: #dbe8e6;
  --muted: #78908f;
  --actual: #45e0cf;
  --approval: #f1b957;
  --failure: #ff756e;
  --declared: #8b9699;
  --success: #45e09a;
  --violet: #a78bd8;
}
```

Use a micro-grid, precise borders, telemetry labels, state coordinates, and restrained glow. Do not add decorative orbs, a gradient hero, nested cards, or remote fonts.

- [ ] **Step 3: Implement responsive and print layouts**

Required breakpoints:

```css
@media (max-width: 980px) { /* collapse inspector below graph */ }
@media (max-width: 700px) { /* hide rail, stack controls, vertical graph */ }
@media print { /* white background, expand details, hide controls */ }
```

Expected: no horizontal overflow at `1440x1000`, `1024x768`, and `390x844`.

## Task 3: Implement task and Agent-stage exploration

**Files:**
- Modify: `docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`

- [ ] **Step 1: Render the REQUIREMENT main graph**

Render a readable success spine plus branch lanes using inline SVG. Nodes and edges must be buttons or keyboard-focusable elements with stable identifiers:

```html
<button class="state-node" data-state="WAITING_POLICY" aria-label="查看 WAITING_POLICY 状态">...</button>
<button class="edge-hitbox" data-transition="waiting-policy--executing" aria-label="查看 WAITING_POLICY 到 EXECUTING 转移">...</button>
```

The view must distinguish actual, approval, failure, legal-only, and declared states through text labels as well as color.

- [ ] **Step 2: Render the four-role pipeline separately**

Show the fixed role order and reuse one stage-state graph per role:

```text
PENDING -> CONTEXT_READY -> DISPATCHING -> RUNNING
-> RESULT_COLLECTING -> VERIFYING -> SUCCEEDED
```

Also render `FAILED_RETRYABLE -> RECOVERING`, `FAILED_NEEDS_HUMAN`, `SKIPPED`, and `CANCELLED` branches with the exact legality from `AgentStageTransitions`.

- [ ] **Step 3: Implement the inspector**

Clicking a node shows definition, phase, truth, incoming/outgoing edges, source references, persisted fields, and operational notes. Clicking an edge shows all fields in the transition contract.

- [ ] **Step 4: Implement search and truth filters**

Required controls:

```html
<input id="atlas-search" type="search" aria-label="搜索状态、方法或概念">
<button data-filter="ACTUAL" aria-pressed="true">ACTUAL</button>
<button data-filter="LEGAL" aria-pressed="true">LEGAL</button>
<button data-filter="DECLARED" aria-pressed="true">DECLARED</button>
<button data-filter="CONTROL_EVENT" aria-pressed="true">CONTROL_EVENT</button>
```

Search must match English state IDs, Chinese explanations, class names, and method references.

## Task 4: Add path rehearsal, audit, extension, and evidence sections

**Files:**
- Modify: `docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`

- [ ] **Step 1: Add four deterministic rehearsal scenarios**

Scenarios:

```js
{
  success: ["CREATED", "MATERIAL_COLLECTING", "MATERIAL_READY", "CONTEXT_BUILDING", "CONTEXT_READY", "PLAN_GENERATING", "PLAN_GENERATED", "WAITING_POLICY", "EXECUTING", "VALIDATING", "PR_CREATING", "COMMITTED", "REPORTING", "COMPLETED"],
  approval: ["PLAN_GENERATED", "WAITING_POLICY", "WAITING_APPROVAL", "EXECUTING"],
  humanBlock: ["WAITING_POLICY", "FAILED_NEEDS_HUMAN"],
  retry: ["FAILED_NEEDS_HUMAN", "MATERIAL_COLLECTING", "CONTEXT_BUILDING", "EXECUTING"]
}
```

Play, pause, next, previous, and reset controls must never resize the layout.

- [ ] **Step 2: Add the optimization audit**

Render the confirmed P0/P1/P2 findings from the design. Each finding includes evidence, impact, recommendation, acceptance signal, and confidence.

- [ ] **Step 3: Add the extension roadmap**

Render short, medium, and long-term extensions. Each item includes value, dependency, minimum implementation, acceptance signal, and main risk.

- [ ] **Step 4: Add source evidence and glossary**

Include module map, execution bridge, experience write/read path, persistent tables, class-size evidence, source paths, and a glossary of task state, stage state, attempt, context package, policy gate, artifact, and experience.

## Task 5: Verify content parity and browser behavior

**Files:**
- Verify: `docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`

- [ ] **Step 1: Run static checks**

Run:

```bash
test -s docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html
rg -q 'window.__RD_ATLAS__' docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html
rg -q 'REQUIREMENT_REVIEWER' docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html
rg -q 'RequirementDeliveryEngine.java:635' docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html
git diff --check
```

Expected: every command exits `0`.

- [ ] **Step 2: Serve the document locally**

Run:

```bash
python3 -m http.server 4173 --bind 127.0.0.1
```

Open:

`http://127.0.0.1:4173/docs/reports/2026-07-11-rd-bot-requirement-lifecycle-atlas.html`

- [ ] **Step 3: Verify page truth in the browser**

Evaluate:

```js
({
  taskStates: window.__RD_ATLAS__.taskStates.length,
  stageStates: window.__RD_ATLAS__.stageStates.length,
  roles: window.__RD_ATLAS__.roles.length,
  horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
})
```

Expected:

```json
{"taskStates":24,"stageStates":12,"roles":4,"horizontalOverflow":false}
```

- [ ] **Step 4: Verify interactions**

At desktop and mobile viewports:

- Search `WAITING_POLICY` and confirm matching nodes/edges remain visible.
- Toggle `DECLARED` and confirm declared-only nodes are hidden and restored.
- Open `WAITING_POLICY -> EXECUTING` and verify guard, writes, recovery, and source.
- Run all four rehearsal scenarios and reset each one.
- Navigate all sections from the rail and mobile section control.
- Confirm keyboard focus indicators and unique accessible names.
- Confirm browser console has no errors.

- [ ] **Step 5: Record verification outcome**

The final response must report:

- Artifact path and localhost URL.
- Static checks executed.
- Desktop/mobile browser checks executed.
- Any missing external verification, explicitly noting that no Provider, Docker, PR, Feishu, or production database side effect was required.
