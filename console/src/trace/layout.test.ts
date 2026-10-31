import { describe, expect, it } from "vitest";
import type { Trace } from "../api/types";
import { layoutBars, offsetSeconds, rowOf, spanOf } from "./layout";

const trace: Trace = {
  events: [
    { seq: 0, at: "2026-10-31T00:00:00Z", type: "UserPrompt", phase: "AwaitingContext" },
    { seq: 1, at: "2026-10-31T00:00:01Z", type: "ContextAssembled", phase: "AwaitingModel", turn: 1 },
    { seq: 2, at: "2026-10-31T00:00:06Z", type: "ModelReplied", phase: "AwaitingTools" },
    { seq: 3, at: "2026-10-31T00:00:10Z", type: "ToolFinished", phase: "Ended" },
  ],
  segments: [
    { handler: "ContextHandler", from: "2026-10-31T00:00:00Z", to: "2026-10-31T00:00:01Z" },
    { handler: "ModelHandler", from: "2026-10-31T00:00:01Z", to: "2026-10-31T00:00:06Z" },
    { handler: "ToolHandler", from: "2026-10-31T00:00:06Z", to: "2026-10-31T00:00:10Z" },
  ],
};

describe("trace layout", () => {
  it("maps handlers to the three rows", () => {
    expect(rowOf("ContextHandler")).toBe("input");
    expect(rowOf("ModelHandler")).toBe("model");
    expect(rowOf("TimerHandler")).toBe("model");
    expect(rowOf("ToolHandler")).toBe("tool");
    expect(rowOf("WorkHandler")).toBe("tool");
  });

  it("positions bars as percentages of the whole run", () => {
    const bars = layoutBars(trace, 0);
    expect(bars.map((bar) => [bar.row, Math.round(bar.left), Math.round(bar.width)])).toEqual([
      ["input", 0, 10],
      ["model", 10, 50],
      ["tool", 60, 40],
    ]);
    expect(bars[1]?.durationMs).toBe(5000);
    expect(bars.every((bar) => !bar.open)).toBe(true);
  });

  it("extends an open segment to now", () => {
    const open: Trace = { events: trace.events.slice(0, 2), segments: [trace.segments[0]!, { handler: "ModelHandler", from: "2026-10-31T00:00:01Z" }] };
    const now = new Date("2026-10-31T00:00:21Z").getTime();
    expect(spanOf(open, now)).toEqual({ start: new Date("2026-10-31T00:00:00Z").getTime(), end: now });
    const bars = layoutBars(open, now);
    expect(bars[1]).toMatchObject({ open: true, durationMs: 20000 });
    expect(Math.round(bars[1]!.left + bars[1]!.width)).toBe(100);
  });

  it("formats offsets and tolerates an empty trace", () => {
    const start = new Date("2026-10-31T00:00:00Z").getTime();
    expect(offsetSeconds("2026-10-31T00:00:06.250Z", start)).toBe("6.3s");
    expect(layoutBars({ events: [], segments: [] }, 5)).toEqual([]);
  });
});
