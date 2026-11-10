// 只属于这个浏览器的侧栏状态（workspaces W9–W12）：视图选项、收起的组、上次用的工作区存 localStorage；
// 还没发出第一句的新会话选了哪个工作区存 sessionStorage，和草稿一样。读写都经 ui/storage，拿不到存储就用默认值。

import { useCallback, useState } from "react";
import { localArea, readStored, removeStored, sessionArea, writeStored } from "../ui/storage";
import type { ViewOptions } from "./group";

type Area = Pick<Storage, "getItem" | "setItem" | "removeItem">;

const VIEW = "tantalum.sidebar.view";
const COLLAPSED = "tantalum.sidebar.collapsed";
const LAST_PROJECT = "tantalum.project.last";
const PENDING = "tantalum.project.";

export const DEFAULT_VIEW: ViewOptions = { mode: "project", showArchived: false };

export function readView(area: Area | null = localArea()): ViewOptions {
  const stored = readStored(VIEW, area);
  if (stored === null) return DEFAULT_VIEW;
  try {
    const value = JSON.parse(stored) as Partial<ViewOptions>;
    return { mode: value.mode === "flat" ? "flat" : "project", showArchived: value.showArchived === true };
  } catch {
    return DEFAULT_VIEW;
  }
}

export function useViewOptions(): [ViewOptions, (next: ViewOptions) => void] {
  const [view, setView] = useState(readView);
  const update = useCallback((next: ViewOptions) => {
    setView(next);
    writeStored(VIEW, JSON.stringify(next), localArea());
  }, []);
  return [view, update];
}

export function readCollapsed(area: Area | null = localArea()): string[] {
  const stored = readStored(COLLAPSED, area);
  if (stored === null) return [];
  try {
    const value: unknown = JSON.parse(stored);
    return Array.isArray(value) ? value.filter((key): key is string => typeof key === "string") : [];
  } catch {
    return [];
  }
}

/** 收起的组；刷新后保持（需求 3.3）。 */
export function useCollapsed(): [ReadonlySet<string>, (key: string) => void] {
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set(readCollapsed()));
  const toggle = useCallback((key: string) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      writeStored(COLLAPSED, JSON.stringify([...next]), localArea());
      return next;
    });
  }, []);
  return [collapsed, toggle];
}

export function readLastProject(area: Area | null = localArea()): string | null {
  return readStored(LAST_PROJECT, area);
}

export function saveLastProject(id: string | null, area: Area | null = localArea()): void {
  if (id === null) removeStored(LAST_PROJECT, area);
  else writeStored(LAST_PROJECT, id, area);
}

/** 新会话选的工作区：空串表示“不选”，没有记录为 null（用默认）。 */
export function readPending(session: string, area: Area | null = sessionArea()): string | null {
  return readStored(PENDING + session, area);
}

export function savePending(session: string, id: string | null, area: Area | null = sessionArea()): void {
  writeStored(PENDING + session, id ?? "", area);
}

export function clearPending(session: string, area: Area | null = sessionArea()): void {
  removeStored(PENDING + session, area);
}
