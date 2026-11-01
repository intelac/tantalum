// 侧栏宽度和断点的纯逻辑，组件只负责把它们接到 DOM 上。

import { localStore } from "../app/storage";

export const SIDEBAR_MIN = 264;
export const SIDEBAR_MAX = 420;
export const SIDEBAR_DEFAULT = 280;
export const RAIL_WIDTH = 56;

export const WIDTH_KEY = "tantalum.sidebar.width";
export const COLLAPSED_KEY = "tantalum.sidebar.collapsed";

export type LayoutMode = "wide" | "medium" | "narrow";

export function clampWidth(width: number): number {
  if (!Number.isFinite(width)) return SIDEBAR_DEFAULT;
  return Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, Math.round(width)));
}

export function readWidth(storage: Pick<Storage, "getItem"> | null = localStore()): number {
  try {
    const stored = storage?.getItem(WIDTH_KEY) ?? null;
    return stored === null ? SIDEBAR_DEFAULT : clampWidth(Number(stored));
  } catch {
    return SIDEBAR_DEFAULT;
  }
}

export function saveWidth(width: number, storage: Pick<Storage, "setItem"> | null = localStore()): void {
  try {
    storage?.setItem(WIDTH_KEY, String(clampWidth(width)));
  } catch {
    // 存不了只影响这次。
  }
}

export function readCollapsed(storage: Pick<Storage, "getItem"> | null = localStore()): boolean {
  try {
    return storage?.getItem(COLLAPSED_KEY) === "true";
  } catch {
    return false;
  }
}

export function saveCollapsed(collapsed: boolean, storage: Pick<Storage, "setItem"> | null = localStore()): void {
  try {
    storage?.setItem(COLLAPSED_KEY, String(collapsed));
  } catch {
    // 同上。
  }
}

/** ≥1024 展开可拖动；768–1023 收成图标栏；<768 抽屉。 */
export function modeFor(viewportWidth: number): LayoutMode {
  if (viewportWidth >= 1024) return "wide";
  if (viewportWidth >= 768) return "medium";
  return "narrow";
}
