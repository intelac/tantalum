// 深浅色：选择存在 localStorage，解析成 light/dark 写到 <html data-theme>。
// index.html 里有一段同样逻辑的内联脚本，在样式加载前先设好属性，避免闪烁。

import { useSyncExternalStore } from "react";
import { localStore } from "../app/storage";

export type Theme = "light" | "dark" | "system";
export type Resolved = "light" | "dark";

export const THEME_KEY = "tantalum.theme";
const DARK_QUERY = "(prefers-color-scheme: dark)";

const THEMES: readonly Theme[] = ["light", "dark", "system"];

export function readTheme(storage: Pick<Storage, "getItem"> | null = localStore()): Theme {
  try {
    const stored = storage?.getItem(THEME_KEY) ?? null;
    return THEMES.includes(stored as Theme) ? (stored as Theme) : "system";
  } catch {
    return "system";
  }
}

export function saveTheme(theme: Theme, storage: Pick<Storage, "setItem"> | null = localStore()): void {
  try {
    storage?.setItem(THEME_KEY, theme);
  } catch {
    // 私密模式下存不了，只影响这次会话。
  }
}

export function resolveTheme(theme: Theme, prefersDark: boolean): Resolved {
  if (theme === "system") return prefersDark ? "dark" : "light";
  return theme;
}

function prefersDark(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia(DARK_QUERY).matches;
}

export function applyTheme(theme: Theme, root: HTMLElement = document.documentElement): Resolved {
  const resolved = resolveTheme(theme, prefersDark());
  root.dataset.theme = resolved;
  return resolved;
}

const listeners = new Set<() => void>();
let current: Theme = readTheme();

function emit(): void {
  for (const listener of listeners) listener();
}

/** 应用当前选择并跟随系统变化。返回停止函数。 */
export function initTheme(): () => void {
  applyTheme(current);
  if (typeof window.matchMedia !== "function") return () => undefined;
  const media = window.matchMedia(DARK_QUERY);
  const onChange = (): void => {
    if (current === "system") applyTheme(current);
  };
  media.addEventListener("change", onChange);
  return () => media.removeEventListener("change", onChange);
}

export function setTheme(theme: Theme): void {
  current = theme;
  saveTheme(theme);
  applyTheme(theme);
  emit();
}

export function currentTheme(): Theme {
  return current;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useTheme(): [Theme, (theme: Theme) => void] {
  const theme = useSyncExternalStore(subscribe, currentTheme, currentTheme);
  return [theme, setTheme];
}
