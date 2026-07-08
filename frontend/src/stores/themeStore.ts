import { create } from "zustand";

export type ThemeMode = "light" | "dark";

interface ThemeState {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  toggleTheme: () => void;
  initialize: () => void;
}

function applyTheme(theme: ThemeMode) {
  document.documentElement.classList.toggle("dark", theme === "dark");
}

/**
 * 主题状态。
 *
 * RD-Bot 当前为浅色主题，这里保留 light/dark 接口以兼容管理台的
 * MarkdownRenderer 等组件；不依赖持久化存储，默认 light。
 */
export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: "light",
  setTheme: (theme) => {
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: () => {
    const next = get().theme === "light" ? "dark" : "light";
    get().setTheme(next);
  },
  initialize: () => {
    applyTheme("light");
    set({ theme: "light" });
  }
}));
