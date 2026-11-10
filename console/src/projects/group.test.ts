import { describe, expect, it } from "vitest";
import type { Project, SessionSummary } from "../api/types";
import { FLAT, PAGE, SEARCH_LIMIT, UNGROUPED, groupSessions, projectOf, searchSessions } from "./group";

// workspaces 任务 18（W4、W9、W10、W13）。

const QUATTRO: Project = { id: "p-quattro", title: "QTR_Quattro_github_3", path: "C:\\APPL\\QTR_Quattro_github_3", created: "2026-11-10T00:00:00Z" };
const WIKI: Project = { id: "p-wiki", title: "知识库", path: "C:\\quattro-run\\wiki", created: "2026-11-09T00:00:00Z" };
const PROJECTS = [QUATTRO, WIKI];
const BY_PROJECT = { mode: "project", showArchived: false } as const;

function session(id: string, minute: number, more: Partial<SessionSummary> = {}): SessionSummary {
  return { id, title: `title ${id}`, lastActivity: `2026-11-10T10:${String(minute).padStart(2, "0")}:00Z`, runStatus: "idle", ...more };
}

describe("groupSessions", () => {
  it("puts sessions in the workspace whose path matches, ignoring case and slashes on Windows", () => {
    const sessions = [session("a", 1, { dir: "c:/appl/qtr_quattro_github_3/" }), session("b", 2), session("c", 3, { dir: "/elsewhere" })];
    expect(projectOf(sessions[0], PROJECTS)).toBe(QUATTRO);
    const groups = groupSessions(sessions, PROJECTS, BY_PROJECT, {}, null);
    expect(groups.map((group) => [group.key, group.count, group.rows.map((row) => row.id)])).toEqual([
      ["p-quattro", 1, ["a"]],
      ["p-wiki", 0, []],
      [UNGROUPED, 2, ["c", "b"]],
    ]);
  });

  it("leaves out the ungrouped bucket when it is empty", () => {
    const groups = groupSessions([session("a", 1, { dir: QUATTRO.path })], PROJECTS, BY_PROJECT, {}, null);
    expect(groups.map((group) => group.key)).toEqual(["p-quattro", "p-wiki"]);
  });

  it("shows five, then five more per click, and always the running, waiting and current ones", () => {
    const many = Array.from({ length: 12 }, (_, i) => session(`s${i}`, i, { dir: QUATTRO.path }));
    many[0] = { ...many[0], runStatus: "AwaitingModel" };
    many[1] = { ...many[1], awaitingAnswer: true, runStatus: "AwaitingAnswer" };
    const first = groupSessions(many, PROJECTS, BY_PROJECT, {}, "s2")[0];
    expect(first.rows.map((row) => row.id)).toEqual(["s11", "s10", "s9", "s8", "s7", "s2", "s1", "s0"]);
    expect(first.hidden).toBe(12 - first.rows.length);
    expect(first.count).toBe(12);
    // 再显示一次：前 10 条，加上排在最后、但在运行和等回答的两条
    const more = groupSessions(many, PROJECTS, BY_PROJECT, { "p-quattro": PAGE }, null)[0];
    expect(more.rows).toHaveLength(12);
    expect(more.hidden).toBe(0);
    const quiet = many.map((row) => ({ ...row, runStatus: "idle", awaitingAnswer: false }));
    const twice = groupSessions(quiet, PROJECTS, BY_PROJECT, { "p-quattro": PAGE }, null)[0];
    expect(twice.rows).toHaveLength(10);
    expect(twice.hidden).toBe(2);
  });

  it("hides archived sessions unless asked, and then counts them", () => {
    const sessions = [session("a", 1, { dir: QUATTRO.path, archived: true }), session("b", 2, { dir: QUATTRO.path })];
    expect(groupSessions(sessions, PROJECTS, BY_PROJECT, {}, null)[0].count).toBe(1);
    expect(groupSessions(sessions, PROJECTS, { mode: "project", showArchived: true }, {}, null)[0].count).toBe(2);
  });

  it("lists everything newest first in one group when flat", () => {
    const sessions = [session("a", 1, { dir: QUATTRO.path }), session("b", 3), session("c", 2, { dir: WIKI.path })];
    const groups = groupSessions(sessions, PROJECTS, { mode: "flat", showArchived: false }, {}, null);
    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe(FLAT);
    expect(groups[0].rows.map((row) => row.id)).toEqual(["b", "c", "a"]);
  });
});

describe("searchSessions", () => {
  const sessions = [
    session("a", 1, { title: "Review the uncommitted code", dir: QUATTRO.path }),
    session("b", 2, { title: "index.md 第一行写的是什么", dir: WIKI.path }),
    session("c", 3, { title: "分支检查", archived: true }),
  ];

  it("matches titles and workspace names without caring about case", () => {
    expect(searchSessions(sessions, PROJECTS, "REVIEW", false).hits.map((hit) => hit.session.id)).toEqual(["a"]);
    expect(searchSessions(sessions, PROJECTS, "知识库", false).hits.map((hit) => hit.project?.id)).toEqual(["p-wiki"]);
  });

  it("leaves archived sessions out unless they are shown", () => {
    expect(searchSessions(sessions, PROJECTS, "分支", false).hits).toEqual([]);
    expect(searchSessions(sessions, PROJECTS, "分支", true).hits).toHaveLength(1);
  });

  it("stops at the limit and says there are more", () => {
    const many = Array.from({ length: SEARCH_LIMIT + 3 }, (_, i) => session(`s${i}`, i % 60, { title: "same" }));
    const found = searchSessions(many, [], "same", false);
    expect(found.hits).toHaveLength(SEARCH_LIMIT);
    expect(found.more).toBe(true);
  });
});
