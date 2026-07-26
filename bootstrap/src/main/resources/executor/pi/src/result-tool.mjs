import { mkdir, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const STATUSES = new Set(["SUCCESS", "FAILED", "NEED_INFO", "UNSAFE"]);

export function validateResult(result) {
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    throw new Error("result must be a JSON object");
  }
  if (!STATUSES.has(result.status)) {
    throw new Error("result.status is invalid");
  }
  if (typeof result.summary !== "string") {
    throw new Error("result.summary must be a string");
  }
  return result;
}

export async function writeResultAtomically(path, result) {
  validateResult(result);
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}`;
  await writeFile(temporary, `${JSON.stringify(result)}\n`, { encoding: "utf8", mode: 0o600 });
  await rename(temporary, path);
  return path;
}
