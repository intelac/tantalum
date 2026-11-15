// 页面只有几类：登录、会话、轨迹、统计。自己写一个小路由，不引入路由库。

import { useSyncExternalStore } from "react";

export type Route =
  | { page: "home" }
  | { page: "login"; next?: string }
  | { page: "session"; session: string }
  | { page: "trace"; session: string; run?: string; record?: string }
  | { page: "stats"; session: string }
  | { page: "runs"; query: string }
  | { page: "run"; session: string; run: string }
  | { page: "notFound"; path: string };

export function parseRoute(pathname: string, search = ""): Route {
  const parts = pathname.split("/").filter((part) => part !== "");
  if (parts.length === 0) return { page: "home" };
  if (parts.length === 1 && parts[0] === "login") {
    const next = new URLSearchParams(search).get("next");
    return next !== null && isLocalPath(next) ? { page: "login", next } : { page: "login" };
  }
  if (parts[0] === "runs") {
    if (parts.length === 1) return { page: "runs", query: search.startsWith("?") ? search.slice(1) : search };
    if (parts.length === 3) {
      const session = decode(parts[1] as string);
      const run = decode(parts[2] as string);
      if (session !== null && run !== null) return { page: "run", session, run };
    }
    return { page: "notFound", path: pathname };
  }
  if (parts[0] === "s" && parts[1] !== undefined) {
    const session = decode(parts[1]);
    if (session === null) return { page: "notFound", path: pathname };
    if (parts.length === 2) return { page: "session", session };
    if (parts.length === 3 && parts[2] === "trace") {
      const record = new URLSearchParams(search).get("record");
      return record !== null && record !== "" ? { page: "trace", session, record } : { page: "trace", session };
    }
    if (parts.length === 3 && parts[2] === "stats") return { page: "stats", session };
    if (parts.length === 4 && parts[2] === "trace" && parts[3] !== undefined) {
      const run = decode(parts[3]);
      if (run !== null) return { page: "trace", session, run };
    }
  }
  return { page: "notFound", path: pathname };
}

/** 登录后的跳转目标只能是站内路径：`//evil.example` 和 `/\evil.example` 都会被浏览器当成别的主机。 */
export function isLocalPath(path: string): boolean {
  return path.startsWith("/") && !path.startsWith("//") && !path.startsWith("/\\");
}

function decode(part: string): string | null {
  try {
    const value = decodeURIComponent(part);
    return value === "" ? null : value;
  } catch {
    return null;
  }
}

/** 新会话只是一个新 ID；后端在第一次提交时才创建它。 */
export function newSessionId(): string {
  return `s-${crypto.randomUUID().slice(0, 8)}`;
}

export function sessionPath(session: string): string {
  return `/s/${encodeURIComponent(session)}`;
}

/** 轨迹页：不带参数时停在末尾；带运行时定位到那一轮（旧链接也是这个形式）。 */
export function tracePath(session: string, run?: string): string {
  const base = `${sessionPath(session)}/trace`;
  return run === undefined ? base : `${base}/${encodeURIComponent(run)}`;
}

export function statsPath(session: string): string {
  return `${sessionPath(session)}/stats`;
}

/** 轨迹页上的一条记录：展开所在的轮和调用、滚到并选中。 */
export function traceRecordPath(session: string, record: string): string {
  return `${sessionPath(session)}/trace?record=${encodeURIComponent(record)}`;
}

/** 观测页面；`query` 是筛选条件（不带 `?`），默认条件时为空。 */
export function runsPath(query = ""): string {
  return query === "" ? "/runs" : `/runs?${query}`;
}

/** 观测页面里的一次运行。 */
export function runPath(session: string, run: string): string {
  return `/runs/${encodeURIComponent(session)}/${encodeURIComponent(run)}`;
}

export function loginPath(next?: string): string {
  return next !== undefined && next !== "/" && next !== "/login" ? `/login?next=${encodeURIComponent(next)}` : "/login";
}

const listeners = new Set<() => void>();
let current: Route = typeof window === "undefined" ? { page: "home" } : parseRoute(window.location.pathname, window.location.search);

function refresh(): void {
  current = parseRoute(window.location.pathname, window.location.search);
  for (const listener of listeners) listener();
}

if (typeof window !== "undefined") window.addEventListener("popstate", refresh);

export function navigate(path: string, replace = false): void {
  if (replace) window.history.replaceState(null, "", path);
  else window.history.pushState(null, "", path);
  refresh();
}

export function currentRoute(): Route {
  return current;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useRoute(): Route {
  return useSyncExternalStore(subscribe, currentRoute, currentRoute);
}
