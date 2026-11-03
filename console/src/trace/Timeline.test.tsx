import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import pageJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { TracePage } from "../api/types";
import { buildLedger } from "./rows";
import { buildTimeline, type TimeRange } from "./segments";
import { MINIMUM_HIT_PX, Timeline, TOOLTIP_DELAY_MS } from "./Timeline";

const RECORDS = (JSON.parse(pageJson) as TracePage).records;
const ROWS = buildLedger(RECORDS, new Set(), new Set());
const SPANS = buildTimeline(ROWS, "sequence")?.spans.length ?? 0;
const WIDTH = 1000;

beforeEach(() => {
  // jsdom 不排版：轨道当作从 0 开始、1000 px 宽。
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({ left: 0, top: 0, width: WIDTH, height: 64, right: WIDTH, bottom: 64, x: 0, y: 0, toJSON: () => ({}) } as DOMRect);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

function show(props: Partial<Parameters<typeof Timeline>[0]> = {}) {
  const handlers = { onRange: vi.fn(), onSelect: vi.fn(), onFocus: vi.fn(), onLoadEarlier: vi.fn() };
  const view = render(<Timeline rows={ROWS} mode="sequence" range={null} selected={null} matches={new Set()} hasEarlier={false} {...handlers} {...props} />);
  const track = screen.getByRole("group", { name: /时间线概览/ });
  return { ...handlers, track, view };
}

/** 序号模式下第 i 个操作中间的横坐标。 */
const xOf = (index: number) => ((index + 0.5) / SPANS) * WIDTH;

function press(target: Element, x: number, button = 0) {
  fireEvent.pointerDown(target, { clientX: x, button, pointerId: 1 });
}

function release(target: Element, x: number) {
  fireEvent.pointerUp(target, { clientX: x, button: 0, pointerId: 1 });
}

describe("时间概览", () => {
  it("每条记录一段，四条道；段是按钮，Enter 选中", () => {
    const { onSelect } = show();
    const segments = screen.getAllByRole("button", { name: /，/ });
    expect(segments).toHaveLength(SPANS);
    expect(screen.getByText("插件")).toBeTruthy();
    fireEvent.keyDown(segments[0] as HTMLElement, { key: "Enter" });
    expect(onSelect).toHaveBeenCalledWith(RECORDS.find((record) => record.kind !== "run")?.id);
  });

  it("每段的点击区至少 6 px", () => {
    show();
    for (const segment of screen.getAllByRole("button", { name: /，/ })) {
      const width = segment.style.width;
      expect(width === `${MINIMUM_HIT_PX}px` || width.startsWith(`max(${MINIMUM_HIT_PX}px`), width).toBe(true);
    }
  });

  it("移动小于 3 px 算点击：点中段就选中并清除区间", () => {
    const { onSelect, onRange } = show({ range: { start: 0, end: 3 } });
    const segment = screen.getAllByRole("button", { name: /，/ })[4] as HTMLElement;
    press(segment, xOf(4));
    release(segment, xOf(4) + 2);
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onRange).toHaveBeenLastCalledWith(null);
  });

  it("拖选得到区间；不足最小宽时展开到一个操作宽", () => {
    const { track, onRange, onSelect } = show();
    press(track, xOf(2));
    fireEvent.pointerMove(track, { clientX: xOf(6), pointerId: 1 });
    release(track, xOf(6));
    const range = onRange.mock.calls.at(-1)?.[0] as TimeRange;
    expect(range.start).toBeCloseTo(2.5);
    expect(range.end).toBeCloseTo(6.5);
    expect(onSelect).not.toHaveBeenCalled();

    press(track, xOf(10));
    release(track, xOf(10) + 5);
    const small = onRange.mock.calls.at(-1)?.[0] as TimeRange;
    expect(small.end - small.start).toBeCloseTo(1);
  });

  it("点空白处：区间展开到最小宽，记录表滚到最近的记录，选中不变", () => {
    const { track, onRange, onFocus, onSelect } = show();
    press(track, xOf(3));
    release(track, xOf(3));
    const range = onRange.mock.calls.at(-1)?.[0] as TimeRange;
    expect(range.end - range.start).toBeCloseTo(1);
    expect(onFocus).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("右键单击、双击、Esc 都清除区间", () => {
    const { track, onRange } = show({ range: { start: 1, end: 4 } });
    onRange.mockClear();
    press(track, 100, 2);
    release(track, 100);
    fireEvent.doubleClick(track);
    fireEvent.keyDown(track, { key: "Escape" });
    expect(onRange.mock.calls).toEqual([[null], [null], [null]]);
  });

  it("滚轮以指针为锚点放大，视口外的段不渲染；放大后右键拖动平移", () => {
    show();
    const root = screen.getByRole("region", { name: "轨迹时间线" });
    act(() => {
      root.dispatchEvent(new WheelEvent("wheel", { deltaY: -1500, clientX: 0, bubbles: true, cancelable: true }));
    });
    const zoomed = screen.getAllByRole("button", { name: /，/ });
    expect(zoomed.length).toBeLessThan(SPANS);
    const firstBefore = zoomed[0]?.getAttribute("data-span");
    const track = screen.getByRole("group", { name: /时间线概览/ });
    press(track, 600, 2);
    fireEvent.pointerMove(track, { clientX: 100, pointerId: 1 });
    release(track, 100);
    expect(screen.getAllByRole("button", { name: /，/ })[0]?.getAttribute("data-span")).not.toBe(firstBefore);
  });

  it("悬停 500 ms 才出提示，内容有类型、时刻和时长", () => {
    vi.useFakeTimers();
    show({ mode: "duration" });
    const request = screen.getAllByRole("button", { name: /^助手，/ })[0] as HTMLElement;
    fireEvent.pointerEnter(request);
    act(() => {
      vi.advanceTimersByTime(TOOLTIP_DELAY_MS - 10);
    });
    expect(screen.queryByRole("tooltip")).toBeNull();
    act(() => {
      vi.advanceTimersByTime(20);
    });
    const tooltip = screen.getByRole("tooltip").textContent ?? "";
    expect(tooltip).toMatch(/^助手 · /);
    expect(tooltip).toMatch(/→/);
    expect(tooltip).toMatch(/首 token .* · 解码 /);
  });

  it("没有计时数据时显示“无计时数据”；有更早的历史时左边可以加载", () => {
    render(<Timeline rows={[]} mode="duration" range={null} selected={null} matches={new Set()} hasEarlier={false} onRange={vi.fn()} onSelect={vi.fn()} onFocus={vi.fn()} onLoadEarlier={vi.fn()} />);
    expect(screen.getByText("无计时数据")).toBeTruthy();
    cleanup();
    const { onLoadEarlier } = show({ hasEarlier: true });
    fireEvent.click(screen.getByRole("button", { name: "加载更早的历史" }));
    expect(onLoadEarlier).toHaveBeenCalledTimes(1);
  });
});
