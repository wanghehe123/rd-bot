import type { PageResponse, ToastTone } from "./types";

export function cn(...values: Array<string | false | null | undefined>) {
  return values.filter(Boolean).join(" ");
}

export function recordsOf<T>(page: PageResponse<T> | T[] | null | undefined): T[] {
  if (Array.isArray(page)) return page;
  return page?.records || page?.items || [];
}

export function totalOf<T>(page: PageResponse<T> | T[] | null | undefined): number {
  if (Array.isArray(page)) return page.length;
  return page?.total ?? recordsOf(page).length;
}

export function formatTime(value?: string | number | null) {
  if (!value) return "-";
  const date = typeof value === "number" ? new Date(value) : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("zh-CN", { hour12: false });
}

export function truncate(value: unknown, length = 80) {
  const text = String(value ?? "");
  return text.length > length ? `${text.slice(0, length)}...` : text;
}

export function enabledLabel(value: unknown) {
  return value === false || value === 0 ? "停用" : "启用";
}

export function notify(message: string, tone: ToastTone = "info") {
  window.dispatchEvent(new CustomEvent("rd-bot-toast", { detail: { message, tone } }));
}

export async function runAction(action: () => Promise<void>, success: string) {
  try {
    await action();
    notify(success, "success");
  } catch (error) {
    notify(error instanceof Error ? error.message : "操作失败", "error");
  }
}
