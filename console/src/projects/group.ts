// 侧栏怎么分组（workspaces W4、W9、W10、W13）。纯函数，不碰 DOM 和存储。

import type { Project, SessionSummary } from "../api/types";
import { pathKey } from "./pathKey";

/** “未分组”的键：会话没有目录，或者目录对不上任何工作区。 */
export const UNGROUPED = "ungrouped";
/** 平铺时唯一一组的键。 */
export const FLAT = "flat";
/** 每组先显示几条，“再显示”每次加几条（DSH WorkspaceBrowser.tsx:55-94）。 */
export const PAGE = 5;
/** 搜索最多列几条。 */
export const SEARCH_LIMIT = 20;

export interface ViewOptions {
  mode: "project" | "flat";
  showArchived: boolean;
}

export interface Group {
  key: string;
  /** 工作区；“未分组”和平铺时为 null。 */
  project: Project | null;
  /** 这一组一共几个会话（不算隐藏的已归档）。 */
  count: number;
  rows: SessionSummary[];
  /** 还没显示的条数。 */
  hidden: number;
}

/** 会话属于哪个工作区：目录和工作区路径规范化后相同。 */
export function projectOf(session: SessionSummary, projects: readonly Project[]): Project | null {
  if (session.dir === undefined || session.dir === null) return null;
  const key = pathKey(session.dir);
  return projects.find((project) => pathKey(project.path) === key) ?? null;
}

/** 运行中、等你回答、当前打开的：不管排第几都显示。 */
function sticky(session: SessionSummary, current: string | null): boolean {
  return session.id === current || session.awaitingAnswer === true || session.runStatus !== "idle";
}

function newestFirst(a: SessionSummary, b: SessionSummary): number {
  return b.lastActivity.localeCompare(a.lastActivity);
}

/**
 * 分组。工作区按给的顺序（服务器给的是加入时间新的在前），没有会话的工作区也列出来；“未分组”有会话时才列，放最后。
 * 组内按最近活动排，先显示 {@link PAGE} 条加上 {@code extra[key]} 条，置顶的几种总显示。平铺时一组，全部显示。
 */
export function groupSessions(
  sessions: readonly SessionSummary[],
  projects: readonly Project[],
  options: ViewOptions,
  extra: Readonly<Record<string, number>>,
  current: string | null,
): Group[] {
  const visible = sessions.filter((session) => options.showArchived || session.archived !== true).slice().sort(newestFirst);
  if (options.mode === "flat") return [{ key: FLAT, project: null, count: visible.length, rows: visible, hidden: 0 }];
  const byKey = new Map<string, SessionSummary[]>();
  for (const session of visible) {
    const key = projectOf(session, projects)?.id ?? UNGROUPED;
    byKey.set(key, [...(byKey.get(key) ?? []), session]);
  }
  const groups: Group[] = projects.map((project) => page(project.id, project, byKey.get(project.id) ?? [], extra, current));
  const ungrouped = byKey.get(UNGROUPED) ?? [];
  if (ungrouped.length > 0) groups.push(page(UNGROUPED, null, ungrouped, extra, current));
  return groups;
}

function page(key: string, project: Project | null, rows: SessionSummary[], extra: Readonly<Record<string, number>>, current: string | null): Group {
  const limit = PAGE + (extra[key] ?? 0);
  const shown = rows.filter((session, index) => index < limit || sticky(session, current));
  return { key, project, count: rows.length, rows: shown, hidden: rows.length - shown.length };
}

export interface SearchHit {
  session: SessionSummary;
  project: Project | null;
}

/** 按标题和工作区名过滤，不分大小写，平铺，最近活动的在前，最多 {@link SEARCH_LIMIT} 条。 */
export function searchSessions(
  sessions: readonly SessionSummary[],
  projects: readonly Project[],
  query: string,
  showArchived: boolean,
): { hits: SearchHit[]; more: boolean } {
  const needle = query.trim().toLocaleLowerCase();
  const hits: SearchHit[] = [];
  for (const session of sessions.slice().sort(newestFirst)) {
    if (!showArchived && session.archived === true) continue;
    const project = projectOf(session, projects);
    const haystack = `${session.title}\n${project?.title ?? ""}`.toLocaleLowerCase();
    if (needle === "" || haystack.includes(needle)) hits.push({ session, project });
  }
  return { hits: hits.slice(0, SEARCH_LIMIT), more: hits.length > SEARCH_LIMIT };
}
