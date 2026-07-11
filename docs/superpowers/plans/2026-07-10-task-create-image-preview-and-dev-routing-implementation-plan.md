# Task Create Image Preview and Dev Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users preview and remove pending task images, restore visible template feedback, and route AI draft requests correctly through the Vite development server.

**Architecture:** Keep image bytes in the existing `File[]` submission flow and add a small pure selection helper for validation, deduplication, and removal. Render temporary browser Object URLs only in the task form and revoke them on state changes. Fix the two confirmed development defects at their sources: mount Sonner's `Toaster` and proxy `/admin/rd-task-drafts` to Spring Boot.

**Tech Stack:** React 18, TypeScript, Vite 6, Sonner, Lucide, Node test runner.

**Repository rule:** Do not commit; repository `AGENTS.md` requires an explicit user request before commit or push.

---

### Task 1: Define pending image selection behavior

**Files:**
- Create: `frontend/src/pages/admin/rdtask/imageAttachments.ts`
- Create: `frontend/test/imageAttachments.test.ts`

- [x] **Step 1: Write failing selection tests**

Cover append, duplicate removal, invalid MIME, 10 MiB rejection, count limit, and single-item deletion using file-like fixtures.

```ts
const first = file("first.png", "image/png", 1024, 1);
const second = file("second.webp", "image/webp", 2048, 2);
assert.deepEqual(mergeImageAttachments([first], [second]).files, [first, second]);
assert.deepEqual(removeImageAttachment([first, second], imageAttachmentKey(first)), [second]);
```

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
cd frontend
node --experimental-strip-types --test test/imageAttachments.test.ts
```

Expected: FAIL because `imageAttachments.ts` does not exist.

- [x] **Step 3: Implement the pure helper**

Export:

```ts
export const MAX_IMAGE_ATTACHMENTS = 10;
export const MAX_IMAGE_ATTACHMENT_BYTES = 10 * 1024 * 1024;
export const imageAttachmentKey = (file: ImageAttachmentFile) =>
  `${file.name}:${file.type}:${file.size}:${file.lastModified}`;
export function mergeImageAttachments<T extends ImageAttachmentFile>(current: T[], incoming: T[]): ImageAttachmentMergeResult<T>;
export function removeImageAttachment<T extends ImageAttachmentFile>(files: T[], key: string): T[];
```

The merge result contains accepted `files` and rejected entries with `TYPE`, `SIZE`, or `COUNT` reasons.

- [x] **Step 4: Re-run and verify GREEN**

Run the command from Step 2. Expected: all helper tests PASS.

### Task 2: Render thumbnail cards and remove pending files

**Files:**
- Modify: `frontend/src/pages/admin/rdtask/RdTaskListPage.tsx`

- [x] **Step 1: Preserve the browser-level failing evidence**

Current bundle shows only `已选 N 张` and has no image preview or delete button.

- [x] **Step 2: Append validated image selections**

Replace direct `setAttachmentFiles(Array.from(...))` with `mergeImageAttachments`. Clear `event.currentTarget.value` after reading files so a deleted file can be selected again. Display one visible toast for rejected type, size, or count.

- [x] **Step 3: Create and revoke preview URLs**

Use `useMemo` to create `{ key, file, url }` records with `URL.createObjectURL`, and an effect cleanup that calls `URL.revokeObjectURL` for every generated URL.

- [x] **Step 4: Render the responsive thumbnail grid**

Below the toolbar, render repeated compact items with a stable square preview, truncated filename, formatted size, and an icon-only `Trash2` button using `aria-label="删除图片 <filename>"`. Deleting calls `removeImageAttachment`; the existing submit loops continue to upload only `attachmentFiles`.

- [x] **Step 5: Typecheck and build**

```bash
cd frontend
npm run typecheck
npm run build
```

Expected: PASS; Vite may retain the existing non-blocking large-chunk warning.

### Task 3: Route AI drafts through Vite

**Files:**
- Modify: `frontend/vite.config.ts`
- Create: `frontend/test/viteProxy.test.ts`

- [x] **Step 1: Write the failing proxy assertion**

Import the Vite config and assert `server.proxy` contains `/admin/rd-task-drafts` with target `http://127.0.0.1:18080`.

