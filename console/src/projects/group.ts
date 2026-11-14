// 侧栏怎么分组（workspaces W4、W9、W10、W13）。纯函数，不碰 DOM 和存储。

import type { ContentHit, Project, SessionSummary } from "../api/types";
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

/** 置顶的、运行中、等你回答、当前打开的：不管排第几都显示。 */
function sticky(session: SessionSummary, current: string | null): boolean {
  return session.id === current || session.pinned === true || session.awaitingAnswer === true || session.runStatus !== "idle";
}

function newestFirst(a: SessionSummary, b: SessionSummary): number {
  return b.lastActivity.localeCompare(a.lastActivity);
}

/** 置顶的在前，置顶的之间、其余的之间各按最近活动排（DSH 最近更新模式，session-find-pin R1.4）。 */
export function pinnedFirst(a: SessionSummary, b: SessionSummary): number {
  const pinned = Number(b.pinned === true) - Number(a.pinned === true);
  return pinned !== 0 ? pinned : newestFirst(a, b);
}

/**
 * 分组。工作区按给的顺序（服务器给的是加入时间新的在前），没有会话的工作区也列出来；“未分组”有会话时才列，放最后。
 * 组内置顶的在前、再按最近活动排，先显示 {@link PAGE} 条加上 {@code extra[key]} 条，置顶的几种总显示。平铺时一组，全部显示。
 */
export function groupSessions(
  sessions: readonly SessionSummary[],
  projects: readonly Project[],
  options: ViewOptions,
  extra: Readonly<Record<string, number>>,
  current: string | null,
): Group[] {
  const visible = sessions.filter((session) => options.showArchived || session.archived !== true).slice().sort(pinnedFirst);
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
  /** 按内容搜到的：命中处的一段原文。按标题、工作区名搜到的没有。 */
  snippet?: string;
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

/**
 * 本地结果在前，内容结果按会话去重接在后面（DSH WorkspaceBrowser，session-find-pin R3.3）。列表里没有的会话、
 * 被“显示已归档”过滤掉的会话丢掉；合计最多 {@link SEARCH_LIMIT} 条，多出或服务器说还有时 {@code more} 为 true。
 */
export function mergeHits(
  local: { hits: SearchHit[]; more: boolean },
  content: { hits: readonly ContentHit[]; more: boolean } | null,
  sessions: readonly SessionSummary[],
  projects: readonly Project[],
  showArchived: boolean,
): { hits: SearchHit[]; more: boolean } {
  if (content === null) return local;
  const seen = new Set(local.hits.map((hit) => hit.session.id));
  const byId = new Map(sessions.map((session) => [session.id, session]));
  const merged = [...local.hits];
  let more = local.more || content.more;
  for (const hit of content.hits) {
    const session = byId.get(hit.session);
    if (session === undefined || seen.has(hit.session) || (!showArchived && session.archived === true)) continue;
    seen.add(hit.session);
    if (merged.length >= SEARCH_LIMIT) {
      more = true;
      break;
    }
    merged.push({ session, project: projectOf(session, projects), snippet: hit.snippet });
  }
  return { hits: merged, more };
}
