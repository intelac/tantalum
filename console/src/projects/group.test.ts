import { describe, expect, it } from "vitest";
import type { Project, SessionSummary } from "../api/types";
import { FLAT, PAGE, SEARCH_LIMIT, UNGROUPED, groupSessions, mergeHits, nestChildren, projectOf, searchSessions } from "./group";

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

// session-find-pin R1.4、R3.3。
describe("pinned sessions", () => {
  it("come first in their group, each part newest first, and are never hidden by the page size", () => {
    const sessions = Array.from({ length: PAGE + 3 }, (_, i) => session(`s${i}`, i + 1));
    sessions[0] = { ...sessions[0], pinned: true };
    sessions[2] = { ...sessions[2], pinned: true };
    const [group] = groupSessions(sessions, [], BY_PROJECT, {}, null);
    expect(group.rows.map((row) => row.id).slice(0, 3)).toEqual(["s2", "s0", `s${PAGE + 2}`]);
    expect(group.rows.map((row) => row.id)).toContain("s0");
    const [flat] = groupSessions(sessions, [], { mode: "flat", showArchived: false }, {}, null);
    expect(flat.rows.map((row) => row.id).slice(0, 2)).toEqual(["s2", "s0"]);
  });
});

describe("mergeHits", () => {
  const sessions = [session("a", 1, { title: "invoice" }), session("b", 2), session("c", 3, { archived: true }), session("d", 4)];
  const local = searchSessions(sessions, [], "invoice", false);

  it("keeps local hits first and appends content hits once, with their snippet", () => {
    const merged = mergeHits(
      local,
      {
        hits: [
          { session: "a", role: "user", snippet: "x" },
          { session: "d", role: "assistant", snippet: "the invoice total" },
          { session: "gone", role: "user", snippet: "y" },
          { session: "c", role: "user", snippet: "archived" },
        ],
        more: false,
      },
      sessions,
      [],
      false,
    );
    expect(merged.hits.map((hit) => [hit.session.id, hit.snippet])).toEqual([
      ["a", undefined],
      ["d", "the invoice total"],
    ]);
    expect(merged.more).toBe(false);
  });

  it("shows archived content hits when archived sessions are shown, and passes on more", () => {
    const merged = mergeHits(local, { hits: [{ session: "c", role: "user", snippet: "archived" }], more: true }, sessions, [], true);
    expect(merged.hits.map((hit) => hit.session.id)).toEqual(["a", "c"]);
    expect(merged.more).toBe(true);
  });

  it("stops at the limit", () => {
    const many = Array.from({ length: SEARCH_LIMIT + 2 }, (_, i) => session(`m${i}`, i % 60));
    const merged = mergeHits(
      { hits: [], more: false },
      { hits: many.map((s) => ({ session: s.id, role: "user" as const, snippet: "z" })), more: false },
      many,
      [],
      false,
    );
    expect(merged.hits).toHaveLength(SEARCH_LIMIT);
    expect(merged.more).toBe(true);
  });

  it("is the local result while content search has not answered", () => {
    expect(mergeHits(local, null, sessions, [], false)).toBe(local);
  });

  // memory-final-plan 第 12 节：委派开的子会话紧跟在父会话后面
  it("puts delegated sub-conversations right after their parent", () => {
    const at = (minutes: number) => new Date(Date.UTC(2026, 10, 16, 0, minutes)).toISOString();
    const rows: SessionSummary[] = [
      { id: "d-1", title: "child", lastActivity: at(9), runStatus: "idle", parent: "p" },
      { id: "x", title: "other", lastActivity: at(8), runStatus: "idle" },
      { id: "p", title: "parent", lastActivity: at(7), runStatus: "idle" },
      { id: "d-2", title: "grandchild", lastActivity: at(6), runStatus: "idle", parent: "d-1" },
      { id: "d-3", title: "orphan", lastActivity: at(5), runStatus: "idle", parent: "gone" },
    ];
    expect(nestChildren(rows).map((row) => row.id)).toEqual(["x", "p", "d-1", "d-2", "d-3"]);
  });
});
