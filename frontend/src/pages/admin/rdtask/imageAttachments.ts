export const MAX_IMAGE_ATTACHMENTS = 10;
export const MAX_IMAGE_ATTACHMENT_BYTES = 10 * 1024 * 1024;

const ALLOWED_IMAGE_TYPES = new Set([
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/gif"
]);

export interface ImageAttachmentFile {
  name: string;
  type: string;
  size: number;
  lastModified: number;
}

export type ImageAttachmentRejectReason = "TYPE" | "SIZE" | "COUNT";

export interface ImageAttachmentRejection<T extends ImageAttachmentFile> {
  file: T;
  reason: ImageAttachmentRejectReason;
}

export interface ImageAttachmentMergeResult<T extends ImageAttachmentFile> {
  files: T[];
  rejected: Array<ImageAttachmentRejection<T>>;
}

export const imageAttachmentKey = (file: ImageAttachmentFile) =>
  `${file.name}:${file.type}:${file.size}:${file.lastModified}`;

export function mergeImageAttachments<T extends ImageAttachmentFile>(
  current: T[],
  incoming: T[]
): ImageAttachmentMergeResult<T> {
  const files = [...current];
  const knownKeys = new Set(files.map(imageAttachmentKey));
  const rejected: Array<ImageAttachmentRejection<T>> = [];

  for (const file of incoming) {
    if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
      rejected.push({ file, reason: "TYPE" });
      continue;
    }
    if (file.size > MAX_IMAGE_ATTACHMENT_BYTES) {
      rejected.push({ file, reason: "SIZE" });
      continue;
    }
    const key = imageAttachmentKey(file);
    if (knownKeys.has(key)) {
      continue;
    }
    if (files.length >= MAX_IMAGE_ATTACHMENTS) {
      rejected.push({ file, reason: "COUNT" });
      continue;
    }
    files.push(file);
    knownKeys.add(key);
  }

  return { files, rejected };
}

export function removeImageAttachment<T extends ImageAttachmentFile>(
  files: T[],
  key: string
): T[] {
  return files.filter((file) => imageAttachmentKey(file) !== key);
}