- [x] **Step 2: Run and verify RED**

```bash
cd frontend
node --experimental-strip-types --test test/viteProxy.test.ts
```

Expected: FAIL because the prefix is absent and POST currently returns Vite 404.

- [x] **Step 3: Add the proxy prefix**

Add:

```ts
"/admin/rd-task-drafts": backendTarget,
```

No HTML navigation bypass is needed because this prefix is API-only.

- [x] **Step 4: Re-run and verify GREEN**

Run the command from Step 2. Expected: PASS.

### Task 4: Restore visible Sonner feedback

**Files:**
- Modify: `frontend/src/App.tsx`
- Create: `frontend/test/notificationHost.test.ts`

- [x] **Step 1: Write a failing structured source test**

Parse `App.tsx` with the TypeScript compiler API and assert that the root JSX contains a `Toaster` element imported from `sonner`.

- [x] **Step 2: Run and verify RED**

```bash
cd frontend
node --experimental-strip-types --test test/notificationHost.test.ts
```

Expected: FAIL because App currently mounts only the legacy `ToastHost`.

- [x] **Step 3: Mount Sonner without removing legacy notifications**

Import `Toaster` from `sonner` and render:

```tsx
<Toaster richColors position="top-right" closeButton />
<ToastHost />
```

This makes `toast.error("请先选择项目")`, template success, AI unavailable, and request failures visible.

- [x] **Step 4: Re-run and verify GREEN**

Run the command from Step 2. Expected: PASS.

### Task 5: Regression and real interaction evidence

**Files:**
- Modify: `docs/qa/rd-task-control-plane-enhancements-acceptance-report-2026-07-10.md`
- Modify: `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-lessons-spec.md`

- [x] **Step 1: Run focused and frontend regression**

```bash
cd frontend
node --experimental-strip-types --test test/imageAttachments.test.ts test/viteProxy.test.ts test/notificationHost.test.ts test/alertRecipients.test.ts
npm run typecheck
npm run build
cd ..
git diff --check
```

- [x] **Step 2: Verify real backend contracts**

```bash
curl -fsS 'http://127.0.0.1:18080/admin/projects/7479447343427883008/task-templates/BUG_FIX'
curl -fsS -X POST 'http://127.0.0.1:18080/admin/rd-task-drafts/complete' \
  -H 'Content-Type: application/json' \
  -d '{"taskType":"BUG_FIX","projectId":"7479447343427883008","currentValues":{},"materialSummaries":[]}'
```

Expected: both return HTTP 200. The draft response may be `available=false` when the configured model credential is absent, but must not be 404.

- [x] **Step 3: Verify the Vite-served UI**

Restart Vite after changing its config. Confirm that selecting images displays thumbnails, deletion updates the count, template actions show visible feedback, and `/admin/rd-task-drafts/complete` is forwarded instead of returning 404.

- [x] **Step 4: Record evidence and lessons**

Document test results, browser state, the missing-proxy root cause, and the distinction between HTTP routing failure and a correctly routed `available=false` provider response.

## Plan self-review

- Spec coverage: thumbnail display/delete, image limits, template feedback, Vite AI routing, and no-credential behavior each map to a concrete task.
- Placeholder scan: no TBD/TODO or unspecified implementation steps remain.
- Type consistency: `ImageAttachmentFile`, `imageAttachmentKey`, `mergeImageAttachments`, and `removeImageAttachment` are used consistently across tests and UI.

## Implementation evidence

- RED was observed independently for the missing image helper, missing Vite draft proxy, and missing Sonner `Toaster`; all focused tests are now green.
- `POST http://127.0.0.1:5174/admin/rd-task-drafts/complete` returns HTTP 200 through Vite. The current backend correctly reports `available=false` with `task draft model credential is not configured`, rather than Vite 404.
- `GET http://127.0.0.1:5174/admin/projects/7479447343427883008/task-templates/BUG_FIX` returns HTTP 200. Browser verification confirmed both the visible “请先选择项目” prompt and successful template field population after selecting the project.
- The thumbnail selection helper covers append, deduplication, type/size/count rejection, and single-item removal. Automated browser file injection is blocked by the browser-control surface, so final real-file thumbnail selection remains a manual check on the running Vite page.
