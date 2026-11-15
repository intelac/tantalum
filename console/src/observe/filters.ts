// 观测页面的筛选条件（observe-page 需求 2）：放在地址的查询参数里，收藏、刷新都保持。默认值不写进地址，
// 所以 `/runs` 就是“最近 7 天，从新到旧”。地址里不认识的值丢掉、用默认值。纯函数。

import type { RunEnd } from "../api/types";

export type RunWindow = "24h" | "7d" | "30d" | "all";
export type RunSort = "started" | "ms" | "tokens";

export const WINDOWS: readonly RunWindow[] = ["24h", "7d", "30d", "all"];
export const SORTS: readonly RunSort[] = ["started", "ms", "tokens"];
export const ENDS: readonly RunEnd[] = ["COMPLETED", "FAILED", "CANCELLED", "STOPPED", "RUNNING"];

export interface Filters {
  window: RunWindow;
  ends: readonly RunEnd[];
  toolErrors: boolean;
  tool?: string;
  model?: string;
  sort: RunSort;
}

export const DEFAULT_FILTERS: Filters = { window: "7d", ends: [], toolErrors: false, sort: "started" };

const WINDOW_MS: Record<Exclude<RunWindow, "all">, number> = {
  "24h": 24 * 3_600_000,
  "7d": 7 * 24 * 3_600_000,
  "30d": 30 * 24 * 3_600_000,
};

export function parseFilters(query: string): Filters {
  const params = new URLSearchParams(query);
  const window = params.get("window");
  const sort = params.get("sort");
  const ends = (params.get("end") ?? "")
    .split(",")
    .map((value) => value.trim().toUpperCase())
    .filter((value): value is RunEnd => (ENDS as readonly string[]).includes(value));
  const tool = params.get("tool");
  const model = params.get("model");
  return {
    window: (WINDOWS as readonly string[]).includes(window ?? "") ? (window as RunWindow) : DEFAULT_FILTERS.window,
    ends: ENDS.filter((end) => ends.includes(end)),
    toolErrors: params.get("toolErrors") === "true",
    ...(tool !== null && tool !== "" ? { tool } : {}),
    ...(model !== null && model !== "" ? { model } : {}),
    sort: (SORTS as readonly string[]).includes(sort ?? "") ? (sort as RunSort) : DEFAULT_FILTERS.sort,
  };
}

/** 地址上的查询参数（不带 `?`），默认值不写。 */
export function serializeFilters(filters: Filters): string {
  const params = new URLSearchParams();
  if (filters.window !== DEFAULT_FILTERS.window) params.set("window", filters.window);
  if (filters.ends.length > 0) params.set("end", ENDS.filter((end) => filters.ends.includes(end)).join(","));
  if (filters.toolErrors) params.set("toolErrors", "true");
  if (filters.tool !== undefined) params.set("tool", filters.tool);
  if (filters.model !== undefined) params.set("model", filters.model);
  if (filters.sort !== DEFAULT_FILTERS.sort) params.set("sort", filters.sort);
  return params.toString();
}

/** 时间窗的起点；“全部”没有起点。按分钟取整，免得每次渲染都换一个查询键。 */
export function windowStart(window: RunWindow, now: number): Date | undefined {
  if (window === "all") return undefined;
  return new Date(Math.floor((now - WINDOW_MS[window]) / 60_000) * 60_000);
}

/** 发给 `/api/runs` 的参数（不带 `?`）。`summary` 为 true 时不带排序，带上浏览器的时区（按天的图用）。 */
export function apiQuery(filters: Filters, now: number, summary = false, zone = browserZone()): string {
  const params = new URLSearchParams();
  const from = windowStart(filters.window, now);
  if (from !== undefined) params.set("from", from.toISOString());
  if (filters.ends.length > 0) params.set("end", filters.ends.join(","));
  if (filters.toolErrors) params.set("toolErrors", "true");
  if (filters.tool !== undefined) params.set("tool", filters.tool);
  if (filters.model !== undefined) params.set("model", filters.model);
  if (summary) params.set("tz", zone);
  else if (filters.sort !== "started") params.set("sort", filters.sort);
  return params.toString();
}

export function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}
