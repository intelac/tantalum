import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { TracePage } from "../api/types";
import { parseRoute, statsPath } from "../app/router";
import { TraceStore } from "../trace/TraceStore";
import { StatsPage } from "./StatsPage";

const FULL = JSON.parse(pageJson) as TracePage;
const sync = (flush: () => void) => flush();

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

/** 接口返回最新一页；它前面还有更早的页（hasMore），但更早的那页永远不回来。 */
function setup() {
  const latest: TracePage = { ...FULL, before: 10, hasMore: true };
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(latest)) })));
  const older = vi.fn(() => new Promise<TracePage>(() => {}));
  const store = new TraceStore(older, sync);
  render(<StatsPage store={store} session="s" />);
  return { store, older };
}

describe("统计页签", () => {
  it("每轮一行，带触发；展开后每步一行，没有记录的格显示“未记录”", async () => {
    setup();
    const table = await screen.findByRole("table", { name: "按轮次的统计" });
    const turn = within(table).getByRole("row", { name: "展开或折叠第 1 轮的每一步" });
    expect(turn.textContent).toContain("用户提问");
    expect(within(table).getByRole("row", { name: "展开或折叠轮次之间的每一步" }).textContent).toContain("手动压缩");
    await userEvent.click(turn);
    expect(turn.getAttribute("aria-expanded")).toBe("true");
    const step2 = within(table).getAllByRole("row").find((row) => row.textContent?.startsWith("第 2 步"));
    expect(step2?.textContent).toContain("未记录");
    expect(within(table).getAllByRole("row").some((row) => row.textContent?.includes("read_file, github/search, rm"))).toBe(true);
  });

  it("和轨迹共用分页：轨迹正在加载更早的页时，这里显示加载中而不是“已加载完”，也不会再发一个请求", async () => {
    const { store, older } = setup();
    await screen.findByRole("table", { name: "按轮次的统计" });
    act(() => {
      void store.loadOlder("s");
    });
    const button = screen.getByRole("button", { name: "正在加载更早的轮次…" });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    await userEvent.click(button);
    expect(older).toHaveBeenCalledTimes(1);
  });

  it("合计行默认显示，可以关掉；上下文增量默认关闭", async () => {
    setup();
    await screen.findByRole("table", { name: "按轮次的统计" });
    expect(screen.getByText("合计")).toBeTruthy();
    await userEvent.click(screen.getByRole("checkbox", { name: "显示合计行" }));
    expect(screen.queryByText("合计")).toBeNull();
    expect((screen.getByRole("checkbox", { name: "显示上下文增量" }) as HTMLInputElement).checked).toBe(false);
  });

  it("地址 /s/<会话>/stats", () => {
    expect(statsPath("s/1")).toBe("/s/s%2F1/stats");
    expect(parseRoute(statsPath("s/1"))).toEqual({ page: "stats", session: "s/1" });
  });
});
