import { describe, expect, it } from "vitest";
import { fillDays, MAX_DAYS, scale } from "./charts";

describe("fillDays", () => {
  it("fills every day from the window start to today, empty days included", () => {
    const now = new Date(2026, 10, 15, 13, 0).getTime();
    const from = new Date(2026, 10, 12, 13, 0);
    const bars = fillDays({ days: [{ day: "2026-11-13", ends: { COMPLETED: 2, FAILED: 1 }, tokens: 300 }] }, from, now);
    expect(bars.map((bar) => bar.day)).toEqual(["2026-11-12", "2026-11-13", "2026-11-14", "2026-11-15"]);
    expect(bars[1]).toEqual({ day: "2026-11-13", ends: { COMPLETED: 2, FAILED: 1 }, runs: 3, tokens: 300 });
    expect(bars[0]?.runs).toBe(0);
  });

  it("starts at the first day with runs when there is no window, and keeps at most 90 days", () => {
    const now = new Date(2026, 10, 15).getTime();
    expect(fillDays({ days: [{ day: "2026-11-14", ends: {}, tokens: 0 }] }, undefined, now).map((bar) => bar.day)).toEqual([
      "2026-11-14",
      "2026-11-15",
    ]);
    const long = fillDays({ days: [{ day: "2025-01-01", ends: {}, tokens: 0 }] }, undefined, now);
    expect(long).toHaveLength(MAX_DAYS);
    expect(long.at(-1)?.day).toBe("2026-11-15");
    expect(fillDays({ days: [] }, undefined, now).map((bar) => bar.day)).toEqual(["2026-11-15"]);
  });
});

describe("scale", () => {
  it("rounds the top up to a 1-2-5 step with three or four ticks", () => {
    expect(scale(7)).toEqual({ top: 10, ticks: [0, 5, 10] });
    expect(scale(19)).toEqual({ top: 20, ticks: [0, 10, 20] });
    expect(scale(1)).toEqual({ top: 1, ticks: [0, 0.5, 1] });
    expect(scale(0)).toEqual({ top: 1, ticks: [0, 1] });
    expect(scale(123_456).top).toBeGreaterThanOrEqual(123_456);
  });
});
