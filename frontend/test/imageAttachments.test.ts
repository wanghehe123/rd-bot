import assert from "node:assert/strict";
import test from "node:test";

import {
  MAX_IMAGE_ATTACHMENT_BYTES,
  imageAttachmentKey,
  mergeImageAttachments,
  removeImageAttachment,
  type ImageAttachmentFile
} from "../src/pages/admin/rdtask/imageAttachments.ts";

const file = (
  name: string,
  type: string,
  size: number,
  lastModified: number
): ImageAttachmentFile => ({ name, type, size, lastModified });

test("appends valid images, deduplicates them, and removes one by key", () => {
  const first = file("first.png", "image/png", 1024, 1);
  const second = file("second.webp", "image/webp", 2048, 2);

  const merged = mergeImageAttachments([first], [first, second]);

  assert.deepEqual(merged.files, [first, second]);
  assert.deepEqual(merged.rejected, []);
  assert.deepEqual(
    removeImageAttachment(merged.files, imageAttachmentKey(first)),
    [second]
  );
});

test("rejects unsupported and oversized images before upload", () => {
  const unsupported = file("notes.txt", "text/plain", 128, 1);
  const oversized = file("large.png", "image/png", MAX_IMAGE_ATTACHMENT_BYTES + 1, 2);

  const merged = mergeImageAttachments([], [unsupported, oversized]);

  assert.deepEqual(merged.files, []);
  assert.deepEqual(merged.rejected.map((item) => item.reason), ["TYPE", "SIZE"]);
});

test("keeps at most ten pending images", () => {
  const files = Array.from({ length: 11 }, (_, index) =>
    file(`${index}.jpg`, "image/jpeg", 100, index)
  );

  const merged = mergeImageAttachments([], files);

  assert.equal(merged.files.length, 10);
  assert.deepEqual(merged.rejected.map((item) => item.reason), ["COUNT"]);
});
