import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { TracePage as Page, TraceRecord } from "../api/types";
import { matchRecords } from "./search";
import { totalParts } from "./TraceTotals";
import { TracePage } from "./TracePage";
import { TraceStore } from "./TraceStore";
import { totalsOf } from "./totals";
import { zh } from "../text/zh";

// 记录表是虚拟滚动的，jsdom 里没有高度就一行也不画：这里换成把收到的行直接列出来，测的是页面交给它哪些行。
vi.mock("./Ledger", () => ({
  Ledger: ({ rows, selected }: { rows: readonly import("./rows").Row[]; selected: string | null }) => (
    <div>
      {rows.map((row) =>
        row.type === "record" ? (
          <div key={row.key} data-record={row.record.id} aria-selected={selected === row.record.id} />
        ) : row.type === "turn" ? (
          <div key={row.key}>{row.turn === undefined ? "轮次之间" : `标题 ${row.turn}`}</div>
        ) : null,
      )}
    </div>
  ),
}));

// 后端 WireFormatTest 的场景：五轮（运行 s_0、s_10、s_13、s_18、s_23），第 4、5 轮之间有一次手动压缩。
const PAGE = JSON.parse(pageJson) as Page;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function open(page: Page = PAGE, props: { run?: string; record?: string } = {}) {
  const older = vi.fn(() => new Promise<Page>(() => undefined));
  const store = new TraceStore(older);
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(page)) })));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ height: 800 }}>
        <TracePage store={store} session="s" {...props} />
      </div>
    </QueryClientProvider>,
  );
  return { store, older };
}

/** 等第一页记录到了（标签里有了第 1 轮）再往下。 */
const tablist = async () => {
  const list = await screen.findByRole("tablist", { name: "轮次" });
  await within(list).findByRole("tab", { name: /^第 1 轮/ });
  return list;
};
const tab = async (name: string) => within(await tablist()).getByRole("tab", { name: new RegExp(`^${name}`) });
/** 记录表里现在有哪些记录行。 */
const shown = () => [...document.querySelectorAll<HTMLElement>("[data-record]")].map((row) => row.dataset.record as string);
const text = (records: readonly TraceRecord[]) => totalParts(totalsOf(records), zh.trace.totals, false).join(" · ");

