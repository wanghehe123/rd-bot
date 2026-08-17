/** Coerce admin JSON list payloads; Vite SPA HTML fallbacks must not reach `.map` / `.filter`. */
export function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? value as T[] : [];
}
