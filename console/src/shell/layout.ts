// 侧栏宽度和断点的纯逻辑，组件只负责把它们接到 DOM 上。

import { localArea, readStored, writeStored } from "../ui/storage";

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

export function readWidth(storage: Pick<Storage, "getItem"> | null = localArea()): number {
  const stored = readStored(WIDTH_KEY, storage);
  return stored === null ? SIDEBAR_DEFAULT : clampWidth(Number(stored));
}

export function saveWidth(width: number, storage: Pick<Storage, "setItem"> | null = localArea()): void {
  writeStored(WIDTH_KEY, String(clampWidth(width)), storage);
}

export function readCollapsed(storage: Pick<Storage, "getItem"> | null = localArea()): boolean {
  return readStored(COLLAPSED_KEY, storage) === "true";
}

export function saveCollapsed(collapsed: boolean, storage: Pick<Storage, "setItem"> | null = localArea()): void {
  writeStored(COLLAPSED_KEY, String(collapsed), storage);
}

/** ≥1024 展开可拖动；768–1023 收成图标栏；<768 抽屉。 */
export function modeFor(viewportWidth: number): LayoutMode {
  if (viewportWidth >= 1024) return "wide";
  if (viewportWidth >= 768) return "medium";
  return "narrow";
}
