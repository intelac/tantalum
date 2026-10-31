import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Trace } from "../api/types";
import { EventList } from "./EventList";
import { TimeBars } from "./TimeBars";

afterEach(cleanup);

const trace: Trace = {
  events: [
    { seq: 0, at: "2026-10-31T00:00:00Z", type: "UserPrompt", phase: "AwaitingContext" },
    { seq: 1, at: "2026-10-31T00:00:01Z", type: "ContextAssembled", phase: "AwaitingModel", turn: 1 },
    { seq: 2, at: "2026-10-31T00:00:06Z", type: "ModelReplied", phase: "AwaitingTools" },
    { seq: 3, at: "2026-10-31T00:00:10Z", type: "ToolFinished", phase: "AwaitingModel", turn: 2 },
    { seq: 4, at: "2026-10-31T00:00:12Z", type: "ModelFailed", phase: "Ended" },
  ],
  segments: [
    { handler: "ContextHandler", from: "2026-10-31T00:00:00Z", to: "2026-10-31T00:00:01Z" },
    { handler: "ModelHandler", from: "2026-10-31T00:00:01Z", to: "2026-10-31T00:00:06Z" },
    { handler: "ToolHandler", from: "2026-10-31T00:00:06Z", to: "2026-10-31T00:00:10Z" },
    { handler: "ModelHandler", from: "2026-10-31T00:00:10Z", to: "2026-10-31T00:00:12Z" },
  ],
};

describe("TimeBars", () => {
  it("draws one bar per segment on the right row with a duration title", () => {
    const { container } = render(<TimeBars trace={trace} now={0} />);
    const bars = Array.from(container.querySelectorAll("[title]"));
    expect(bars).toHaveLength(4);
    // 按行渲染：输入、模型、工具。
    expect(bars.map((bar) => bar.getAttribute("title"))).toEqual(["组装上下文 · 1 秒", "调用模型 · 5 秒", "调用模型 · 2 秒", "执行工具 · 4 秒"]);
    expect(bars[1]?.className).toContain("bg-bar-model");
    expect((bars[3] as HTMLElement).style.left).toBe("50%");
  });
});

describe("EventList", () => {
  it("shows type tags, handlers and lets the model-call rows be selected", async () => {
    const onSelect = vi.fn();
    render(<EventList trace={trace} selectedTurn={2} onSelectTurn={onSelect} />);
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(5);
    expect(items[0]?.textContent).toContain("用户");
    expect(items[0]?.textContent).toContain("组装上下文");
    expect(items[4]?.textContent).toContain("失败");
    expect(items[4]?.textContent).toContain("+12.0s");

    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(2);
    expect(buttons[0]?.textContent).toContain("第 1 次模型调用");
    expect(buttons[1]?.getAttribute("aria-pressed")).toBe("true");
    await userEvent.click(buttons[0] as HTMLElement);
    expect(onSelect).toHaveBeenCalledWith(1);
  });
});