/** trace-rounds 需求 1、2：轮次标签和总数。 */
describe("TracePage turn tabs and totals", () => {
  it("has a tab for all and for every turn, with the turn's question as the hint", async () => {
    open();
    const tabs = within(await tablist()).getAllByRole("tab");
    expect(tabs.map((each) => each.textContent?.replace(/进行中|失败|在等回答/g, ""))).toEqual(["全部", "第 1 轮", "第 2 轮", "第 3 轮", "第 4 轮", "第 5 轮"]);
    expect(tabs[0]?.getAttribute("aria-selected")).toBe("true");
    const prompt = PAGE.records.find((record) => record.kind === "user" && record.run === "s_0" && record.mode === "PROMPT");
    expect(prompt?.kind === "user" && tabs[1]?.getAttribute("title")?.startsWith(prompt.text.replace(/\s+/g, " ").trim())).toBe(true);
  });

  it("picking a turn leaves only that turn in the table and in the totals; all brings everything back", async () => {
    open();
    await tablist();
    await waitFor(() => expect(shown().length).toBeGreaterThan(5));
    const everything = shown();
    expect(screen.getByText(text(PAGE.records))).toBeTruthy();

    await userEvent.click(await tab("第 2 轮"));
    expect((await tab("第 2 轮")).getAttribute("aria-selected")).toBe("true");
    const second = PAGE.records.filter((record) => record.run === "s_10");
    await waitFor(() => expect(shown().every((id) => second.some((record) => record.id === id))).toBe(true));
    expect(shown().length).toBeGreaterThan(0);
    expect(shown().length).toBeLessThan(everything.length);
    // 轮标题只剩这一轮；手动压缩（轮次之间）不在
    expect(screen.queryByText("轮次之间")).toBeNull();
    expect(screen.getByText(text(second))).toBeTruthy();
    expect(screen.queryByText(text(PAGE.records))).toBeNull();

    await userEvent.click(await tab("全部"));
    await waitFor(() => expect(shown()).toEqual(everything));
    expect(screen.getByText("轮次之间")).toBeTruthy();
  });

  it("marks a failed turn and a running one on their tabs", async () => {
    const records = PAGE.records.map((record): TraceRecord => {
      if (record.kind !== "run") return record;
      if (record.run === "s_10") return { ...record, endReason: "FAILED" };
      if (record.run === "s_23") return { ...record, end: undefined, endReason: undefined };
      return record;
    });
    open({ ...PAGE, records });
    expect((await tab("第 2 轮")).dataset.state).toBe("failed");
    expect((await tab("第 5 轮")).dataset.state).toBe("running");
    expect((await tab("第 5 轮")).textContent).toContain("进行中");
    // 有一轮还在跑：耗时不把它算成 0，写明
    expect(screen.getByText(/还有一轮进行中/)).toBeTruthy();
  });

  it("moves between the tabs with the arrow keys and selects with Enter", async () => {
    open();
    const all = await tab("全部");
    all.focus();
    await userEvent.keyboard("{ArrowRight}{ArrowRight}");
    expect(document.activeElement).toBe(await tab("第 2 轮"));
    expect(all.getAttribute("aria-selected")).toBe("true");
    await userEvent.keyboard("{Enter}");
    expect((await tab("第 2 轮")).getAttribute("aria-selected")).toBe("true");
    await userEvent.keyboard("{ArrowLeft}{ArrowLeft}{ArrowLeft}");
    expect(document.activeElement).toBe(await tab("第 5 轮"));
  });

  it("offers earlier turns when there are more, and says the totals are of the loaded part", async () => {
    const { older } = open({ ...PAGE, hasMore: true, before: 5 });
    await tablist();
    expect(screen.getByText(new RegExp(`^已加载的部分：`))).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "更早" }));
    expect(older).toHaveBeenCalledTimes(1);
    // 选了一轮：这一轮是完整的，不再写“已加载的部分”
    await userEvent.click(await tab("第 2 轮"));
    expect(screen.queryByText(/^已加载的部分：/)).toBeNull();
    cleanup();
    open();
    await tablist();
    expect(screen.queryByRole("button", { name: "更早" })).toBeNull();
    expect(screen.queryByText(/^已加载的部分：/)).toBeNull();
  });

  it("a new record of the selected turn shows up; one of another turn does not change the selection", async () => {
    const { store } = open();
    await userEvent.click(await tab("第 2 轮"));
    const before = shown().length;
    const sample = PAGE.records.find((record) => record.kind === "tool") as Extract<TraceRecord, { kind: "tool" }>;
    const mine: TraceRecord = { ...sample, id: "t:900.0", pos: "900.0", run: "s_10", turn: 2, step: 1, request: undefined };
    const other: TraceRecord = { ...sample, id: "t:901.0", pos: "901.0", run: "s_23", turn: 5, step: 1, request: undefined };
    act(() => store.applyRecord(mine, PAGE.seq + 1));
    await waitFor(() => expect(shown()).toContain("t:900.0"));
    act(() => store.applyRecord(other, PAGE.seq + 2));
    expect(shown()).not.toContain("t:901.0");
    expect(shown().length).toBe(before + 1);
    expect((await tab("第 2 轮")).getAttribute("aria-selected")).toBe("true");
  });

  it("search only walks through what is shown", async () => {
    open();
    await tablist();
    const needle = (PAGE.records.find((record) => record.run === "s_23" && record.kind === "tool") as Extract<TraceRecord, { kind: "tool" }>).name;
    // 搜的是每条记录的可搜索文字（请求、钩子里也可能提到这个工具名）；运行头不是表里的一行，不算
    const hits = (records: readonly TraceRecord[]) => matchRecords(records.filter((record) => record.kind !== "run"), needle).size;
    const all = hits(PAGE.records);
    const box = screen.getByRole("searchbox", { name: /搜索/ });
    await userEvent.type(box, needle);
    expect(await screen.findByText(`0/${all}`)).toBeTruthy();
    const inFifth = hits(PAGE.records.filter((record) => record.run === "s_23"));
    await userEvent.click(await tab("第 5 轮"));
    expect(inFifth).toBeLessThan(all);
    expect(await screen.findByText(`0/${inFifth}`)).toBeTruthy();
  });

  it("locating a record from the address goes back to all turns first", async () => {
    const target = PAGE.records.find((record) => record.run === "s_23" && record.kind === "tool") as TraceRecord;
    const page = { ...PAGE };
    const older = vi.fn(() => new Promise<Page>(() => undefined));
    const store = new TraceStore(older);
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(page)) })));
    const view = (record?: string) => (
      <QueryClientProvider client={new QueryClient()}>
        <div style={{ height: 800 }}>
          <TracePage store={store} session="s" record={record} />
        </div>
      </QueryClientProvider>
    );
    const { rerender } = render(view());
    await userEvent.click(await tab("第 2 轮"));
    expect(shown()).not.toContain(target.id);
    rerender(view(target.id));
    await waitFor(() => expect((document.querySelector(`[data-record="${target.id}"]`) as HTMLElement | null)?.getAttribute("aria-selected")).toBe("true"));
    expect((await tab("全部")).getAttribute("aria-selected")).toBe("true");
  });
});
