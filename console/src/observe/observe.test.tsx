import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RunNode, RunsSummary } from "../api/types";
import { currentRoute } from "../app/router";
import { ObservePage } from "./ObservePage";
import { RunPage } from "./RunPage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** 按地址回不同的 JSON；`status` 不是 200 时回错误体。 */
function serve(routes: Record<string, { status?: number; body: unknown }>) {
  const fetch = vi.fn((url: string) => {
    const path = url.split("?")[0] as string;
    const found = routes[path] ?? { status: 404, body: { code: "run/not-found", error: "no such run" } };
    const status = found.status ?? 200;
    return Promise.resolve({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(found.body)) });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

const SUMMARY: RunsSummary = {
  runs: 4,
  ends: { COMPLETED: 3, FAILED: 1 },
  toolErrorRuns: 1,
  tokens: 12_300,
  p50Ms: 2_000,
  p90Ms: 48_000,
  days: [],
  tools: ["list_dir", "read_file"],
  models: ["openai/qwen"],
};

describe("观测页面", () => {
  it("汇总数字、筛选清单来自汇总接口；请求带着时间窗和浏览器时区", async () => {
    const fetch = serve({ "/api/runs": { body: { runs: [] } }, "/api/runs/summary": { body: SUMMARY } });
    render(wrap(<ObservePage query="tool=list_dir" />));
    const summary = await screen.findByLabelText("汇总", { selector: "dl" });
    await within(summary).findByText("25%");
    expect(within(summary).getByText("12k")).toBeTruthy();
    const tools = screen.getByRole("combobox", { name: "工具" }) as HTMLSelectElement;
    expect([...tools.options].map((option) => option.value)).toEqual(["", "list_dir", "read_file"]);
    expect(tools.value).toBe("list_dir");
    const urls = fetch.mock.calls.map(([url]) => url as string);
    expect(urls.some((url) => url.startsWith("/api/runs/summary?") && url.includes("tool=list_dir") && url.includes("tz="))).toBe(true);
    expect(urls.some((url) => url.startsWith("/api/runs?") && url.includes("from="))).toBe(true);
  });

  it("没有运行时说明怎么产生第一条；改筛选写回地址", async () => {
    serve({ "/api/runs": { body: { runs: [] } }, "/api/runs/summary": { body: { ...SUMMARY, runs: 0, ends: {}, p50Ms: undefined } } });
    render(wrap(<ObservePage query="" />));
    expect(await screen.findByText(/这段时间没有运行/)).toBeTruthy();
    await userEvent.click(screen.getByRole("radio", { name: "24 小时" }));
    expect(currentRoute()).toEqual({ page: "runs", query: "window=24h" });
    await userEvent.click(screen.getByRole("button", { name: "失败" }));
    expect(currentRoute()).toEqual({ page: "runs", query: "end=FAILED" });
  });

  it("接口出错时显示错误和重试", async () => {
    serve({ "/api/runs": { status: 500, body: { code: "session/unreadable", error: "x" } }, "/api/runs/summary": { body: SUMMARY } });
    render(wrap(<ObservePage query="" />));
    const alert = await screen.findByRole("alert");
    expect(within(alert).getByRole("button", { name: "重试" })).toBeTruthy();
  });
});

const T = (seconds: number) => new Date(Date.parse("2026-11-15T00:00:00Z") + seconds * 1000).toISOString();

function tool(id: string, step: number, state: RunNode["state"] = "ok"): RunNode {
  return { id, parent: `r/s${step}`, kind: "tool", name: "list_dir", state, start: T(step), end: T(step + 0.1), ms: 100, record: id };
}

const TALLY = {
  tokens: { input: 7047, output: 843, cacheRead: 0, cacheWrite: 0 },
  summaryTokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  requests: 6,
  attempts: 6,
  retries: 0,
  toolCalls: 6,
  toolErrors: 1,
  tools: [],
  hooks: 0,
  hookErrors: 0,
  models: ["openai/qwen"],
  ms: 7_000,
};

const ROOT: RunNode = {
  id: "r",
  kind: "run",
  name: "PROMPT",
  state: "ok",
  start: T(0),
  end: T(7),
  ms: 7_000,
  record: "r",
  tally: TALLY,
  children: Array.from({ length: 6 }, (_, i) => ({
    id: `r/s${i + 1}`,
    parent: "r",
    kind: "step" as const,
    name: `step ${i + 1}`,
    state: i === 2 ? ("error" as const) : ("ok" as const),
    start: T(i + 1),
    end: T(i + 1.5),
    children: [tool(`t${i + 1}`, i + 1, i === 2 ? "error" : "ok")],
  })),
};

describe("运行详情", () => {
  it("顶部有汇总和重复调用的提示；点提示选中第一次调用，详情能跳到轨迹页", async () => {
    serve({ "/api/sessions/s1/runs/s1_5/tree": { body: { root: ROOT } } });
    render(wrap(<RunPage session="s1" run="s1_5" />));
    const note = await screen.findByRole("note", { name: "重复调用" });
    expect(note.textContent).toContain("list_dir 连续调用了 6 次（第 1–6 步）");
    expect(screen.getByText("7.9k")).toBeTruthy();
    await userEvent.click(within(note).getByRole("button"));
    const detail = screen.getByRole("region", { name: "详情" });
    expect(within(detail).getByRole("heading").textContent).toBe("list_dir");
    await userEvent.click(within(detail).getByRole("button", { name: "在轨迹页里看这条记录" }));
    expect(currentRoute()).toEqual({ page: "trace", session: "s1", record: "t1" });
  });

  it("只看出错：只留出错的步骤和调用", async () => {
    serve({ "/api/sessions/s1/runs/s1_5/tree": { body: { root: ROOT } } });
    render(wrap(<RunPage session="s1" run="s1_5" />));
    const tree = await screen.findByRole("tree", { name: "调用树" });
    expect(within(tree).getAllByRole("treeitem")).toHaveLength(12);
    await userEvent.click(screen.getByRole("switch", { name: "只看出错" }));
    expect(within(tree).getAllByRole("treeitem").map((item) => item.getAttribute("data-node"))).toEqual(["r/s3", "t3"]);
  });

  it("找不到的运行说找不到，能回到观测", async () => {
    serve({});
    render(wrap(<RunPage session="s1" run="s1_9" />));
    expect(await screen.findByText("找不到这次运行")).toBeTruthy();
    expect(screen.getByRole("button", { name: "回到观测" })).toBeTruthy();
  });
});
